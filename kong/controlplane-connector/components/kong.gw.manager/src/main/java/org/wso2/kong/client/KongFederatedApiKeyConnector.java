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
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.kong.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import feign.Feign;
import feign.RequestInterceptor;
import feign.gson.GsonDecoder;
import feign.gson.GsonEncoder;
import feign.slf4j.Slf4jLogger;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.FederatedApiKeyConnector;
import org.wso2.carbon.apimgt.api.model.CredentialCreationResult;
import org.wso2.carbon.apimgt.api.model.Environment;
import org.wso2.carbon.apimgt.api.model.ExternalSubscriptionPolicy;
import org.wso2.carbon.apimgt.api.model.FederatedApiKeyContext;
import org.wso2.carbon.apimgt.api.model.GatewayPortalConfiguration;
import org.wso2.carbon.apimgt.impl.kmclient.ApacheFeignHttpClient;
import org.wso2.kong.client.model.KongAcl;
import org.wso2.kong.client.model.KongConsumer;
import org.wso2.kong.client.model.KongConsumerGroup;
import org.wso2.kong.client.model.KongConsumerGroupMembership;
import org.wso2.kong.client.model.KongKeyAuth;
import org.wso2.kong.client.model.PagedResponse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Kong implementation of federated API key management.
 */
public class KongFederatedApiKeyConnector implements FederatedApiKeyConnector {

    private static final Log log = LogFactory.getLog(KongFederatedApiKeyConnector.class);
    private static final int HTTP_NOT_FOUND = 404;
    private static final int HTTP_CONFLICT = 409;
    private static final int MAX_TAG_LENGTH = 256;
    private static final String TAG_API_ID = "wso2:api-id";
    private static final String TAG_API_UUID = "wso2:api-uuid";
    private static final String TAG_KEY_UUID = "wso2:key-uuid";
    private static final String TAG_AUTHZ_USER = "wso2:authz-user";
    private static final String TAG_ORGANIZATION = "wso2:organization";
    private static final String TAG_VALIDITY_PERIOD = "wso2:key-validity-period";
    private static final String TAG_PERMITTED_IP = "wso2:key-permitted-ip";
    private static final String TAG_PERMITTED_REFERER = "wso2:key-permitted-referer";

    private KongKonnectApi apiGatewayClient;
    private String controlPlaneId;
    private String deploymentType;

    @Override
    public void init(Environment environment, String organization) throws APIManagementException {
        try {
            this.deploymentType = environment.getAdditionalProperties().get(KongConstants.KONG_DEPLOYMENT_TYPE);
            String adminUrl = environment.getAdditionalProperties().get(KongConstants.KONG_ADMIN_URL);
            this.controlPlaneId = environment.getAdditionalProperties().get(KongConstants.KONG_CONTROL_PLANE_ID);
            String authToken = environment.getAdditionalProperties().get(KongConstants.KONG_AUTH_TOKEN);

            if (KongConstants.KONG_KUBERNETES_DEPLOYMENT.equals(deploymentType)) {
                throw new APIManagementException("Kong API key federation is not supported for Kubernetes mode");
            }
            if (StringUtils.isAnyBlank(adminUrl, controlPlaneId, authToken)) {
                throw new APIManagementException("Missing required Kong environment configurations");
            }

            CloseableHttpClient httpClient = HttpClients.custom().build();
            RequestInterceptor auth = template ->
                    template.header(KongConstants.AUTHORIZATION_HEADER, KongConstants.BEARER_PREFIX + authToken);
            apiGatewayClient = Feign.builder()
                    .client(new ApacheFeignHttpClient(httpClient))
                    .encoder(new GsonEncoder())
                    .decoder(new GsonDecoder())
                    .errorDecoder(new KongErrorDecoder())
                    .logger(new Slf4jLogger(KongKonnectApi.class))
                    .requestInterceptor(auth)
                    .target(KongKonnectApi.class, adminUrl);
        } catch (APIManagementException e) {
            throw e;
        } catch (Exception e) {
            throw new APIManagementException("Error occurred while initializing Kong Federated API Key Connector", e);
        }
    }

    @Override
    public CredentialCreationResult createApiKey(FederatedApiKeyContext context) throws APIManagementException {
        if (context == null || StringUtils.isAnyBlank(context.getApiKeyUuid(), context.getApiKeyValue())) {
            throw new APIManagementException("API key UUID and value are required");
        }

        String consumerId = null;
        String consumerName = KongConstants.CONSUMER_NAME_PREFIX + context.getApiKeyUuid();
        try {
            String remoteApiId = resolveRemoteApiId(context.getApiReferenceArtifact());
            List<String> metadataTags = buildMetadataTags(context, remoteApiId);

            KongConsumer consumerRequest = new KongConsumer(consumerName, context.getApiKeyUuid());
            consumerRequest.setTags(metadataTags);
            KongConsumer consumer = apiGatewayClient.createConsumer(controlPlaneId, consumerRequest);
            if (consumer == null || StringUtils.isBlank(consumer.getId())) {
                throw new APIManagementException("Failed to create Kong consumer for API key");
            }
            consumerId = consumer.getId();

            KongKeyAuth keyAuthRequest = new KongKeyAuth();
            keyAuthRequest.setKey(context.getApiKeyValue());
            if (context.getValidityPeriod() != null && context.getValidityPeriod() > 0) {
                keyAuthRequest.setTtl(context.getValidityPeriod());
            }
            keyAuthRequest.setTags(metadataTags);
            KongKeyAuth keyAuth = createKeyAuthWithTagFallback(consumerId, keyAuthRequest);
            if (keyAuth == null || StringUtils.isBlank(keyAuth.getId())) {
                throw new APIManagementException("Failed to create Kong key-auth credential");
            }
            apiGatewayClient.createAcl(controlPlaneId, consumerId, new KongAcl(buildApiAclGroup(remoteApiId)));
            
            return CredentialCreationResult.builder()
                    .remoteCredentialId(consumerId)
                    .credentialType("KONG_CONSUMER")
                    .build();
        } catch (KongGatewayException e) {
            if (e.getStatusCode() == HTTP_CONFLICT) {
                throw new APIManagementException("Kong consumer already exists for key UUID: "
                        + context.getApiKeyUuid(), e);
            }
            rollbackCreatedConsumer(consumerId, e);
            throw new APIManagementException("Error creating API key in Kong: " + e.getMessage(), e);
        } catch (Exception e) {
            rollbackCreatedConsumer(consumerId, e);
            throw new APIManagementException("Error creating API key in Kong", e);
        }
    }

    @Override
    public void revokeApiKey(FederatedApiKeyContext context) throws APIManagementException {
        if (context == null || StringUtils.isBlank(context.getRemoteApiKeyId())) {
            return;
        }
        try {
            apiGatewayClient.deleteConsumer(controlPlaneId, context.getRemoteApiKeyId());
        } catch (KongGatewayException e) {
            if (e.getStatusCode() != HTTP_NOT_FOUND) {
                throw new APIManagementException("Error revoking API key in Kong: " + e.getMessage(), e);
            }
        } catch (Exception e) {
            throw new APIManagementException("Error revoking API key in Kong", e);
        }
    }

    @Override
    public void applyRateLimitPolicy(FederatedApiKeyContext context, String policyId)
            throws APIManagementException {
        if (context == null || StringUtils.isBlank(context.getRemoteApiKeyId())) {
            throw new APIManagementException("Remote API key ID is required for Kong association");
        }
        if (StringUtils.isBlank(policyId)) {
            throw new APIManagementException("Mapped remote consumer group ID is required for Kong association");
        }

        try {
            String remoteApiId = resolveRemoteApiId(context.getApiReferenceArtifact());
            if (!consumerGroupExists(policyId)) {
                throw new APIManagementException("Mapped Kong consumer group was not found: " + policyId);
            }

            removeTierAclGroups(context.getRemoteApiKeyId());
            removeSubscriptionScopedAclGroups(context.getRemoteApiKeyId(), remoteApiId);
            apiGatewayClient.createAcl(controlPlaneId, context.getRemoteApiKeyId(),
                    new KongAcl(buildTierAclGroup(policyId)));
            apiGatewayClient.createAcl(controlPlaneId, context.getRemoteApiKeyId(),
                    new KongAcl(buildSubscriptionAclGroup(remoteApiId, policyId)));

            removeConsumerFromAllGroups(context.getRemoteApiKeyId());
            apiGatewayClient.addConsumerToGroup(controlPlaneId, policyId,
                    new KongConsumerGroupMembership(context.getRemoteApiKeyId()));
        } catch (APIManagementException e) {
            throw e;
        } catch (Exception e) {
            throw new APIManagementException("Error associating Kong consumer to group: " + policyId, e);
        }
    }

    @Override
    public void removeRateLimitPolicy(FederatedApiKeyContext context) throws APIManagementException {
        if (context == null || StringUtils.isBlank(context.getRemoteApiKeyId())) {
            return;
        }
        try {
            String remoteApiId = null;
            if (StringUtils.isNotBlank(context.getApiReferenceArtifact())) {
                remoteApiId = resolveRemoteApiId(context.getApiReferenceArtifact());
            }
            removeTierAclGroups(context.getRemoteApiKeyId());
            removeSubscriptionScopedAclGroups(context.getRemoteApiKeyId(), remoteApiId);
            removeConsumerFromAllGroups(context.getRemoteApiKeyId());
        } catch (Exception e) {
            throw new APIManagementException("Error removing Kong consumer group associations", e);
        }
    }

    @Override
    public String resolveRemotePolicyId(String remotePolicyReference) throws APIManagementException {
        if (StringUtils.isBlank(remotePolicyReference)) {
            return null;
        }
        String value = remotePolicyReference.trim();
        try {
            JsonObject policyJson = JsonParser.parseString(value).getAsJsonObject();
            if (policyJson.has("id") && !policyJson.get("id").isJsonNull()) {
                return policyJson.get("id").getAsString();
            }
            if (policyJson.has("planId") && !policyJson.get("planId").isJsonNull()) {
                return policyJson.get("planId").getAsString();
            }
            if (policyJson.has("raw") && !policyJson.get("raw").isJsonNull()) {
                return policyJson.get("raw").getAsString();
            }
        } catch (Exception ignored) {
            // Fall back to raw text format
        }
        return value;
    }

    @Override
    public List<ExternalSubscriptionPolicy> listRateLimitPolicies(Environment environment)
            throws APIManagementException {
        List<ExternalSubscriptionPolicy> rateLimitPolicies = new ArrayList<>();
        try {
            PagedResponse<KongConsumerGroup> response = apiGatewayClient.listConsumerGroups(controlPlaneId,
                    KongConstants.DEFAULT_CONSUMER_GROUP_LIST_LIMIT);
            if (response == null || response.getData() == null) {
                return rateLimitPolicies;
            }
            for (KongConsumerGroup group : response.getData()) {
                if (group == null || StringUtils.isBlank(group.getId())) {
                    continue;
                }
                Map<String, String> limits = new HashMap<>();
                rateLimitPolicies.add(new ExternalSubscriptionPolicy(group.getId(),
                        StringUtils.defaultIfBlank(group.getName(), group.getId()),
                        "", limits));
            }
            return rateLimitPolicies;
        } catch (Exception e) {
            throw new APIManagementException("Failed to list Kong consumer groups: " + e.getMessage(), e);
        }
    }

    @Override
    public String getGatewayType() {
        return KongConstants.KONG_TYPE;
    }

    @Override
    public boolean isApiKeySupport() {
        try {
            GatewayPortalConfiguration featureCatalog = new KongGatewayConfiguration().getGatewayFeatureCatalog();
            Object supportedFeatures = featureCatalog.getSupportedFeatures();
            if (supportedFeatures instanceof JsonObject) {
                JsonObject featuresJson = (JsonObject) supportedFeatures;
                if (featuresJson.has("apiKeys") && featuresJson.get("apiKeys").isJsonObject()) {
                    JsonObject apiKeys = featuresJson.getAsJsonObject("apiKeys");
                    return apiKeys.has("supported") && apiKeys.get("supported").getAsBoolean();
                }
            }
        } catch (APIManagementException e) {
            log.warn("Error while resolving Kong API key support from GatewayFeatureCatalog", e);
        }
        return false;
    }

    private String resolveRemoteApiId(String apiReferenceArtifact) throws APIManagementException {
        if (StringUtils.isBlank(apiReferenceArtifact)) {
            throw new APIManagementException("Kong API reference artifact is required");
        }
        try {
            JsonObject refArtifact = JsonParser.parseString(apiReferenceArtifact).getAsJsonObject();
            if (refArtifact.has("uuid") && !refArtifact.get("uuid").isJsonNull()) {
                String value = refArtifact.get("uuid").getAsString();
                if (StringUtils.isNotBlank(value)) {
                    return value;
                }
            }
            if (refArtifact.has("id") && !refArtifact.get("id").isJsonNull()) {
                String value = refArtifact.get("id").getAsString();
                if (StringUtils.isNotBlank(value)) {
                    return value;
                }
            }
        } catch (Exception ignored) {
            // Fallback to raw value below.
        }
        String raw = apiReferenceArtifact.trim();
        if (StringUtils.isBlank(raw)) {
            throw new APIManagementException("Unable to resolve Kong remote API ID from reference artifact");
        }
        return raw;
    }

    private boolean consumerGroupExists(String consumerGroupId) throws APIManagementException {
        try {
            PagedResponse<KongConsumerGroup> response = apiGatewayClient.listConsumerGroups(controlPlaneId,
                    KongConstants.DEFAULT_CONSUMER_GROUP_LIST_LIMIT);
            if (response == null || response.getData() == null) {
                return false;
            }
            for (KongConsumerGroup group : response.getData()) {
                if (group != null && StringUtils.equals(group.getId(), consumerGroupId)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            throw new APIManagementException("Error while validating Kong consumer group: " + consumerGroupId, e);
        }
    }

    private void removeConsumerFromAllGroups(String consumerId) throws APIManagementException {
        PagedResponse<KongConsumerGroup> response;
        try {
            response = apiGatewayClient.listConsumerGroups(controlPlaneId,
                    KongConstants.DEFAULT_CONSUMER_GROUP_LIST_LIMIT);
        } catch (KongGatewayException e) {
            throw new APIManagementException("Error while listing Kong consumer groups", e);
        }
        if (response == null || response.getData() == null) {
            return;
        }
        for (KongConsumerGroup group : response.getData()) {
            if (group == null || StringUtils.isBlank(group.getId())) {
                continue;
            }
            try {
                apiGatewayClient.removeConsumerFromGroup(controlPlaneId, group.getId(), consumerId);
            } catch (KongGatewayException e) {
                if (!shouldIgnoreGroupRemovalError(e)) {
                    throw new APIManagementException("Error removing consumer '" + consumerId
                            + "' from Kong consumer group '" + group.getId() + "'", e);
                }
            }
        }
    }

    private void removeTierAclGroups(String consumerId) throws APIManagementException {
        removeAclsByPredicate(consumerId, acl -> acl != null
                && StringUtils.startsWith(acl.getGroup(), KongConstants.TIER_ACL_GROUP_PREFIX));
    }

    private void removeSubscriptionScopedAclGroups(String consumerId, String remoteApiId)
            throws APIManagementException {
        String subscriptionPrefix = buildSubscriptionAclPrefix(remoteApiId);
        removeAclsByPredicate(consumerId, acl -> acl != null
                && StringUtils.startsWith(acl.getGroup(), subscriptionPrefix));
    }

    private void removeAclsByPredicate(String consumerId, java.util.function.Predicate<KongAcl> predicate)
            throws APIManagementException {
        PagedResponse<KongAcl> response;
        try {
            response = apiGatewayClient.listConsumerAcls(controlPlaneId,
                    consumerId, KongConstants.DEFAULT_ACL_LIST_LIMIT);
        } catch (KongGatewayException e) {
            if (e.getStatusCode() == HTTP_NOT_FOUND) {
                return;
            }
            throw new APIManagementException("Error while listing Kong ACL groups for consumer: " + consumerId, e);
        }
        if (response == null || response.getData() == null) {
            return;
        }
        for (KongAcl acl : response.getData()) {
            if (acl == null || StringUtils.isBlank(acl.getId()) || StringUtils.isBlank(acl.getGroup())) {
                continue;
            }
            if (!predicate.test(acl)) {
                continue;
            }
            try {
                apiGatewayClient.deleteConsumerAcl(controlPlaneId, consumerId, acl.getId());
            } catch (KongGatewayException e) {
                if (!shouldIgnoreGroupRemovalError(e)) {
                    throw new APIManagementException("Error removing ACL group '" + acl.getGroup()
                            + "' from Kong consumer '" + consumerId + "'", e);
                }
            }
        }
    }

    private String buildApiAclGroup(String remoteApiId) {
        return KongConstants.API_ACL_GROUP_PREFIX + remoteApiId;
    }

    private String buildTierAclGroup(String remoteUsagePlanId) {
        return KongConstants.TIER_ACL_GROUP_PREFIX + remoteUsagePlanId;
    }

    private String buildSubscriptionAclGroup(String remoteApiId, String remoteUsagePlanId) {
        return buildSubscriptionAclPrefix(remoteApiId) + remoteUsagePlanId;
    }

    private String buildSubscriptionAclPrefix(String remoteApiId) {
        if (StringUtils.isBlank(remoteApiId)) {
            return KongConstants.SUBSCRIPTION_ACL_GROUP_PREFIX;
        }
        return KongConstants.SUBSCRIPTION_ACL_GROUP_PREFIX + remoteApiId + KongConstants.API_ACL_GROUP_SEPARATOR;
    }

    private boolean shouldIgnoreGroupRemovalError(KongGatewayException exception) {
        if (exception == null) {
            return false;
        }
        int statusCode = exception.getStatusCode();
        if (statusCode == HTTP_NOT_FOUND) {
            return true;
        }
        if (statusCode == 400 || statusCode == 409) {
            String message = exception.getMessage();
            if (StringUtils.isBlank(message)) {
                return false;
            }
            String normalized = message.toLowerCase();
            return normalized.contains("not in")
                    || normalized.contains("not member")
                    || normalized.contains("does not belong")
                    || normalized.contains("not found");
        }
        return false;
    }

    private void rollbackCreatedConsumer(String consumerId, Exception originalError) {
        if (StringUtils.isBlank(consumerId)) {
            return;
        }
        try {
            apiGatewayClient.deleteConsumer(controlPlaneId, consumerId);
        } catch (Exception rollbackError) {
            log.error("Failed to rollback Kong consumer after create failure: " + consumerId, rollbackError);
            if (log.isDebugEnabled()) {
                log.debug("Original error while creating Kong API key", originalError);
            }
        }
    }

    private KongKeyAuth createKeyAuthWithTagFallback(String consumerId, KongKeyAuth keyAuthRequest)
            throws APIManagementException {
        try {
            return apiGatewayClient.createKeyAuth(controlPlaneId, consumerId, keyAuthRequest);
        } catch (KongGatewayException e) {
            if (keyAuthRequest.getTags() != null && !keyAuthRequest.getTags().isEmpty()) {
                log.warn("Kong key-auth create with metadata tags failed; retrying without tags for consumer: "
                        + consumerId, e);
                KongKeyAuth fallbackRequest = new KongKeyAuth();
                fallbackRequest.setKey(keyAuthRequest.getKey());
                fallbackRequest.setTtl(keyAuthRequest.getTtl());
                fallbackRequest.setTags(Collections.emptyList());
                try {
                    return apiGatewayClient.createKeyAuth(controlPlaneId, consumerId, fallbackRequest);
                } catch (Exception retryException) {
                    throw new APIManagementException("Failed to create Kong key-auth credential", retryException);
                }
            }
            throw new APIManagementException("Failed to create Kong key-auth credential", e);
        } catch (Exception e) {
            throw new APIManagementException("Failed to create Kong key-auth credential", e);
        }
    }

    private List<String> buildMetadataTags(FederatedApiKeyContext context, String remoteApiId) {
        List<String> tags = new ArrayList<>();
        addTag(tags, TAG_API_ID, remoteApiId);
        addTag(tags, TAG_API_UUID, context != null ? context.getApiUuid() : null);
        addTag(tags, TAG_KEY_UUID, context != null ? context.getApiKeyUuid() : null);
        addTag(tags, TAG_AUTHZ_USER, context != null ? context.getAuthzUser() : null);
        addTag(tags, TAG_ORGANIZATION, context != null ? context.getOrganizationId() : null);
        if (context != null && context.getValidityPeriod() != null) {
            addTag(tags, TAG_VALIDITY_PERIOD, String.valueOf(context.getValidityPeriod()));
        }
        addTag(tags, TAG_PERMITTED_IP, context != null ? context.getPermittedIP() : null);
        addTag(tags, TAG_PERMITTED_REFERER, context != null ? context.getPermittedReferer() : null);
        return tags;
    }

    private void addTag(List<String> tags, String key, String value) {
        if (StringUtils.isBlank(key) || StringUtils.isBlank(value)) {
            return;
        }
        String trimmed = value.trim();
        if (trimmed.length() > MAX_TAG_LENGTH) {
            trimmed = trimmed.substring(0, MAX_TAG_LENGTH);
        }
        tags.add(key + "=" + trimmed);
    }
}
