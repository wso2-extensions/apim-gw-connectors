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
import org.wso2.carbon.apimgt.api.model.GatewayConfigurationContext;
import org.wso2.carbon.apimgt.api.model.GatewayMode;
import org.wso2.carbon.apimgt.api.model.GatewayPortalConfiguration;
import org.wso2.carbon.apimgt.api.model.policy.PolicyConstants;
import org.wso2.carbon.apimgt.api.model.policy.SubscriptionPolicy;
import org.wso2.carbon.apimgt.impl.kmclient.ApacheFeignHttpClient;
import org.wso2.kong.client.model.KongConsumerGroup;
import org.wso2.kong.client.model.PagedResponse;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;


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
            "The gateway plan mappings you added are invalid. Verify the Kong consumer group IDs.";
    private static final String PLAN_MAPPING_CONFIG_NAME = "plan_mapping";
    private static final String MAPPING_TYPE = "mapping";
    private static final String LEFT_LABEL_KEY = "left";
    private static final String RIGHT_LABEL_KEY = "right";
    private static final Set<String> NON_MAPPABLE_POLICIES = new HashSet<>(
            Arrays.asList("Unauthenticated", "DefaultSubscriptionless", "AsyncDefaultSubscriptionless"));

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

        return Collections.singletonList(
                new ConfigurationDto(KongConstants.KONG_DEPLOYMENT_TYPE, "Deployment Type", "options",
                        "Select the Kong Gateway deployment type", KongConstants.KONG_STANDALONE_DEPLOYMENT, true,
                        false, deploymentValues, false));
    }

    public List<ConfigurationDto> getConnectionConfigurations(GatewayConfigurationContext context) {
        List<ConfigurationDto> configurationDtos = new ArrayList<>(getConnectionConfigurations());
        configurationDtos.add(buildPlanMappingConfiguration(context));
        return configurationDtos;
    }

    public void validateEnvironment(Environment environment) throws APIManagementException {
        Map<String, String> additionalProperties = environment.getAdditionalProperties();
        if (additionalProperties == null) {
            log.warn("Kong gateway validation failed due to missing additional properties.");
            throw new APIManagementException(INCOMPLETE_KONG_CONFIGURATION);
        }
        String deploymentType = additionalProperties.get(KongConstants.KONG_DEPLOYMENT_TYPE);
        if (KongConstants.KONG_KUBERNETES_DEPLOYMENT.equals(deploymentType)) {
            return;
        }
        String adminUrl = additionalProperties.get(KongConstants.KONG_ADMIN_URL);
        String controlPlaneId = additionalProperties.get(KongConstants.KONG_CONTROL_PLANE_ID);
        String authToken = additionalProperties.get(KongConstants.KONG_AUTH_TOKEN);
        if (StringUtils.isAnyBlank(adminUrl, controlPlaneId, authToken)) {
            log.warn("Kong gateway validation failed due to incomplete required connection properties.");
            throw new APIManagementException(INCOMPLETE_KONG_CONFIGURATION);
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
            validatePlanMappings(environment, apiGatewayClient, controlPlaneId);
        } catch (APIManagementException e) {
            log.error("Kong gateway validation failed with a domain validation error: " + e.getMessage(), e);
            throw e;
        } catch (KongGatewayException e) {
            log.error("Kong gateway validation failed while contacting Kong.", e);
            throw new APIManagementException(INVALID_KONG_CONFIGURATION, e);
        } catch (FeignException e) {
            log.error("Kong gateway validation failed with an HTTP client error.", e);
            throw new APIManagementException(INVALID_KONG_CONFIGURATION, e);
        } catch (Exception e) {
            log.error("Kong gateway validation failed with an unexpected error.", e);
            throw new APIManagementException(INVALID_KONG_CONFIGURATION, e);
        }
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

    private void validatePlanMappings(Environment environment, KongKonnectApi apiGatewayClient, String controlPlaneId)
            throws APIManagementException {
        if (environment.getAdditionalProperties() == null) {
            return;
        }
        PagedResponse<KongConsumerGroup> response;
        try {
            response = apiGatewayClient.listConsumerGroups(controlPlaneId,
                    KongConstants.DEFAULT_CONSUMER_GROUP_LIST_LIMIT);
        } catch (Exception e) {
            log.error("Kong gateway plan mapping validation failed while listing consumer groups.", e);
            throw new APIManagementException(INVALID_KONG_PLAN_MAPPING_CONFIGURATION, e);
        }
        if (response == null || response.getData() == null) {
            log.warn("Kong gateway plan mapping validation failed due to empty consumer group response.");
            throw new APIManagementException(INVALID_KONG_PLAN_MAPPING_CONFIGURATION);
        }
        for (Map.Entry<String, String> property : environment.getAdditionalProperties().entrySet()) {
            if (!StringUtils.startsWith(property.getKey(), "plan_mapping.")) {
                continue;
            }
            String consumerGroupId = StringUtils.trimToNull(property.getValue());
            if (consumerGroupId == null) {
                continue;
            }
            if (!consumerGroupExists(response.getData(), consumerGroupId)) {
                log.warn("Kong gateway plan mapping validation failed. Invalid consumer group ID: " + consumerGroupId);
                throw new APIManagementException(INVALID_KONG_PLAN_MAPPING_CONFIGURATION);
            }
        }
    }

    private boolean consumerGroupExists(List<KongConsumerGroup> groups, String consumerGroupId) {
        for (KongConsumerGroup group : groups) {
            if (group != null && StringUtils.equals(group.getId(), consumerGroupId)) {
                return true;
            }
        }
        return false;
    }

    public List<String> getSupportedModes() {
        return Arrays.asList(GatewayMode.READ_ONLY.getMode());
    }

    private ConfigurationDto buildPlanMappingConfiguration(GatewayConfigurationContext context) {
        ConfigurationDto configuration = new ConfigurationDto(PLAN_MAPPING_CONFIG_NAME, "Plan Mapping", MAPPING_TYPE,
                "Map local WSO2 plans to Kong consumer groups.", "", false, false, Collections.emptyList(), false);
        Map<String, String> labels = new HashMap<>();
        labels.put(LEFT_LABEL_KEY, "WSO2 Plan");
        labels.put(RIGHT_LABEL_KEY, "Consumer Group ID");
        configuration.setLabels(labels);
        configuration.setValues(buildPlanMappingValues(context));
        return configuration;
    }

    private List<Object> buildPlanMappingValues(GatewayConfigurationContext context) {
        List<Object> values = new ArrayList<>();
        if (context == null || context.getSubscriptionPolicies() == null) {
            return values;
        }
        Set<String> supportedApiTypes = resolveSupportedApiTypes();
        for (SubscriptionPolicy policy : context.getSubscriptionPolicies()) {
            if (policy == null || policy.getUUID() == null || policy.getPolicyName() == null) {
                continue;
            }
            if (NON_MAPPABLE_POLICIES.contains(policy.getPolicyName())) {
                continue;
            }
            String apiType = resolvePolicyApiType(policy);
            if (apiType == null || !supportedApiTypes.contains(apiType)) {
                continue;
            }
            Map<String, String> value = new LinkedHashMap<>();
            value.put("id", policy.getUUID());
            value.put("label", policy.getDisplayName() != null ? policy.getDisplayName() : policy.getPolicyName());
            value.put("apiType", apiType);
            values.add(value);
        }
        return values;
    }

    private Set<String> resolveSupportedApiTypes() {
        try {
            GatewayPortalConfiguration configuration = getGatewayFeatureCatalog();
            if (configuration != null && configuration.getSupportedAPITypes() != null) {
                return new HashSet<>(configuration.getSupportedAPITypes());
            }
        } catch (APIManagementException e) {
            return Collections.emptySet();
        }
        return Collections.emptySet();
    }

    private String resolvePolicyApiType(SubscriptionPolicy policy) {
        if (policy.getDefaultQuotaPolicy() == null || policy.getDefaultQuotaPolicy().getType() == null) {
            return null;
        }
        String type = policy.getDefaultQuotaPolicy().getType();
        if (PolicyConstants.REQUEST_COUNT_TYPE.equalsIgnoreCase(type)) {
            return "rest";
        }
        if (PolicyConstants.EVENT_COUNT_TYPE.equalsIgnoreCase(type)) {
            return "async";
        }
        if (PolicyConstants.AI_API_QUOTA_TYPE.equalsIgnoreCase(type)) {
            return "ai-api";
        }
        return null;
    }
}
