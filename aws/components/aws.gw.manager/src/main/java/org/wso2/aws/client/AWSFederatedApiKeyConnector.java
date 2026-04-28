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
import com.google.gson.JsonParser;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.aws.client.util.GatewayUtil;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.FederatedApiKeyConnector;
import org.wso2.carbon.apimgt.api.model.FederatedApiKeyCreationResult;
import org.wso2.carbon.apimgt.api.model.Environment;
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
import software.amazon.awssdk.services.apigateway.model.ConflictException;
import software.amazon.awssdk.services.apigateway.model.GetApiKeyRequest;
import software.amazon.awssdk.services.apigateway.model.GetApiKeyResponse;
import software.amazon.awssdk.services.apigateway.model.NotFoundException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AWS implementation of federated API key management.
 */
public class AWSFederatedApiKeyConnector implements FederatedApiKeyConnector {

    private static final Log log = LogFactory.getLog(AWSFederatedApiKeyConnector.class);
    private static final int MAX_TAG_LENGTH = 256;
    private static final String API_KEY_ID = "apiKeyId";
    private static final String TAG_API_ID = "wso2:api-id";
    private static final String TAG_API_UUID = "wso2:api-uuid";
    private static final String TAG_KEY_UUID = "wso2:key-uuid";
    private static final String TAG_AUTHZ_USER = "wso2:authz-user";
    private static final String TAG_ORGANIZATION = "wso2:organization";
    private static final String TAG_VALIDITY_PERIOD = "wso2:key-validity-period";
    private static final String TAG_PERMITTED_IP = "wso2:key-permitted-ip";
    private static final String TAG_PERMITTED_REFERER = "wso2:key-permitted-referer";
    private static final String USAGE_PLAN_KEY_TYPE_API_KEY = "API_KEY";
    private static final String PLAN_MAPPING_PROPERTY_PREFIX = "plan_mapping.";

    public static final String PROP_API_KEY_NAME = "apiKeyName";
    public static final String PROP_API_UUID = "apiUuid";
    public static final String PROP_AUTHZ_USER = "authzUser";
    public static final String PROP_ORGANIZATION_ID = "organizationId";
    public static final String PROP_VALIDITY_PERIOD = "validityPeriod";
    public static final String PROP_PERMITTED_IP = "permittedIP";
    public static final String PROP_PERMITTED_REFERER = "permittedReferer";

    private ApiGatewayClient apiGatewayClient;
    private String environmentId;
    private final List<LocalPolicyRemoteMapping> planMappings = new ArrayList<>();

    /**
     * Returns the gateway type handled by this connector.
     */
    @Override
    public String getGatewayType() {
        return AWSConstants.AWS_TYPE;
    }

    /**
     * Indicates that AWS federated API-key provisioning is supported.
     */
    @Override
    public boolean isApiKeySupport() {
        return true;
    }

    /**
     * Initializes the AWS API Gateway client from the environment credentials and region.
     */
    @Override
    public void init(Environment environment) throws APIManagementException {
        try {
            String region = environment.getAdditionalProperties().get(AWSConstants.AWS_ENVIRONMENT_REGION);
            String accessKey = environment.getAdditionalProperties().get(AWSConstants.AWS_ENVIRONMENT_ACCESS_KEY);
            String secretKey = environment.getAdditionalProperties().get(AWSConstants.AWS_ENVIRONMENT_SECRET_KEY);
            this.environmentId = environment.getUuid();
            loadPlanMappings(environment);

            SdkHttpClient httpClient = ApacheHttpClient.builder().build();
            this.apiGatewayClient = ApiGatewayClient.builder()
                    .region(Region.of(region))
                    .httpClient(httpClient)
                    .credentialsProvider(StaticCredentialsProvider
                            .create(AwsBasicCredentials.create(accessKey, secretKey)))
                    .build();
        } catch (Exception e) {
            throw new APIManagementException("Error occurred while initializing AWS Federated API Key Connector", e);
        }
    }

    /**
     * Creates an AWS API key using the caller-provided local API-key value and returns the AWS API key ID.
     */
    @Override
    public FederatedApiKeyCreationResult createApiKey(String apiKeyUuid, String apiKeyValue, String apiReferenceArtifact,
                                                      String localPolicyId, Map<String, String> properties)
            throws APIManagementException {
        if (StringUtils.isBlank(apiKeyValue)) {
            throw new APIManagementException("API key value is required to create AWS API key");
        }
        try {
            String awsApiId = GatewayUtil.getAWSApiIdFromReferenceArtifact(apiReferenceArtifact);
            CreateApiKeyRequest request = buildCreateApiKeyRequest(apiKeyUuid, apiKeyValue, awsApiId, null, properties);
            CreateApiKeyResponse response = apiGatewayClient.createApiKey(request);
            
            return FederatedApiKeyCreationResult.builder()
                    .referenceArtifact(buildApiKeyReferenceArtifact(response.id()))
                    .build();
        } catch (Exception e) {
            throw new APIManagementException("Error creating API key in AWS", e);
        }
    }

    /**
     * Replaces an AWS API key by creating a new key, migrating the mapped usage-plan association, and deleting the old
     * key. AWS API Gateway does not support patching an API key's value in place.
     */
    @Override
    public FederatedApiKeyCreationResult replaceApiKey(String apiKeyReferenceArtifact, String newApiKeyValue,
                                                       String apiReferenceArtifact, String localPolicyId,
                                                       Map<String, String> properties) throws APIManagementException {
        if (StringUtils.isBlank(newApiKeyValue)) {
            throw new APIManagementException("API key value is required to replace AWS API key");
        }
        String oldApiKeyId = resolveApiKeyId(apiKeyReferenceArtifact);
        GetApiKeyResponse oldApiKey = getExistingApiKey(oldApiKeyId);
        String awsApiId = GatewayUtil.getAWSApiIdFromReferenceArtifact(apiReferenceArtifact);
        String apiKeyUuid = properties != null ? properties.get(PROP_API_UUID) : null;
        CreateApiKeyRequest request = buildCreateApiKeyRequest(apiKeyUuid, newApiKeyValue, awsApiId, oldApiKey,
                properties);
        CreateApiKeyResponse response;
        try {
            response = apiGatewayClient.createApiKey(request);
        } catch (Exception e) {
            throw new APIManagementException("Error creating replacement API key in AWS", e);
        }
        FederatedApiKeyCreationResult result = FederatedApiKeyCreationResult.builder()
                .referenceArtifact(buildApiKeyReferenceArtifact(response.id()))
                .build();
        if (result == null || StringUtils.isBlank(result.getReferenceArtifact())) {
            throw new APIManagementException("AWS API key replacement did not return a reference artifact");
        }
        String newApiKeyRefArtifact = result.getReferenceArtifact();
        try {
            String remotePolicyReference = resolveRemotePolicyReference(localPolicyId, false);
            if (StringUtils.isNotBlank(remotePolicyReference)) {
                applyResolvedRateLimitPolicy(newApiKeyRefArtifact, remotePolicyReference);
            }
        } catch (APIManagementException e) {
            revokeApiKey(newApiKeyRefArtifact);
            throw e;
        }
        revokeApiKey(apiKeyReferenceArtifact);
        return result;
    }

    private CreateApiKeyRequest buildCreateApiKeyRequest(String apiKeyUuid, String apiKeyValue, String awsApiId,
                                                         GetApiKeyResponse oldApiKey, Map<String, String> properties) {
        Map<String, String> tags = oldApiKey != null && oldApiKey.hasTags()
                ? new HashMap<>(oldApiKey.tags()) : new HashMap<>();
        tags.putAll(buildTags(apiKeyUuid, awsApiId, properties));
        return CreateApiKeyRequest.builder()
                .name(resolveApiKeyName(apiKeyUuid, oldApiKey, properties))
                .value(apiKeyValue)
                .enabled(oldApiKey != null && oldApiKey.enabled() != null ? oldApiKey.enabled() : true)
                .description(resolveApiKeyDescription(apiKeyUuid, oldApiKey))
                .tags(tags)
                .build();
    }

    private GetApiKeyResponse getExistingApiKey(String apiKeyId) throws APIManagementException {
        if (StringUtils.isBlank(apiKeyId)) {
            return null;
        }
        try {
            GetApiKeyRequest request = GetApiKeyRequest.builder()
                    .apiKey(apiKeyId)
                    .includeValue(false)
                    .build();
            return apiGatewayClient.getApiKey(request);
        } catch (NotFoundException e) {
            if (log.isDebugEnabled()) {
                log.debug("Old AWS API key was not found for replacement: " + apiKeyId, e);
            }
            return null;
        } catch (Exception e) {
            throw new APIManagementException("Error retrieving old AWS API key for replacement", e);
        }
    }

    private String resolveApiKeyName(String apiKeyUuid, GetApiKeyResponse oldApiKey, Map<String, String> properties) {
        String apiKeyName = properties != null ? properties.get(PROP_API_KEY_NAME) : null;
        if (StringUtils.isNotBlank(apiKeyName)) {
            return apiKeyName;
        }
        if (oldApiKey != null && StringUtils.isNotBlank(oldApiKey.name())) {
            return oldApiKey.name();
        }
        return StringUtils.isNotBlank(apiKeyUuid) ? "wso2-key-" + apiKeyUuid : "wso2-key";
    }

    private String resolveApiKeyDescription(String apiKeyUuid, GetApiKeyResponse oldApiKey) {
        if (oldApiKey != null && StringUtils.isNotBlank(oldApiKey.description())) {
            return oldApiKey.description();
        }
        return "WSO2 API Key UUID: " + StringUtils.defaultString(apiKeyUuid, "");
    }

    /**
     * Deletes the AWS API key identified by the stored connector-owned reference artifact.
     */
    @Override
    public void revokeApiKey(String apiKeyReferenceArtifact) throws APIManagementException {
        String apiKeyId = resolveApiKeyId(apiKeyReferenceArtifact);
        if (StringUtils.isBlank(apiKeyId)) {
            return;
        }
        try {
            DeleteApiKeyRequest request = DeleteApiKeyRequest.builder()
                    .apiKey(apiKeyId)
                    .build();
            apiGatewayClient.deleteApiKey(request);
        } catch (Exception e) {
            throw new APIManagementException("Error revoking API key in AWS", e);
        }
    }

    /**
     * Associates the AWS API key with the usage plan encoded in the connector-owned remote plan reference.
     */
    @Override
    public void applyRateLimitPolicy(String apiKeyReferenceArtifact, String apiReferenceArtifact, String localPolicyId)
            throws APIManagementException {
        String remotePolicyReference = resolveRemotePolicyReference(localPolicyId, true);
        if (StringUtils.isBlank(remotePolicyReference)) {
            return;
        }
        applyResolvedRateLimitPolicy(apiKeyReferenceArtifact, remotePolicyReference);
    }

    private void applyResolvedRateLimitPolicy(String apiKeyReferenceArtifact, String remotePolicyReference)
            throws APIManagementException {
        String apiKeyId = resolveApiKeyId(apiKeyReferenceArtifact);
        if (StringUtils.isBlank(apiKeyId)) {
            throw new APIManagementException("Remote API key ID is required for rate limit policy association");
        }
        String policyId = resolveRemotePolicyId(remotePolicyReference);
        if (StringUtils.isBlank(policyId)) {
            throw new APIManagementException("Remote policy ID is required for association");
        }
        try {
            CreateUsagePlanKeyRequest request = CreateUsagePlanKeyRequest.builder()
                    .usagePlanId(policyId)
                    .keyId(apiKeyId)
                    .keyType(USAGE_PLAN_KEY_TYPE_API_KEY)
                    .build();
            apiGatewayClient.createUsagePlanKey(request);
        } catch (ConflictException e) {
            if (log.isDebugEnabled()) {
                log.debug("AWS API key is already associated with usage plan: " + policyId, e);
            }
        } catch (Exception e) {
            throw new APIManagementException("Error associating API key with usage plan in AWS", e);
        }
    }

    /**
     * Removes the AWS API key from the usage plan encoded in the connector-owned remote plan reference.
     */
    @Override
    public void removeRateLimitPolicy(String apiKeyReferenceArtifact, String apiReferenceArtifact, String localPolicyId)
            throws APIManagementException {
        String remotePolicyReference = resolveRemotePolicyReference(localPolicyId, true);
        if (StringUtils.isBlank(remotePolicyReference)) {
            return;
        }
        String apiKeyId = resolveApiKeyId(apiKeyReferenceArtifact);
        if (StringUtils.isBlank(apiKeyId)) {
            return;
        }
        String policyId = resolveRemotePolicyId(remotePolicyReference);
        if (StringUtils.isBlank(policyId)) {
            throw new APIManagementException("Remote policy ID is required for rate limit policy removal");
        }
        try {
            DeleteUsagePlanKeyRequest deleteRequest = DeleteUsagePlanKeyRequest.builder()
                    .usagePlanId(policyId)
                    .keyId(apiKeyId)
                    .build();
            apiGatewayClient.deleteUsagePlanKey(deleteRequest);
        } catch (NotFoundException e) {
            if (log.isDebugEnabled()) {
                log.debug("AWS API key is not associated with usage plan: " + policyId, e);
            }
        } catch (Exception e) {
            throw new APIManagementException("Error removing API key usage plan association in AWS", e);
        }
    }

    private String buildApiKeyReferenceArtifact(String apiKeyId) {
        com.google.gson.JsonObject referenceArtifact = new com.google.gson.JsonObject();
        referenceArtifact.addProperty(API_KEY_ID, apiKeyId);
        return referenceArtifact.toString();
    }

    private String resolveApiKeyId(String apiKeyReferenceArtifact) throws APIManagementException {
        if (StringUtils.isBlank(apiKeyReferenceArtifact)) {
            return null;
        }
        try {
            com.google.gson.JsonObject referenceArtifact = com.google.gson.JsonParser
                    .parseString(apiKeyReferenceArtifact).getAsJsonObject();
            if (!referenceArtifact.has(API_KEY_ID) || referenceArtifact.get(API_KEY_ID).isJsonNull()
                    || StringUtils.isBlank(referenceArtifact.get(API_KEY_ID).getAsString())) {
                throw new APIManagementException("AWS API key reference artifact must contain apiKeyId");
            }
            return referenceArtifact.get(API_KEY_ID).getAsString();
        } catch (APIManagementException e) {
            throw e;
        } catch (Exception e) {
            throw new APIManagementException("Invalid AWS API key reference artifact", e);
        }
    }

    /**
     * Extracts the AWS usage plan ID from the connector-owned flat environment mapping.
     */
    private String resolveRemotePolicyId(String remotePolicyReference) {
        return StringUtils.trimToNull(remotePolicyReference);
    }

    private String resolveRemotePolicyReference(String localPolicyId, boolean requireLocalPolicy)
            throws APIManagementException {
        if (planMappings.isEmpty()) {
            return null;
        }
        if (StringUtils.isBlank(localPolicyId)) {
            if (requireLocalPolicy) {
                throw new APIManagementException("Local subscription policy is required for external plan mapping");
            }
            return null;
        }
        for (LocalPolicyRemoteMapping planMapping : planMappings) {
            if (StringUtils.equals(localPolicyId, planMapping.getLocalPolicyId())) {
                if (StringUtils.isBlank(planMapping.getRemotePlanReference())) {
                    throw new APIManagementException("External plan is not configured for local policy: "
                            + localPolicyId);
                }
                return planMapping.getRemotePlanReference();
            }
        }
        throw new APIManagementException("No external plan mapping found for local policy: " + localPolicyId
                + " in environment: " + environmentId);
    }

    /**
     * Builds AWS tags that keep enough WSO2 context on the remote API key for traceability.
     */
    private Map<String, String> buildTags(String apiKeyUuid, String awsApiId, Map<String, String> properties) {
        Map<String, String> tags = new HashMap<>();
        putTag(tags, TAG_API_ID, awsApiId);
        putTag(tags, TAG_API_UUID, properties != null ? properties.get(PROP_API_UUID) : null);
        putTag(tags, TAG_KEY_UUID, apiKeyUuid);
        putTag(tags, TAG_AUTHZ_USER, properties != null ? properties.get(PROP_AUTHZ_USER) : null);
        putTag(tags, TAG_ORGANIZATION, properties != null ? properties.get(PROP_ORGANIZATION_ID) : null);
        String validityPeriod = properties != null ? properties.get(PROP_VALIDITY_PERIOD) : null;
        if (StringUtils.isNotBlank(validityPeriod)) {
            putTag(tags, TAG_VALIDITY_PERIOD, validityPeriod);
        }
        putTag(tags, TAG_PERMITTED_IP, properties != null ? properties.get(PROP_PERMITTED_IP) : null);
        putTag(tags, TAG_PERMITTED_REFERER, properties != null ? properties.get(PROP_PERMITTED_REFERER) : null);
        return tags;
    }

    /**
     * Adds a bounded AWS tag value when both key and value are present.
     */
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

    private void loadPlanMappings(Environment environment) throws APIManagementException {
        planMappings.clear();
        if (environment.getAdditionalProperties() == null) {
            return;
        }
        for (Map.Entry<String, String> property : environment.getAdditionalProperties().entrySet()) {
            String key = property.getKey();
            if (!StringUtils.startsWith(key, PLAN_MAPPING_PROPERTY_PREFIX)) {
                continue;
            }
            String localPolicyId = StringUtils.removeStart(key, PLAN_MAPPING_PROPERTY_PREFIX);
            String remotePlanReference = StringUtils.trimToNull(property.getValue());
            if (StringUtils.isNotBlank(localPolicyId) && remotePlanReference != null) {
                planMappings.add(new LocalPolicyRemoteMapping(localPolicyId, remotePlanReference));
            }
        }
    }

    private static final class LocalPolicyRemoteMapping {
        private final String localPolicyId;
        private final String remotePlanReference;

        private LocalPolicyRemoteMapping(String localPolicyId, String remotePlanReference) {
            this.localPolicyId = localPolicyId;
            this.remotePlanReference = remotePlanReference;
        }

        private String getLocalPolicyId() {
            return localPolicyId;
        }

        private String getRemotePlanReference() {
            return remotePlanReference;
        }
    }
}
