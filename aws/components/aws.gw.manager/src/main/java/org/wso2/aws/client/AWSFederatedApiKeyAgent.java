/*
 * Copyright (c) 2026 WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
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

import com.google.gson.JsonObject;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.aws.client.util.GatewayUtil;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.FederatedApiKeyAgent;
import org.wso2.carbon.apimgt.api.model.Environment;
import org.wso2.carbon.apimgt.api.model.FederatedApiKeyContext;
import org.wso2.carbon.apimgt.api.model.GatewayPortalConfiguration;
import org.wso2.carbon.apimgt.api.model.RemotePlan;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.CreateApiKeyRequest;
import software.amazon.awssdk.services.apigateway.model.CreateApiKeyResponse;
import software.amazon.awssdk.services.apigateway.model.CreateUsagePlanKeyRequest;
import software.amazon.awssdk.services.apigateway.model.DeleteApiKeyRequest;
import software.amazon.awssdk.services.apigateway.model.DeleteUsagePlanKeyRequest;
import software.amazon.awssdk.services.apigateway.model.GetUsagePlansRequest;
import software.amazon.awssdk.services.apigateway.model.GetUsagePlansResponse;
import software.amazon.awssdk.services.apigateway.model.UsagePlan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AWS implementation of federated API key management.
 */
public class AWSFederatedApiKeyAgent implements FederatedApiKeyAgent {

    private static final Log log = LogFactory.getLog(AWSFederatedApiKeyAgent.class);
    private static final int MAX_TAG_LENGTH = 256;

    private static final String TAG_API_ID = "wso2:api-id";
    private static final String TAG_API_UUID = "wso2:api-uuid";
    private static final String TAG_KEY_UUID = "wso2:key-uuid";
    private static final String TAG_AUTHZ_USER = "wso2:authz-user";
    private static final String TAG_ORGANIZATION = "wso2:organization";

    private ApiGatewayClient apiGatewayClient;

    @Override
    public String getGatewayType() {
        return AWSConstants.AWS_TYPE;
    }

    @Override
    public boolean isApiKeySupport() {
        try {
            GatewayPortalConfiguration featureCatalog = new AWSGatewayConfiguration().getGatewayFeatureCatalog();
            Object supportedFeatures = featureCatalog.getSupportedFeatures();
            if (supportedFeatures instanceof JsonObject) {
                JsonObject featuresJson = (JsonObject) supportedFeatures;
                if (featuresJson.has("apiKeys") && featuresJson.get("apiKeys").isJsonObject()) {
                    JsonObject apiKeys = featuresJson.getAsJsonObject("apiKeys");
                    return apiKeys.has("supported") && apiKeys.get("supported").getAsBoolean();
                }
            }
        } catch (APIManagementException e) {
            log.warn("Error while resolving AWS API key support from GatewayFeatureCatalog", e);
        }
        return false;
    }

    @Override
    public void init(Environment environment, String organization) throws APIManagementException {
        try {
            String region = environment.getAdditionalProperties().get(AWSConstants.AWS_ENVIRONMENT_REGION);
            String accessKey = environment.getAdditionalProperties().get(AWSConstants.AWS_ENVIRONMENT_ACCESS_KEY);
            String secretKey = environment.getAdditionalProperties().get(AWSConstants.AWS_ENVIRONMENT_SECRET_KEY);

            SdkHttpClient httpClient = ApacheHttpClient.builder().build();
            this.apiGatewayClient = ApiGatewayClient.builder()
                    .region(Region.of(region))
                    .httpClient(httpClient)
                    .credentialsProvider(StaticCredentialsProvider
                            .create(AwsBasicCredentials.create(accessKey, secretKey)))
                    .build();
        } catch (Exception e) {
            throw new APIManagementException("Error occurred while initializing AWS Federated API Key Agent", e);
        }
    }

    @Override
    public String createApiKey(FederatedApiKeyContext context) throws APIManagementException {
        if (StringUtils.isBlank(context.getApiKeyValue())) {
            throw new APIManagementException("API key value is required to create AWS API key");
        }
        try {
            String awsApiId = GatewayUtil.getAWSApiIdFromReferenceArtifact(context.getApiReferenceArtifact());
            CreateApiKeyRequest request = CreateApiKeyRequest.builder()
                    .name(context.getApiKeyName())
                    .value(context.getApiKeyValue())
                    .enabled(true)
                    .description("WSO2 API Key UUID: " + context.getApiKeyUuid())
                    .tags(buildTags(context, awsApiId))
                    .build();
            CreateApiKeyResponse response = apiGatewayClient.createApiKey(request);
            return response.id();
        } catch (Exception e) {
            throw new APIManagementException("Error creating API key in AWS", e);
        }
    }

    @Override
    public void revokeApiKey(FederatedApiKeyContext context) throws APIManagementException {
        if (StringUtils.isBlank(context.getRemoteApiKeyId())) {
            return;
        }
        try {
            DeleteApiKeyRequest request = DeleteApiKeyRequest.builder()
                    .apiKey(context.getRemoteApiKeyId())
                    .build();
            apiGatewayClient.deleteApiKey(request);
        } catch (Exception e) {
            throw new APIManagementException("Error revoking API key in AWS", e);
        }
    }

    @Override
    public void associateApiKeyWithUsagePlan(FederatedApiKeyContext context, String remoteUsagePlanId)
            throws APIManagementException {
        if (StringUtils.isBlank(context.getRemoteApiKeyId())) {
            throw new APIManagementException("Remote API key ID is required for usage plan association");
        }
        if (StringUtils.isBlank(remoteUsagePlanId)) {
            throw new APIManagementException("Remote usage plan ID is required for association");
        }
        try {
            removeApiKeyAssociations(context);
            CreateUsagePlanKeyRequest request = CreateUsagePlanKeyRequest.builder()
                    .usagePlanId(remoteUsagePlanId)
                    .keyId(context.getRemoteApiKeyId())
                    .keyType("API_KEY")
                    .build();
            apiGatewayClient.createUsagePlanKey(request);
        } catch (Exception e) {
            throw new APIManagementException("Error associating API key with usage plan in AWS", e);
        }
    }

    @Override
    public void removeApiKeyAssociations(FederatedApiKeyContext context) throws APIManagementException {
        if (StringUtils.isBlank(context.getRemoteApiKeyId())) {
            return;
        }
        try {
            GetUsagePlansRequest usagePlansRequest = GetUsagePlansRequest.builder()
                    .keyId(context.getRemoteApiKeyId())
                    .build();
            for (GetUsagePlansResponse usagePlansResponse : apiGatewayClient.getUsagePlansPaginator(usagePlansRequest)) {
                if (usagePlansResponse.items() == null || usagePlansResponse.items().isEmpty()) {
                    continue;
                }
                for (UsagePlan usagePlan : usagePlansResponse.items()) {
                    DeleteUsagePlanKeyRequest deleteRequest = DeleteUsagePlanKeyRequest.builder()
                            .usagePlanId(usagePlan.id())
                            .keyId(context.getRemoteApiKeyId())
                            .build();
                    apiGatewayClient.deleteUsagePlanKey(deleteRequest);
                }
            }
        } catch (Exception e) {
            throw new APIManagementException("Error removing API key usage plan associations in AWS", e);
        }
    }

    @Override
    public List<RemotePlan> listRemotePlans(Environment environment) throws APIManagementException {
        List<RemotePlan> remotePlans = new ArrayList<>();
        try {
            String position = null;
            do {
                GetUsagePlansRequest request = GetUsagePlansRequest.builder()
                        .limit(500)
                        .position(position)
                        .build();
                GetUsagePlansResponse response = apiGatewayClient.getUsagePlans(request);
                for (UsagePlan plan : response.items()) {
                    Map<String, String> limits = new HashMap<>();
                    if (plan.throttle() != null) {
                        if (plan.throttle().rateLimit() != null) {
                            limits.put("rateLimit", String.valueOf(plan.throttle().rateLimit()));
                        }
                        if (plan.throttle().burstLimit() != null) {
                            limits.put("burstLimit", String.valueOf(plan.throttle().burstLimit()));
                        }
                    }
                    if (plan.quota() != null) {
                        if (plan.quota().limit() != null) {
                            limits.put("quotaLimit", String.valueOf(plan.quota().limit()));
                        }
                        if (plan.quota().period() != null) {
                            limits.put("quotaPeriod", plan.quota().period().toString());
                        }
                    }
                    remotePlans.add(new RemotePlan(plan.id(), plan.name(),
                            plan.description() != null ? plan.description() : "", limits));
                }
                position = response.position();
            } while (position != null);
        } catch (Exception e) {
            throw new APIManagementException("Failed to list AWS Usage Plans: " + e.getMessage(), e);
        }
        return remotePlans;
    }

    private Map<String, String> buildTags(FederatedApiKeyContext context, String awsApiId) {
        Map<String, String> tags = new HashMap<>();
        putTag(tags, TAG_API_ID, awsApiId);
        putTag(tags, TAG_API_UUID, context.getApiUuid());
        putTag(tags, TAG_KEY_UUID, context.getApiKeyUuid());
        putTag(tags, TAG_AUTHZ_USER, context.getAuthzUser());
        putTag(tags, TAG_ORGANIZATION, context.getOrganizationId());
        return tags;
    }

    private void putTag(Map<String, String> tags, String key, String value) {
        if (StringUtils.isBlank(value)) {
            return;
        }
        String trimmed = value.trim();
        if (trimmed.length() > MAX_TAG_LENGTH) {
            trimmed = trimmed.substring(0, MAX_TAG_LENGTH);
        }
        tags.put(key, trimmed);
    }
}
