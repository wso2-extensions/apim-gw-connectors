/*
 * Copyright (c) 2025 WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.kong.client;

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import feign.Feign;
import feign.FeignException;
import feign.RequestInterceptor;
import feign.gson.GsonDecoder;
import feign.gson.GsonEncoder;
import feign.slf4j.Slf4jLogger;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.osgi.service.component.annotations.Component;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.model.ConfigurationDto;
import org.wso2.carbon.apimgt.api.model.Environment;
import org.wso2.carbon.apimgt.api.model.GatewayAgentConfiguration;
import org.wso2.carbon.apimgt.api.model.GatewayEnvironmentValidationResult;
import org.wso2.carbon.apimgt.api.model.GatewayMode;
import org.wso2.carbon.apimgt.api.model.GatewayPortalConfiguration;
import org.wso2.carbon.apimgt.impl.kmclient.ApacheFeignHttpClient;
import org.wso2.kong.client.model.KongConsumerGroup;
import org.wso2.kong.client.model.PagedResponse;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


/**
 * This class contains the configurations related to Kong Gateway.
 */
@Component(
        name = "kong.external.gateway.configuration.component",
        immediate = true,
        service = GatewayAgentConfiguration.class
)
public class KongGatewayConfiguration implements GatewayAgentConfiguration {
    private static final Log log = LogFactory.getLog(KongGatewayConfiguration.class);
    private static final String INCOMPLETE_KONG_CONFIGURATION =
            "The gateway configuration you added is incomplete. Provide the required Kong gateway details.";
    private static final String INVALID_KONG_CONFIGURATION =
            "The Kong gateway configuration you added is invalid. Verify the admin URL, control plane ID,"
    + " and access token.";
    private static final String INVALID_KONG_PLAN_MAPPING_CONFIGURATION =
            "The Kong consumer group assignments you added are invalid. Verify the Kong consumer group names.";
    private static final String PLAN_MAPPING_CONFIG_NAME = "plan_mapping";
    private static final String PLAN_MAPPING_PROPERTY_PREFIX = "plan_mapping.";
    private static final String PLAN_MAPPING_CONFIG_TYPE = "plan_mapping";
    private static final String CONSUMER_GROUP_NAME_LABEL = "Kong Consumer Group Name";

    @Override
    public String getGatewayDeployerImplementation() {
        return null;
    }

    @Override
    public String getImplementation() {
        // Deprecated method, kept for backward compatibility
        return getGatewayDeployerImplementation();
    }

    public String getDiscoveryImplementation() {
        return KongFederatedAPIDiscovery.class.getName();
    }

    @Override
    public String getApiKeyConnectorImplementation() {
        return KongFederatedApiKeyConnector.class.getName();
    }

    @Override
    public List<ConfigurationDto> getConnectionConfigurations() {

        List<ConfigurationDto> standaloneConfigValues = new ArrayList<>();

        standaloneConfigValues.add(
                new ConfigurationDto(KongConstants.KONG_ADMIN_URL, "Admin URL", "input", "Admin URL", "", true, false,
                        Collections.emptyList(), false));
        standaloneConfigValues.add(
                new ConfigurationDto(KongConstants.KONG_CONTROL_PLANE_ID, "Control Plane ID", "input",
                        "Control Plane ID", "", true, true, Collections.emptyList(), false));
        standaloneConfigValues.add(new ConfigurationDto(KongConstants.KONG_AUTH_TOKEN, "Access Token", "input",
                "Access Token for Authentication", "", true, true, Collections.emptyList(), false));

        List<ConfigurationDto> deploymentValues = new ArrayList<>();

        deploymentValues.add(
                new ConfigurationDto(KongConstants.KONG_STANDALONE_DEPLOYMENT, KongConstants.KONG_STANDALONE_DEPLOYMENT,
                        "labelOnly", "Select to use standalone deployment", "", false, false, standaloneConfigValues,
                        true));
        deploymentValues.add(
                new ConfigurationDto(KongConstants.KONG_KUBERNETES_DEPLOYMENT, KongConstants.KONG_KUBERNETES_DEPLOYMENT,
                        "labelOnly", "Select to use kubernetes deployment", "", false, false, Collections.emptyList(),
                        true));

        List<ConfigurationDto> configurationDtos = new ArrayList<>();
        configurationDtos.add(new ConfigurationDto(KongConstants.KONG_DEPLOYMENT_TYPE, "Deployment Type", "options",
                "Select the Kong Gateway deployment type", KongConstants.KONG_STANDALONE_DEPLOYMENT, true,
                false, deploymentValues, false));
        configurationDtos.add(buildPlanMappingConfiguration());
        return configurationDtos;
    }

    public GatewayEnvironmentValidationResult validateEnvironment(Environment environment) {
        Map<String, String> errors = new LinkedHashMap<>();
        String description = null;
        Map<String, String> additionalProperties = environment.getAdditionalProperties();
        if (additionalProperties == null) {
            log.warn("Kong gateway validation failed due to missing additional properties.");
            description = INCOMPLETE_KONG_CONFIGURATION;
            return buildValidationResult(errors, description);
        }
        String deploymentType = additionalProperties.get(KongConstants.KONG_DEPLOYMENT_TYPE);
        if (KongConstants.KONG_KUBERNETES_DEPLOYMENT.equals(deploymentType)) {
            return buildValidationResult(errors, description);
        }
        String adminUrl = additionalProperties.get(KongConstants.KONG_ADMIN_URL);
        String controlPlaneId = additionalProperties.get(KongConstants.KONG_CONTROL_PLANE_ID);
        String authToken = additionalProperties.get(KongConstants.KONG_AUTH_TOKEN);
        if (StringUtils.isAnyBlank(adminUrl, controlPlaneId, authToken)) {
            log.warn("Kong gateway validation failed due to incomplete required connection properties.");
            description = INCOMPLETE_KONG_CONFIGURATION;
            return buildValidationResult(errors, description);
        }
        try (CloseableHttpClient httpClient = HttpClients.custom().build()) {
            RequestInterceptor auth = template ->
                    template.header(KongConstants.AUTHORIZATION_HEADER, KongConstants.BEARER_PREFIX + authToken);
            KongKonnectApi apiGatewayClient = Feign.builder()
                    .client(new ApacheFeignHttpClient(httpClient))
                    .encoder(new GsonEncoder())
                    .decoder(new GsonDecoder())
                    .errorDecoder(new KongErrorDecoder())
                    .logger(new Slf4jLogger(KongKonnectApi.class))
                    .requestInterceptor(auth)
                    .target(KongKonnectApi.class, adminUrl);
            apiGatewayClient.listServices(controlPlaneId, 1);
            description = validatePlanMappings(environment, apiGatewayClient, controlPlaneId, errors);
        } catch (KongGatewayException e) {
            log.error("Kong gateway validation failed while contacting Kong.", e);
            description = INVALID_KONG_CONFIGURATION;
        } catch (FeignException e) {
            log.error("Kong gateway validation failed with an HTTP client error.", e);
            description = INVALID_KONG_CONFIGURATION;
        } catch (Exception e) {
            log.error("Kong gateway validation failed with an unexpected error.", e);
            description = INVALID_KONG_CONFIGURATION;
        }
        return buildValidationResult(errors, description);
    }

    @Override
    public String getType() {
        return KongConstants.KONG_TYPE;
    }

    @Override
    public GatewayPortalConfiguration getGatewayFeatureCatalog() throws APIManagementException {
        try (InputStream inputStream = KongGatewayConfiguration.class.getClassLoader()
                .getResourceAsStream("GatewayFeatureCatalog.json")) {

            if (inputStream == null) {
                throw new APIManagementException("Gateway Feature Catalog JSON not found");
            }

            // Initialize Gson
            Gson gson = new Gson();

            InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
            JsonObject jsonObject = JsonParser.parseReader(reader).getAsJsonObject();

            JsonObject gatewayObject = jsonObject.getAsJsonObject(KongConstants.KONG_TYPE);

            List<String> apiTypes = gson.fromJson(gatewayObject.get("apiTypes"),
                    new TypeToken<List<String>>() { }.getType());
            JsonObject gatewayFeatures = gatewayObject.get("gatewayFeatures").getAsJsonObject();

            GatewayPortalConfiguration config = new GatewayPortalConfiguration();
            config.setGatewayType(KongConstants.KONG_TYPE);
            config.setSupportedAPITypes(apiTypes);
            config.setSupportedFeatures(gatewayFeatures);

            return config;
        } catch (Exception e) {
            throw new APIManagementException("Error occurred while reading Gateway Feature Catalog JSON", e);
        }
    }

    @Override
    public String getDefaultHostnameTemplate() {
        return "";
    }

    private String validatePlanMappings(Environment environment, KongKonnectApi apiGatewayClient, String controlPlaneId,
            Map<String, String> errors) {
        if (environment.getAdditionalProperties() == null) {
            return null;
        }
        try {
            resolveConsumerGroupMappings(environment, apiGatewayClient, controlPlaneId, errors);
        } catch (Exception e) {
            log.error("Kong gateway plan mapping validation failed while listing consumer groups.", e);
            return INVALID_KONG_PLAN_MAPPING_CONFIGURATION;
        }
        if (!errors.isEmpty()) {
            return INVALID_KONG_PLAN_MAPPING_CONFIGURATION;
        }
        return null;
    }

    private void resolveConsumerGroupMappings(Environment environment, KongKonnectApi apiGatewayClient,
            String controlPlaneId, Map<String, String> errors)
            throws KongGatewayException {

        PagedResponse<KongConsumerGroup> response;
        response = apiGatewayClient.listConsumerGroups(controlPlaneId,
                KongConstants.DEFAULT_CONSUMER_GROUP_LIST_LIMIT);
        if (response == null || response.getData() == null) {
            log.warn("Kong gateway plan mapping validation failed due to empty consumer group response.");
            throw new KongGatewayException(INVALID_KONG_PLAN_MAPPING_CONFIGURATION, null);
        }
        for (Map.Entry<String, String> property : environment.getAdditionalProperties().entrySet()) {
            if (!StringUtils.startsWith(property.getKey(), PLAN_MAPPING_PROPERTY_PREFIX)) {
                continue;
            }
            String consumerGroupName = getPlanMappingName(property.getValue());
            if (consumerGroupName == null) {
                if (StringUtils.isNotBlank(property.getValue())) {
                    errors.put(property.getKey(), "Invalid consumer group name");
                }
                continue;
            }
            List<KongConsumerGroup> matchedConsumerGroups = findConsumerGroupsByName(response.getData(),
                    consumerGroupName);
            if (matchedConsumerGroups.isEmpty()) {
                log.warn("Kong gateway plan mapping validation failed. Invalid consumer group name: "
                        + consumerGroupName);
                errors.put(property.getKey(), "Invalid consumer group name");
                continue;
            }
            if (matchedConsumerGroups.size() > 1) {
                log.warn("Kong gateway plan mapping validation failed. Duplicate consumer group name: "
                        + consumerGroupName);
                errors.put(property.getKey(), "Multiple Kong consumer groups found with the same name");
            }
        }
    }

    private String getPlanMappingName(String planMappingValue) {
        return StringUtils.trimToNull(planMappingValue);
    }

    private GatewayEnvironmentValidationResult buildValidationResult(Map<String, String> errors, String description) {
        GatewayEnvironmentValidationResult validationResult = new GatewayEnvironmentValidationResult();
        validationResult.setValid(StringUtils.isBlank(description));
        validationResult.setDescription(description);
        validationResult.setErrors(errors);
        return validationResult;
    }

    private List<KongConsumerGroup> findConsumerGroupsByName(List<KongConsumerGroup> groups, String consumerGroupName) {
        List<KongConsumerGroup> matchedConsumerGroups = new ArrayList<>();
        for (KongConsumerGroup group : groups) {
            if (group != null && StringUtils.equals(group.getName(), consumerGroupName)
                    && StringUtils.isNotBlank(group.getId())) {
                matchedConsumerGroups.add(group);
            }
        }
        return matchedConsumerGroups;
    }

    public List<String> getSupportedModes() {
        return Arrays.asList(GatewayMode.READ_ONLY.getMode());
    }

    private ConfigurationDto buildPlanMappingConfiguration() {
        ConfigurationDto configuration = new ConfigurationDto(PLAN_MAPPING_CONFIG_NAME,
                "Kong Consumer Group Assignment", PLAN_MAPPING_CONFIG_TYPE,
                "For each WSO2 subscription policy, enter the Kong consumer group name to apply when generating "
                        + "third-party API keys.", CONSUMER_GROUP_NAME_LABEL, false, false,
                Collections.emptyList(), false);
        configuration.setValues(Collections.emptyList());
        return configuration;
    }
}
