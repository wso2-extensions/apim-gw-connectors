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

package org.wso2.aws.client;

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.osgi.service.component.annotations.Component;
import org.wso2.aws.client.util.AWSAPIUtil;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.model.ConfigurationDto;
import org.wso2.carbon.apimgt.api.model.Environment;
import org.wso2.carbon.apimgt.api.model.GatewayAgentConfiguration;
import org.wso2.carbon.apimgt.api.model.GatewayConfigurationContext;
import org.wso2.carbon.apimgt.api.model.GatewayPortalConfiguration;
import org.wso2.carbon.apimgt.api.model.policy.PolicyConstants;
import org.wso2.carbon.apimgt.api.model.policy.SubscriptionPolicy;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.GetRestApisRequest;
import software.amazon.awssdk.services.apigateway.model.GetUsagePlanRequest;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;


/**
 * This class contains the configurations related to AWS Gateway
 */
@Component(
        name = "aws.external.gateway.configuration.component",
        immediate = true,
        service = GatewayAgentConfiguration.class
)
public class AWSGatewayConfiguration implements GatewayAgentConfiguration {
    private static final Log log = LogFactory.getLog(AWSAPIUtil.class);
    private static final String INCOMPLETE_AWS_CONFIGURATION =
            "The gateway configuration you added is incomplete. Provide the required AWS gateway details.";
    private static final String INVALID_AWS_CONFIGURATION =
            "The AWS gateway configuration you added is invalid. Verify the region, access key, and secret key.";
    private static final String INVALID_AWS_PLAN_MAPPING_CONFIGURATION =
            "The gateway plan mappings you added are invalid. Verify the AWS usage plan IDs.";
    private static final String PLAN_MAPPING_CONFIG_NAME = "plan_mapping";
    private static final String MAPPING_TYPE = "mapping";
    private static final String LEFT_LABEL_KEY = "left";
    private static final String RIGHT_LABEL_KEY = "right";
    private static final Set<String> NON_MAPPABLE_POLICIES = new HashSet<>(
            java.util.Arrays.asList("Unauthenticated", "DefaultSubscriptionless", "AsyncDefaultSubscriptionless"));

    @Override
    public String getGatewayDeployerImplementation() {
        return AWSGatewayDeployer.class.getName();
    }

    @Override
    public String getImplementation() {
        // Deprecated method, kept for backward compatibility
        return getGatewayDeployerImplementation();
    }

    public String getDiscoveryImplementation() {
        return AWSFederatedAPIDiscovery.class.getName();
    }

    @Override
    public String getApiKeyConnectorImplementation() {
        return AWSFederatedApiKeyConnector.class.getName();
    }

    @Override
    public List<ConfigurationDto> getConnectionConfigurations() {
        List<ConfigurationDto> configurationDtoList = new ArrayList<>();
        configurationDtoList
                .add(new ConfigurationDto("region", "AWS Region", "input", "AWS Region", "", true, false, Collections.emptyList(), false));
        configurationDtoList
                .add(new ConfigurationDto("access_key", "Access Key", "input", "AWS Access Key for Signature Authentication", "", true,
                        true, Collections.emptyList(), false));
        configurationDtoList
                .add(new ConfigurationDto("secret_key", "Secret Key", "input", "AWS Secret Key for Signature Authentication", "",
                        true, true, Collections.emptyList(), false));
        configurationDtoList.add(new ConfigurationDto("stage", "Stage Name", "input", "Default stage name", "", true,
                false,
                Collections.emptyList(), false));

        return configurationDtoList;
    }

    public List<ConfigurationDto> getConnectionConfigurations(GatewayConfigurationContext context) {
        List<ConfigurationDto> configurationDtoList = new ArrayList<>(getConnectionConfigurations());
        configurationDtoList.add(buildPlanMappingConfiguration(context));
        return configurationDtoList;
    }

    public void validateEnvironment(Environment environment) throws APIManagementException {
        Map<String, String> additionalProperties = environment.getAdditionalProperties();
        if (additionalProperties == null) {
            log.warn("AWS gateway validation failed due to missing additional properties.");
            throw new APIManagementException(INCOMPLETE_AWS_CONFIGURATION);
        }
        String region = additionalProperties.get(AWSConstants.AWS_ENVIRONMENT_REGION);
        String accessKey = additionalProperties.get(AWSConstants.AWS_ENVIRONMENT_ACCESS_KEY);
        String secretKey = additionalProperties.get(AWSConstants.AWS_ENVIRONMENT_SECRET_KEY);
        if (StringUtils.isAnyBlank(region, accessKey, secretKey)) {
            log.warn("AWS gateway validation failed due to incomplete required connection properties.");
            throw new APIManagementException(INCOMPLETE_AWS_CONFIGURATION);
        }
        try {
            try (SdkHttpClient httpClient = ApacheHttpClient.builder().build();
                    ApiGatewayClient client = ApiGatewayClient.builder()
                    .region(Region.of(region))
                    .httpClient(httpClient)
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(accessKey, secretKey)))
                    .build()) {
                client.getRestApis(GetRestApisRequest.builder().limit(1).build());
                validatePlanMappings(environment, client);
            }
        } catch (SdkException e) {
            log.error("AWS gateway validation failed while contacting AWS API Gateway.", e);
            throw new APIManagementException(INVALID_AWS_CONFIGURATION, e);
        }
    }

    @Override
    public String getType() {
        return AWSConstants.AWS_TYPE;
    }

    @Override
    public GatewayPortalConfiguration getGatewayFeatureCatalog() throws APIManagementException {
        try (InputStream inputStream = AWSGatewayConfiguration.class.getClassLoader()
                .getResourceAsStream("GatewayFeatureCatalog.json")) {

            if (inputStream == null) {
                throw new APIManagementException("Gateway Feature Catalog JSON not found");
            }

            // Initialize Gson
            Gson gson = new Gson();

            InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
            JsonObject jsonObject = JsonParser.parseReader(reader).getAsJsonObject();

            JsonObject gatewayObject = jsonObject.getAsJsonObject(AWSConstants.AWS_TYPE);

            List<String> apiTypes = gson.fromJson(gatewayObject.get("apiTypes"),
                    new TypeToken<List<String>>() {}.getType());
            JsonObject gatewayFeatures = gatewayObject.get("gatewayFeatures").getAsJsonObject();

            GatewayPortalConfiguration config = new GatewayPortalConfiguration();
            config.setGatewayType(AWSConstants.AWS_TYPE);
            config.setSupportedAPITypes(apiTypes);
            config.setSupportedFeatures(gatewayFeatures);

            return config;
        } catch (Exception e) {
            throw new APIManagementException("Error occurred while reading Gateway Feature Catalog JSON", e);
        }
    }

    @Override
    public String getDefaultHostnameTemplate() {

        return AWSConstants.AWS_API_EXECUTION_URL_TEMPLATE;
    }

    private void validatePlanMappings(Environment environment, ApiGatewayClient client) throws APIManagementException {
        if (environment.getAdditionalProperties() == null) {
            return;
        }
        for (Map.Entry<String, String> property : environment.getAdditionalProperties().entrySet()) {
            if (!StringUtils.startsWith(property.getKey(), "plan_mapping.")) {
                continue;
            }
            String usagePlanId = StringUtils.trimToNull(property.getValue());
            if (usagePlanId == null) {
                continue;
            }
            try {
                client.getUsagePlan(GetUsagePlanRequest.builder().usagePlanId(usagePlanId).build());
            } catch (SdkException e) {
                log.error("AWS plan mapping validation failed for usage plan ID: " + usagePlanId, e);
                throw new APIManagementException(INVALID_AWS_PLAN_MAPPING_CONFIGURATION, e);
            }
        }
    }

    private ConfigurationDto buildPlanMappingConfiguration(GatewayConfigurationContext context) {
        ConfigurationDto configuration = new ConfigurationDto(PLAN_MAPPING_CONFIG_NAME, "Plan Mapping", MAPPING_TYPE,
                "Map local WSO2 plans to AWS usage plans.", "", false, false, Collections.emptyList(), false);
        Map<String, String> labels = new HashMap<>();
        labels.put(LEFT_LABEL_KEY, "WSO2 Plan");
        labels.put(RIGHT_LABEL_KEY, "Usage Plan ID");
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
            log.warn("Failed to resolve AWS supported API types for plan mapping configuration", e);
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
