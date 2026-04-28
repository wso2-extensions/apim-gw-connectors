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
import org.wso2.carbon.apimgt.api.model.Environment;
import org.wso2.carbon.apimgt.api.model.FederatedApiKeyContext;
import org.wso2.carbon.apimgt.api.model.FederatedApiKeyCreationResult;
import org.wso2.carbon.apimgt.impl.kmclient.ApacheFeignHttpClient;
import org.wso2.kong.client.model.KongAcl;
import org.wso2.kong.client.model.KongConsumer;
import org.wso2.kong.client.model.KongConsumerGroup;
import org.wso2.kong.client.model.KongConsumerGroupMembership;
import org.wso2.kong.client.model.KongKeyAuth;
import org.wso2.kong.client.model.PagedResponse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Kong implementation of federated API key management.
 */
public class KongFederatedApiKeyConnector implements FederatedApiKeyConnector {

    private static final Log log = LogFactory.getLog(KongFederatedApiKeyConnector.class);
    private static final int HTTP_NOT_FOUND = 404;
    private static final int HTTP_CONFLICT = 409;
    private static final int MAX_TAG_LENGTH = 256;
    private static final String CONSUMER_ID = "consumerId";
    private static final String KONG_REFERENCE_UUID = "uuid";
    private static final String TAG_API_ID = "wso2:api-id";
    private static final String TAG_API_UUID = "wso2:api-uuid";
    private static final String TAG_KEY_UUID = "wso2:key-uuid";
    private static final String TAG_AUTHZ_USER = "wso2:authz-user";
    private static final String TAG_ORGANIZATION = "wso2:organization";
    private static final String TAG_VALIDITY_PERIOD = "wso2:key-validity-period";
    private static final String TAG_PERMITTED_IP = "wso2:key-permitted-ip";
    private static final String TAG_PERMITTED_REFERER = "wso2:key-permitted-referer";
    private static final String PLAN_MAPPING_PROPERTY_PREFIX = "plan_mapping.";

    private KongKonnectApi apiGatewayClient;
    private String controlPlaneId;
    private String deploymentType;
    private String environmentId;
    private final List<LocalPolicyRemoteMapping> planMappings = new ArrayList<>();

    /**
     * Initializes the Kong Konnect client from the environment URL, control plane ID, and access token.
     */
    @Override
    public void init(Environment environment) throws APIManagementException {
        try {
            this.deploymentType = environment.getAdditionalProperties().get(KongConstants.KONG_DEPLOYMENT_TYPE);
            String adminUrl = environment.getAdditionalProperties().get(KongConstants.KONG_ADMIN_URL);
            this.controlPlaneId = environment.getAdditionalProperties().get(KongConstants.KONG_CONTROL_PLANE_ID);
            String authToken = environment.getAdditionalProperties().get(KongConstants.KONG_AUTH_TOKEN);
            this.environmentId = environment.getUuid();
            loadPlanMappings(environment);

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

    /**
     * Creates a Kong consumer, key-auth credential, and API ACL group for the local API-key value.
     */
    @Override
    public FederatedApiKeyCreationResult createApiKey(FederatedApiKeyContext context) throws APIManagementException {
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
            
            return FederatedApiKeyCreationResult.builder()
                    .referenceArtifact(buildApiKeyReferenceArtifact(consumerId))
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

    /**
     * Replaces the key-auth credential on the existing Kong consumer and keeps consumer-level ACL/group associations.
     */
    public FederatedApiKeyCreationResult replaceApiKey(FederatedApiKeyContext context) throws APIManagementException {
        if (context == null || StringUtils.isBlank(context.getApiKeyValue())) {
            throw new APIManagementException("API key value is required to replace Kong API key");
        }
        String consumerId = resolveConsumerId(context);
        if (StringUtils.isBlank(consumerId)) {
            return createApiKey(context);
        }

        List<KongKeyAuth> existingCredentials = listKeyAuthCredentials(consumerId);
        KongKeyAuth keyAuthRequest = new KongKeyAuth();
        keyAuthRequest.setKey(context.getApiKeyValue());
        if (context.getValidityPeriod() != null && context.getValidityPeriod() > 0) {
            keyAuthRequest.setTtl(context.getValidityPeriod());
        }
        try {
            String remoteApiId = resolveRemoteApiId(context.getApiReferenceArtifact());
            keyAuthRequest.setTags(buildMetadataTags(context, remoteApiId));
            KongKeyAuth replacement = createKeyAuthWithTagFallback(consumerId, keyAuthRequest);
            if (replacement == null || StringUtils.isBlank(replacement.getId())) {
                throw new APIManagementException("Failed to create replacement Kong key-auth credential");
            }
            deleteOldKeyAuthCredentials(consumerId, existingCredentials, replacement.getId());
            return FederatedApiKeyCreationResult.builder()
                    .referenceArtifact(buildApiKeyReferenceArtifact(consumerId))
                    .build();
        } catch (APIManagementException e) {
            throw e;
        } catch (Exception e) {
            throw new APIManagementException("Error replacing API key in Kong", e);
        }
    }

    /**
     * Deletes the Kong consumer identified by the stored connector-owned reference artifact.
     */
    @Override
    public void revokeApiKey(FederatedApiKeyContext context) throws APIManagementException {
        String consumerId = resolveConsumerId(context);
        if (StringUtils.isBlank(consumerId)) {
            return;
        }
        try {
            apiGatewayClient.deleteConsumer(controlPlaneId, consumerId);
        } catch (KongGatewayException e) {
            if (e.getStatusCode() != HTTP_NOT_FOUND) {
                throw new APIManagementException("Error revoking API key in Kong: " + e.getMessage(), e);
            }
        } catch (Exception e) {
            throw new APIManagementException("Error revoking API key in Kong", e);
        }
    }

    /**
     * Adds ACL and consumer-group associations for the mapped remote consumer group.
     */
    @Override
    public void applyRateLimitPolicy(FederatedApiKeyContext context) throws APIManagementException {
        String remotePolicyReference = resolveRemotePolicyReference(context, true);
        if (StringUtils.isBlank(remotePolicyReference)) {
            return;
        }
        String consumerId = resolveConsumerId(context);
        if (StringUtils.isBlank(consumerId)) {
            throw new APIManagementException("Remote API key ID is required for Kong association");
        }
        String policyId = resolveRemotePolicyId(remotePolicyReference);
        if (StringUtils.isBlank(policyId)) {
            throw new APIManagementException("Mapped remote consumer group ID is required for Kong association");
        }

        try {
            String remoteApiId = resolveRemoteApiId(context.getApiReferenceArtifact());
            if (!consumerGroupExists(policyId)) {
                throw new APIManagementException("Mapped Kong consumer group was not found: " + policyId);
            }

            createAclIfAbsent(consumerId, buildPlanAclGroup(policyId));
            createAclIfAbsent(consumerId, buildSubscriptionAclGroup(remoteApiId, policyId));
            addConsumerToGroupIfAbsent(consumerId, policyId);
        } catch (APIManagementException e) {
            throw e;
        } catch (Exception e) {
            throw new APIManagementException("Error associating Kong consumer to group: " + policyId, e);
        }
    }

    /**
     * Removes ACL and consumer-group associations for the mapped remote consumer group.
     */
    @Override
    public void removeRateLimitPolicy(FederatedApiKeyContext context) throws APIManagementException {
        String remotePolicyReference = resolveRemotePolicyReference(context, true);
        if (StringUtils.isBlank(remotePolicyReference)) {
            return;
        }
        String consumerId = resolveConsumerId(context);
        if (StringUtils.isBlank(consumerId)) {
            return;
        }
        String policyId = resolveRemotePolicyId(remotePolicyReference);
        if (StringUtils.isBlank(policyId)) {
            throw new APIManagementException("Mapped remote consumer group ID is required for Kong dissociation");
        }
        try {
            String remoteApiId = null;
            if (StringUtils.isNotBlank(context.getApiReferenceArtifact())) {
                remoteApiId = resolveRemoteApiId(context.getApiReferenceArtifact());
            }
            removeAclGroup(consumerId, buildPlanAclGroup(policyId));
            removeAclGroup(consumerId, buildSubscriptionAclGroup(remoteApiId, policyId));
            removeConsumerFromGroup(consumerId, policyId);
        } catch (Exception e) {
            throw new APIManagementException("Error removing Kong consumer group associations", e);
        }
    }

    /**
     * Extracts the Kong consumer group ID from the connector-owned flat environment mapping.
     */
    private String resolveRemotePolicyId(String remotePolicyReference) {
        return StringUtils.trimToNull(remotePolicyReference);
    }

    private String resolveRemotePolicyReference(FederatedApiKeyContext context, boolean requireLocalPolicy)
            throws APIManagementException {
        if (planMappings.isEmpty()) {
            return null;
        }
        String localPolicyId = context != null ? context.getLocalPolicyId() : null;
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

    private String buildApiKeyReferenceArtifact(String consumerId) {
        JsonObject referenceArtifact = new JsonObject();
        referenceArtifact.addProperty(CONSUMER_ID, consumerId);
        return referenceArtifact.toString();
    }

    private String resolveConsumerId(FederatedApiKeyContext context) throws APIManagementException {
        if (context == null || StringUtils.isBlank(context.getApiKeyReferenceArtifact())) {
            return null;
        }
        try {
            JsonObject referenceArtifact = JsonParser.parseString(context.getApiKeyReferenceArtifact())
                    .getAsJsonObject();
            if (referenceArtifact.has(CONSUMER_ID) && !referenceArtifact.get(CONSUMER_ID).isJsonNull()) {
                String consumerId = referenceArtifact.get(CONSUMER_ID).getAsString();
                if (StringUtils.isNotBlank(consumerId)) {
                    return consumerId;
                }
            }
            throw new APIManagementException("Kong API key reference artifact must contain consumerId");
        } catch (APIManagementException e) {
            throw e;
        } catch (Exception e) {
            throw new APIManagementException("Invalid Kong API key reference artifact", e);
        }
    }

    /**
     * Returns the gateway type handled by this connector.
     */
    @Override
    public String getGatewayType() {
        return KongConstants.KONG_TYPE;
    }

    /**
     * Indicates that Kong federated API-key provisioning is supported.
     */
    @Override
    public boolean isApiKeySupport() {
        return true;
    }

    /**
     * Extracts the Kong remote API ID from the connector-owned API reference artifact.
     */
    private String resolveRemoteApiId(String apiReferenceArtifact) throws APIManagementException {
        if (StringUtils.isBlank(apiReferenceArtifact)) {
            throw new APIManagementException("Kong API reference artifact is required");
        }
        try {
            JsonObject refArtifact = JsonParser.parseString(apiReferenceArtifact).getAsJsonObject();
            if (refArtifact.has(KONG_REFERENCE_UUID) && !refArtifact.get(KONG_REFERENCE_UUID).isJsonNull()) {
                String value = refArtifact.get(KONG_REFERENCE_UUID).getAsString();
                if (StringUtils.isNotBlank(value)) {
                    return value;
                }
            }
            throw new APIManagementException("Kong API reference artifact must contain " + KONG_REFERENCE_UUID);
        } catch (APIManagementException e) {
            throw e;
        } catch (Exception e) {
            throw new APIManagementException("Invalid Kong API reference artifact", e);
        }
    }

    /**
     * Checks whether the mapped Kong consumer group exists before applying the association.
     */
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

    /**
     * Creates an ACL group on the consumer, treating existing ACLs as success for idempotency.
     */
    private void createAclIfAbsent(String consumerId, String group) throws APIManagementException {
        try {
            apiGatewayClient.createAcl(controlPlaneId, consumerId, new KongAcl(group));
        } catch (KongGatewayException e) {
            if (!shouldIgnoreCreateConflict(e)) {
                throw new APIManagementException("Error adding ACL group '" + group + "' to Kong consumer '"
                        + consumerId + "'", e);
            }
        }
    }

    /**
     * Adds a consumer to a group, treating existing membership as success for idempotency.
     */
    private void addConsumerToGroupIfAbsent(String consumerId, String groupId) throws APIManagementException {
        try {
            apiGatewayClient.addConsumerToGroup(controlPlaneId, groupId, new KongConsumerGroupMembership(consumerId));
        } catch (KongGatewayException e) {
            if (!shouldIgnoreCreateConflict(e)) {
                throw new APIManagementException("Error adding consumer '" + consumerId
                        + "' to Kong consumer group '" + groupId + "'", e);
            }
        }
    }

    /**
     * Removes a consumer from a group, ignoring missing membership errors.
     */
    private void removeConsumerFromGroup(String consumerId, String groupId) throws APIManagementException {
        try {
            apiGatewayClient.removeConsumerFromGroup(controlPlaneId, groupId, consumerId);
        } catch (KongGatewayException e) {
            if (!shouldIgnoreGroupRemovalError(e)) {
                throw new APIManagementException("Error removing consumer '" + consumerId
                        + "' from Kong consumer group '" + groupId + "'", e);
            }
        }
    }

    /**
     * Removes one exact ACL group from a consumer.
     */
    private void removeAclGroup(String consumerId, String group) throws APIManagementException {
        removeAclsByPredicate(consumerId, acl -> acl != null && StringUtils.equals(acl.getGroup(), group));
    }

    private List<KongKeyAuth> listKeyAuthCredentials(String consumerId) throws APIManagementException {
        try {
            PagedResponse<KongKeyAuth> response = apiGatewayClient.listKeyAuth(controlPlaneId, consumerId,
                    KongConstants.DEFAULT_KEY_AUTH_LIST_LIMIT);
            if (response == null || response.getData() == null) {
                return Collections.emptyList();
            }
            return response.getData();
        } catch (KongGatewayException e) {
            if (e.getStatusCode() == HTTP_NOT_FOUND) {
                throw new APIManagementException("Kong consumer was not found for API key replacement: "
                        + consumerId, e);
            }
            throw new APIManagementException("Error listing Kong key-auth credentials for consumer: " + consumerId, e);
        } catch (Exception e) {
            throw new APIManagementException("Error listing Kong key-auth credentials for consumer: " + consumerId, e);
        }
    }

    private void deleteOldKeyAuthCredentials(String consumerId, List<KongKeyAuth> existingCredentials,
                                             String replacementCredentialId) throws APIManagementException {
        for (KongKeyAuth credential : existingCredentials) {
            if (credential == null || StringUtils.isBlank(credential.getId())
                    || StringUtils.equals(credential.getId(), replacementCredentialId)) {
                continue;
            }
            try {
                apiGatewayClient.deleteKeyAuth(controlPlaneId, consumerId, credential.getId());
            } catch (KongGatewayException e) {
                if (e.getStatusCode() != HTTP_NOT_FOUND) {
                    throw new APIManagementException("Error deleting old Kong key-auth credential: "
                            + credential.getId(), e);
                }
            } catch (Exception e) {
                throw new APIManagementException("Error deleting old Kong key-auth credential: "
                        + credential.getId(), e);
            }
        }
    }

    /**
     * Lists consumer ACLs and removes the entries selected by the supplied predicate.
     */
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

    /**
     * Builds the API-level ACL group that restricts a key to the remote API.
     */
    private String buildApiAclGroup(String remoteApiId) {
        return KongConstants.API_ACL_GROUP_PREFIX + remoteApiId;
    }

    /**
     * Builds the plan-level ACL group for the mapped remote consumer group.
     */
    private String buildPlanAclGroup(String remoteUsagePlanId) {
        return KongConstants.TIER_ACL_GROUP_PREFIX + remoteUsagePlanId;
    }

    /**
     * Builds the subscription-scoped ACL group for the remote API and mapped remote consumer group.
     */
    private String buildSubscriptionAclGroup(String remoteApiId, String remoteUsagePlanId) {
        return buildSubscriptionAclPrefix(remoteApiId) + remoteUsagePlanId;
    }

    /**
     * Builds the ACL group prefix used to scope a tier association to one remote API.
     */
    private String buildSubscriptionAclPrefix(String remoteApiId) {
        if (StringUtils.isBlank(remoteApiId)) {
            return KongConstants.SUBSCRIPTION_ACL_GROUP_PREFIX;
        }
        return KongConstants.SUBSCRIPTION_ACL_GROUP_PREFIX + remoteApiId + KongConstants.API_ACL_GROUP_SEPARATOR;
    }

    /**
     * Identifies Kong errors that mean the requested group/membership was already absent.
     */
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

    /**
     * Identifies Kong errors that mean the requested group/membership was already present.
     */
    private boolean shouldIgnoreCreateConflict(KongGatewayException exception) {
        if (exception == null) {
            return false;
        }
        int statusCode = exception.getStatusCode();
        if (statusCode == HTTP_CONFLICT) {
            return true;
        }
        if (statusCode == 400) {
            String message = exception.getMessage();
            if (StringUtils.isBlank(message)) {
                return false;
            }
            String normalized = message.toLowerCase();
            return normalized.contains("already")
                    || normalized.contains("exists")
                    || normalized.contains("duplicate");
        }
        return false;
    }

    /**
     * Deletes a consumer created during a failed create operation.
     */
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

    /**
     * Creates key-auth with metadata tags, retrying without tags if Kong rejects tagged credentials.
     */
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

    /**
     * Builds Kong metadata tags that keep enough WSO2 context on the remote consumer and credential.
     */
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

    /**
     * Adds a bounded Kong tag value when both key and value are present.
     */
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

    private void loadPlanMappings(Environment environment) throws APIManagementException {
        planMappings.clear();
        if (environment.getAdditionalProperties() == null) {
            return;
        }
        for (java.util.Map.Entry<String, String> property : environment.getAdditionalProperties().entrySet()) {
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
