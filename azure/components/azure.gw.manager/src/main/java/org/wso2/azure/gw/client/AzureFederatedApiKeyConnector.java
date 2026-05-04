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
import org.wso2.carbon.apimgt.api.FederatedApiKeyConnector;
import org.wso2.carbon.apimgt.api.model.Environment;

import java.util.Map;

/**
 * Azure implementation of federated API key management.
 * This follows API-bound key provisioning using Azure APIM subscriptions scoped to a single API.
 */
public class AzureFederatedApiKeyConnector implements FederatedApiKeyConnector {

    private static final Log log = LogFactory.getLog(AzureFederatedApiKeyConnector.class);
    private static final String SUBSCRIPTION_NAME = "subscriptionName";

    public static final String PROP_API_KEY_NAME = "apiKeyName";
    public static final String PROP_API_NAME = "apiName";

    private String resourceGroup;
    private String serviceName;
    private ApiManagementManager manager;

    /**
     * Initializes the Azure API Management client from the environment service-principal configuration.
     */
    @Override
    public void init(Environment environment) throws APIManagementException {
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
            throw new APIManagementException("Error occurred while initializing Azure Federated API Key Connector", e);
        }
    }

    /**
     * Creates an Azure APIM subscription scoped to the referenced API and returns the subscription name.
     */
    @Override
    public String createApiKey(String apiKeyUuid, String apiKeyValue,
                               String apiReferenceArtifact, String localPolicyId,
                               Map<String, String> properties)
            throws APIManagementException {
        if (StringUtils.isBlank(apiReferenceArtifact) || StringUtils.isBlank(apiKeyValue)) {
            throw new APIManagementException("API reference artifact and API key value are required");
        }
        try {
            String externalApiId = extractAzureApiIdFromReferenceArtifact(apiReferenceArtifact);
            String scope = buildApiScope(externalApiId);
            String subscriptionName = generateSubscriptionName(apiKeyUuid);
            String displayName = generateDisplayName(properties);

            SubscriptionCreateParameters parameters = new SubscriptionCreateParameters()
                    .withScope(scope)
                    .withDisplayName(displayName)
                    .withState(SubscriptionState.ACTIVE)
                    .withAllowTracing(false)
                    .withPrimaryKey(apiKeyValue);

            SubscriptionContract subscription = manager.subscriptions()
                    .createOrUpdate(resourceGroup, serviceName, subscriptionName, parameters);

            return buildApiKeyReferenceArtifact(subscription.name(), externalApiId);
        } catch (Exception e) {
            throw new APIManagementException("Error creating API key in Azure", e);
        }
    }

    /**
     * Replaces an Azure APIM subscription key in place and returns the retained subscription name.
     */
    @Override
    public String replaceApiKey(String apiKeyReferenceArtifact, String newApiKeyValue, Map<String, String> properties)
            throws APIManagementException {
        if (StringUtils.isBlank(newApiKeyValue)) {
            throw new APIManagementException("API key value is required");
        }
        try {
            String externalApiId = resolveExternalApiId(apiKeyReferenceArtifact);
            String scope = buildApiScope(externalApiId);
            String subscriptionName = StringUtils.defaultIfBlank(resolveSubscriptionName(apiKeyReferenceArtifact),
                    generateSubscriptionName(null));
            String displayName = generateDisplayName(properties);

            SubscriptionCreateParameters parameters = new SubscriptionCreateParameters()
                    .withScope(scope)
                    .withDisplayName(displayName)
                    .withState(SubscriptionState.ACTIVE)
                    .withAllowTracing(false)
                    .withPrimaryKey(newApiKeyValue);

            SubscriptionContract subscription = manager.subscriptions()
                    .createOrUpdate(resourceGroup, serviceName, subscriptionName, parameters);

            return buildApiKeyReferenceArtifact(subscription.name(), externalApiId);
        } catch (Exception e) {
            throw new APIManagementException("Error replacing API key in Azure", e);
        }
    }

    /**
     * Deletes the Azure APIM subscription identified by the stored connector-owned reference artifact.
     */
    @Override
    public void revokeApiKey(String apiKeyReferenceArtifact) throws APIManagementException {
        String subscriptionName = resolveSubscriptionName(apiKeyReferenceArtifact);
        if (StringUtils.isBlank(subscriptionName)) {
            return;
        }
        try {
            manager.subscriptions().delete(resourceGroup, serviceName, subscriptionName, "*");
        } catch (Exception e) {
            throw new APIManagementException("Error revoking API key in Azure", e);
        }
    }

    /**
     * No-op because Azure models the API-key scope on the subscription itself, not as a separate plan association.
     */
    @Override
    public void associateSubscriptionPolicy(String apiKeyReferenceArtifact, String localPolicyId) {
        if (log.isDebugEnabled()) {
            log.debug("Skipping rate-limit policy association for Azure API-bound key");
        }
    }

    /**
     * No-op because Azure has no separate remote plan association to remove for API-bound subscriptions.
     */
    @Override
    public void dissociateSubscriptionPolicy(String apiKeyReferenceArtifact, String localPolicyId) {
        if (log.isDebugEnabled()) {
            log.debug("Skipping rate-limit policy dissociation for Azure API-bound key");
        }
    }

    /**
     * Returns the gateway type handled by this connector.
     */
    @Override
    public String getGatewayType() {
        return AzureConstants.AZURE_TYPE;
    }

    /**
     * Indicates that Azure federated API-key provisioning is supported.
     */
    @Override
    public boolean isApiKeySupport() {
        return true;
    }

    /**
     * Extracts the full Azure ARM API resource ID from the connector-owned API reference artifact.
     */
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
            throw new APIManagementException("Azure API ID not found in reference artifact");
        } catch (Exception e) {
            throw new APIManagementException("Failed to parse Azure reference artifact", e);
        }
    }

    /**
     * Builds the Azure subscription scope from the full ARM API resource ID persisted in the reference artifact.
     */
    private String buildApiScope(String externalApiId) throws APIManagementException {
        if (StringUtils.isBlank(externalApiId)) {
            throw new APIManagementException("External API ID cannot be null or empty");
        }
        if (!externalApiId.startsWith("/subscriptions/")) {
            throw new APIManagementException("Azure API reference artifact must contain a full ARM resource ID");
        }
        return externalApiId;
    }

    /**
     * Builds a stable Azure subscription name from the local API-key UUID.
     */
    private String generateSubscriptionName(String apiKeyUuid) {
        if (StringUtils.isBlank(apiKeyUuid)) {
            return "wso2-key";
        }
        String sanitized = apiKeyUuid.replaceAll("[^a-zA-Z0-9-]", "-");
        String base = "wso2-key-" + sanitized;
        return base.length() > 80 ? base.substring(0, 80) : base;
    }

    /**
     * Builds a human-readable Azure subscription display name from available properties.
     */
    private String generateDisplayName(Map<String, String> properties) {
        if (properties == null) {
            return "WSO2 API key";
        }
        String apiName = properties.get(PROP_API_NAME);
        String apiKeyName = properties.get(PROP_API_KEY_NAME);
        if (StringUtils.isNotBlank(apiName) && StringUtils.isNotBlank(apiKeyName)) {
            return apiName + " :: " + apiKeyName;
        }
        if (StringUtils.isNotBlank(apiKeyName)) {
            return apiKeyName;
        }
        return "WSO2 API key";
    }

    private String buildApiKeyReferenceArtifact(String subscriptionName, String externalApiId) {
        JsonObject referenceArtifact = new JsonObject();
        referenceArtifact.addProperty(SUBSCRIPTION_NAME, subscriptionName);
        referenceArtifact.addProperty(AzureConstants.AZURE_EXTERNAL_REFERENCE_ID, externalApiId);
        return referenceArtifact.toString();
    }

    private String resolveSubscriptionName(String apiKeyReferenceArtifact) throws APIManagementException {
        return resolveApiKeyReferenceField(apiKeyReferenceArtifact, SUBSCRIPTION_NAME);
    }

    private String resolveExternalApiId(String apiKeyReferenceArtifact) throws APIManagementException {
        return resolveApiKeyReferenceField(apiKeyReferenceArtifact, AzureConstants.AZURE_EXTERNAL_REFERENCE_ID);
    }

    private String resolveApiKeyReferenceField(String apiKeyReferenceArtifact, String fieldName)
            throws APIManagementException {
        if (StringUtils.isBlank(apiKeyReferenceArtifact)) {
            return null;
        }
        try {
            JsonObject referenceArtifact = JsonParser.parseString(apiKeyReferenceArtifact).getAsJsonObject();
            if (referenceArtifact.has(fieldName) && !referenceArtifact.get(fieldName).isJsonNull()) {
                String value = referenceArtifact.get(fieldName).getAsString();
                if (StringUtils.isNotBlank(value)) {
                    return value;
                }
            }
            throw new APIManagementException("Azure API key reference artifact must contain " + fieldName);
        } catch (APIManagementException e) {
            throw e;
        } catch (Exception e) {
            throw new APIManagementException("Invalid Azure API key reference artifact", e);
        }
    }
}
