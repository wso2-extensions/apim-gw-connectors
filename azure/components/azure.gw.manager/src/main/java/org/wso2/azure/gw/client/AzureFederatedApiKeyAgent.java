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

package org.wso2.azure.gw.client;

import com.azure.core.credential.TokenCredential;
import com.azure.core.http.HttpClient;
import com.azure.core.http.netty.NettyAsyncHttpClientBuilder;
import com.azure.core.management.AzureEnvironment;
import com.azure.core.management.profile.AzureProfile;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.azure.resourcemanager.apimanagement.ApiManagementManager;
import com.azure.resourcemanager.apimanagement.models.SubscriptionContract;
import com.azure.resourcemanager.apimanagement.models.SubscriptionCreateParameters;
import com.azure.resourcemanager.apimanagement.models.SubscriptionState;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.FederatedApiKeyAgent;
import org.wso2.carbon.apimgt.api.model.CredentialCreationResult;
import org.wso2.carbon.apimgt.api.model.Environment;
import org.wso2.carbon.apimgt.api.model.ExternalSubscriptionPolicy;
import org.wso2.carbon.apimgt.api.model.FederatedApiKeyContext;
import org.wso2.carbon.apimgt.api.model.GatewayPortalConfiguration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Azure implementation of federated API key management.
 * This follows API-bound key provisioning using Azure APIM subscriptions scoped to a single API.
 */
public class AzureFederatedApiKeyAgent implements FederatedApiKeyAgent {

    private static final Log log = LogFactory.getLog(AzureFederatedApiKeyAgent.class);

    private String resourceGroup;
    private String serviceName;
    private ApiManagementManager manager;

    @Override
    public void init(Environment environment, String organization) throws APIManagementException {
        try {
            String tenantId = environment.getAdditionalProperties().get(AzureConstants.AZURE_ENVIRONMENT_TENANT_ID);
            String clientId = environment.getAdditionalProperties().get(AzureConstants.AZURE_ENVIRONMENT_CLIENT_ID);
            String clientSecret = environment.getAdditionalProperties()
                    .get(AzureConstants.AZURE_ENVIRONMENT_CLIENT_SECRET);
            String subscriptionId = environment.getAdditionalProperties()
                    .get(AzureConstants.AZURE_ENVIRONMENT_SUBSCRIPTION_ID);

            HttpClient httpClient = new NettyAsyncHttpClientBuilder().build();
            TokenCredential credential = new ClientSecretCredentialBuilder()
                    .httpClient(httpClient)
                    .tenantId(tenantId)
                    .clientId(clientId)
                    .clientSecret(clientSecret)
                    .authorityHost(AzureEnvironment.AZURE.getActiveDirectoryEndpoint())
                    .build();

            AzureProfile profile = new AzureProfile(tenantId, subscriptionId, AzureEnvironment.AZURE);
            manager = ApiManagementManager.configure().withHttpClient(httpClient).authenticate(credential, profile);

            resourceGroup = environment.getAdditionalProperties().get(AzureConstants.AZURE_ENVIRONMENT_RESOURCE_GROUP);
            serviceName = environment.getAdditionalProperties().get(AzureConstants.AZURE_ENVIRONMENT_SERVICE_NAME);

            if (StringUtils.isAnyBlank(tenantId, clientId, clientSecret, subscriptionId, resourceGroup, serviceName)) {
                throw new APIManagementException("Missing required Azure environment configurations");
            }
        } catch (Exception e) {
            throw new APIManagementException("Error occurred while initializing Azure Federated API Key Agent", e);
        }
    }

    @Override
    public CredentialCreationResult createApiKey(FederatedApiKeyContext context) throws APIManagementException {
        if (context == null || StringUtils.isBlank(context.getApiReferenceArtifact())
                || StringUtils.isBlank(context.getApiKeyValue())) {
            throw new APIManagementException("API reference artifact and API key value are required");
        }
        try {
            String externalApiId = extractAzureApiIdFromReferenceArtifact(context.getApiReferenceArtifact());
            String scope = buildApiScope(externalApiId);
            String subscriptionName = generateSubscriptionName(context);
            String displayName = generateDisplayName(context);

            SubscriptionCreateParameters parameters = new SubscriptionCreateParameters()
                    .withScope(scope)
                    .withDisplayName(displayName)
                    .withState(SubscriptionState.ACTIVE)
                    .withAllowTracing(false)
                    .withPrimaryKey(context.getApiKeyValue());

            SubscriptionContract subscription = manager.subscriptions()
                    .createOrUpdate(resourceGroup, serviceName, subscriptionName, parameters);
            
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("subscriptionName", subscription.name());
            metadata.put("primaryKey", subscription.primaryKey());
            metadata.put("secondaryKey", subscription.secondaryKey());
            metadata.put("scope", subscription.scope());
            if (context.getValidityPeriod() != null) {
                metadata.put("requestedValidityPeriod", context.getValidityPeriod());
            }
            if (StringUtils.isNotBlank(context.getPermittedIP())) {
                metadata.put("requestedPermittedIP", context.getPermittedIP());
            }
            if (StringUtils.isNotBlank(context.getPermittedReferer())) {
                metadata.put("requestedPermittedReferer", context.getPermittedReferer());
            }
            
            return CredentialCreationResult.builder()
                    .remoteCredentialId(subscription.name())
                    .credentialType("AZURE_SUBSCRIPTION")
                    .metadata(metadata)
                    .build();
        } catch (Exception e) {
            throw new APIManagementException("Error creating API key in Azure", e);
        }
    }

    @Override
    public void revokeApiKey(FederatedApiKeyContext context) throws APIManagementException {
        if (context == null || StringUtils.isBlank(context.getRemoteApiKeyId())) {
            return;
        }
        try {
            manager.subscriptions().delete(resourceGroup, serviceName, context.getRemoteApiKeyId(), "*");
        } catch (Exception e) {
            throw new APIManagementException("Error revoking API key in Azure", e);
        }
    }

    @Override
    public void applyRateLimitPolicy(FederatedApiKeyContext context, String remotePolicyId) {
        if (log.isDebugEnabled()) {
            log.debug("Skipping rate-limit policy association for Azure API-bound key. keyUuid="
                    + (context != null ? context.getApiKeyUuid() : null));
        }
    }

    @Override
    public void removeRateLimitPolicy(FederatedApiKeyContext context) {
        if (log.isDebugEnabled()) {
            log.debug("Skipping rate-limit policy dissociation for Azure API-bound key. keyUuid="
                    + (context != null ? context.getApiKeyUuid() : null));
        }
    }

    @Override
    public String getGatewayType() {
        return AzureConstants.AZURE_TYPE;
    }

    @Override
    public boolean isApiKeySupport() {
        try {
            GatewayPortalConfiguration featureCatalog = new AzureGatewayConfiguration().getGatewayFeatureCatalog();
            Object supportedFeatures = featureCatalog.getSupportedFeatures();
            if (supportedFeatures instanceof JsonObject) {
                JsonObject featuresJson = (JsonObject) supportedFeatures;
                if (featuresJson.has("apiKeys") && featuresJson.get("apiKeys").isJsonObject()) {
                    JsonObject apiKeys = featuresJson.getAsJsonObject("apiKeys");
                    return apiKeys.has("supported") && apiKeys.get("supported").getAsBoolean();
                }
            }
        } catch (APIManagementException e) {
            log.warn("Error while resolving Azure API key support from GatewayFeatureCatalog", e);
        }
        return false;
    }

    @Override
    public String resolveRemotePolicyId(String remotePolicyReference) throws APIManagementException {
        if (StringUtils.isBlank(remotePolicyReference)) {
            throw new APIManagementException("Remote policy reference cannot be null or empty");
        }
        try {
            JsonObject refJson = JsonParser.parseString(remotePolicyReference).getAsJsonObject();
            if (refJson.has("id") && !refJson.get("id").isJsonNull()) {
                return refJson.get("id").getAsString();
            }
            if (refJson.has("planId") && !refJson.get("planId").isJsonNull()) {
                return refJson.get("planId").getAsString();
            }
            if (refJson.has("raw") && !refJson.get("raw").isJsonNull()) {
                return refJson.get("raw").getAsString();
            }
        } catch (Exception e) {
            log.debug("Failed to parse remote policy reference as JSON, treating as raw value", e);
        }
        return remotePolicyReference;
    }

    @Override
    public List<ExternalSubscriptionPolicy> listRateLimitPolicies(Environment environment)
            throws APIManagementException {
        // Azure API-bound keys don't require separate rate limit policies
        // Rate limiting is configured at the API/Product level in Azure
        return new ArrayList<>();
    }

    private String extractAzureApiIdFromReferenceArtifact(String referenceArtifact) throws APIManagementException {
        try {
            JsonObject refJson = JsonParser.parseString(referenceArtifact).getAsJsonObject();
            if (refJson.has(AzureConstants.AZURE_EXTERNAL_REFERENCE_ID)
                    && !refJson.get(AzureConstants.AZURE_EXTERNAL_REFERENCE_ID).isJsonNull()) {
                String azureId = refJson.get(AzureConstants.AZURE_EXTERNAL_REFERENCE_ID).getAsString();
                if (StringUtils.isNotBlank(azureId)) {
                    return azureId;
                }
            }
            if (refJson.has("name") && !refJson.get("name").isJsonNull()) {
                String name = refJson.get("name").getAsString();
                if (StringUtils.isNotBlank(name)) {
                    return name;
                }
            }
            throw new APIManagementException("Azure API ID not found in reference artifact");
        } catch (Exception e) {
            throw new APIManagementException("Failed to parse Azure reference artifact", e);
        }
    }

    private String buildApiScope(String externalApiId) throws APIManagementException {
        if (StringUtils.isBlank(externalApiId)) {
            throw new APIManagementException("External API ID cannot be null or empty");
        }
        if (externalApiId.startsWith("/subscriptions/") || externalApiId.startsWith("/apis/")) {
            return externalApiId;
        }
        return "/apis/" + externalApiId;
    }

    private String generateSubscriptionName(FederatedApiKeyContext context) {
        String keyUuid = context != null ? context.getApiKeyUuid() : null;
        if (StringUtils.isBlank(keyUuid)) {
            return "wso2-key";
        }
        String sanitized = keyUuid.replaceAll("[^a-zA-Z0-9-]", "-");
        String base = "wso2-key-" + sanitized;
        return base.length() > 80 ? base.substring(0, 80) : base;
    }

    private String generateDisplayName(FederatedApiKeyContext context) {
        if (context == null) {
            return "WSO2 API key";
        }
        if (StringUtils.isNotBlank(context.getApiName()) && StringUtils.isNotBlank(context.getApiKeyName())) {
            return context.getApiName() + " :: " + context.getApiKeyName();
        }
        if (StringUtils.isNotBlank(context.getApiKeyName())) {
            return context.getApiKeyName();
        }
        return "WSO2 API key";
    }
}
