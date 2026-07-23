/*
 *
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
 *
 */

package org.wso2.azure.gw.client;

import com.azure.core.credential.TokenCredential;
import com.azure.core.http.HttpClient;
import com.azure.core.http.netty.NettyAsyncHttpClientBuilder;
import com.azure.core.http.rest.PagedIterable;
import com.azure.core.management.AzureEnvironment;
import com.azure.core.management.profile.AzureProfile;
import com.azure.core.util.Context;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.azure.resourcemanager.apimanagement.ApiManagementManager;
import com.azure.resourcemanager.apimanagement.models.ApiContract;
import com.azure.resourcemanager.apimanagement.models.ApiRevisionContract;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.azure.gw.client.util.AzureAPIUtil;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.FederatedAPIDiscovery;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.DiscoveredAPI;
import org.wso2.carbon.apimgt.api.model.Environment;

import java.util.ArrayList;
import java.util.List;

/**
 * This class provides the implementation for the discovery of APIs from the Azure API Management Gateway.
 */
public class AzureFederatedAPIDiscovery implements FederatedAPIDiscovery {

    private static final Log log = LogFactory.getLog(AzureFederatedAPIDiscovery.class);

    private Environment environment;
    private String organization;

    private String resourceGroup;
    private String serviceName;
    private String hostName;
    private ApiManagementManager manager;
    HttpClient httpClient;

    @Override
    public void init(Environment environment, String organization)
            throws APIManagementException {
        if (log.isDebugEnabled()) {
            log.debug("Initializing Azure Gateway Deployer for environment: " + environment.getName());
        }
        try {

            String tenantId = environment.getAdditionalProperties().get(AzureConstants.AZURE_ENVIRONMENT_TENANT_ID);
            String clientId = environment.getAdditionalProperties().get(AzureConstants.AZURE_ENVIRONMENT_CLIENT_ID);
            String clientSecret = environment.getAdditionalProperties()
                    .get(AzureConstants.AZURE_ENVIRONMENT_CLIENT_SECRET);
            String subscriptionId = environment.getAdditionalProperties()
                    .get(AzureConstants.AZURE_ENVIRONMENT_SUBSCRIPTION_ID);

            httpClient = new NettyAsyncHttpClientBuilder().build();

            TokenCredential cred = new ClientSecretCredentialBuilder()
                    .httpClient(httpClient)
                    .tenantId(tenantId)
                    .clientId(clientId)
                    .clientSecret(clientSecret)
                    .authorityHost(AzureEnvironment.AZURE.getActiveDirectoryEndpoint())
                    .build();

            AzureProfile profile = new AzureProfile(tenantId, subscriptionId, AzureEnvironment.AZURE);
            manager = ApiManagementManager.configure().withHttpClient(httpClient).authenticate(cred, profile);

            resourceGroup = environment.getAdditionalProperties().get(AzureConstants.AZURE_ENVIRONMENT_RESOURCE_GROUP);
            serviceName = environment.getAdditionalProperties().get(AzureConstants.AZURE_ENVIRONMENT_SERVICE_NAME);
            hostName = environment.getAdditionalProperties().get(AzureConstants.AZURE_ENVIRONMENT_HOSTNAME);

            this.environment = environment;
            this.organization = organization;

            if (tenantId == null || clientId == null || clientSecret == null || subscriptionId == null
            || resourceGroup == null || serviceName == null || hostName == null) {
                throw new APIManagementException("Missing required Azure environment configurations");
            }

            if (log.isDebugEnabled()) {
                log.debug("Initialization completed Azure Gateway Discovery for environment: " + environment.getName());
            }

        } catch (Exception e) {
            throw new APIManagementException("Error occurred while initializing Azure Gateway Deployer", e);
        }
    }

    @Override
    public List<DiscoveredAPI> discoverAPI() {
        PagedIterable<ApiContract> apis = manager.apis().listByService(resourceGroup, serviceName, "isCurrent eq true",
                        null, /* top */
                        null, /* skip */
                        null, /* tags */
                        null, /* expandApiVersionSet */
                        Context.NONE
                );
        List<DiscoveredAPI> retrievedAPIs = java.util.Collections.synchronizedList(new ArrayList<>());
        apis.stream().parallel().forEach(api -> {
            try {
                // Get API
                String apiDefinition = AzureAPIUtil.getRestApiDefinition(manager, httpClient, api);
                API apiArtifact = AzureAPIUtil.restAPItoAPI(api, apiDefinition, organization, environment);

                // Get current revision
                PagedIterable<ApiRevisionContract> revisions = manager.apiRevisions().listByService(resourceGroup,
                        serviceName, api.name(), "isCurrent eq true", /* top */ null, /* skip */ null, Context.NONE);
                ApiRevisionContract revisionContract = revisions.stream().findFirst().orElse(null);
                if (revisionContract == null) {
                    throw new APIManagementException("Current API Revision not found for api: " + api.name());
                }
                String referenceArtifact = AzureAPIUtil.generateReferenceArtifact(apiArtifact, api, null,
                        revisionContract);

                retrievedAPIs.add(new DiscoveredAPI(apiArtifact, referenceArtifact));

            } catch (APIManagementException e) {
                log.error("Error retrieving API definition for API: " + api.name(), e);
            }
        });
        return new ArrayList<>(retrievedAPIs);
    }

    @Override
    public List<DiscoveredAPI> discoverMetadata() {
        log.debug("[LOGGING] Azure Connector: discoverMetadata() called. "
                + "Fetching metadata for Azure APIs in parallel.");
        PagedIterable<ApiContract> apis = manager.apis()
                .listByService(resourceGroup, serviceName, "isCurrent eq true",
                        null, /* top */
                        null, /* skip */
                        null, /* tags */
                        null, /* expandApiVersionSet */
                        Context.NONE
                );
        List<DiscoveredAPI> retrievedAPIs = java.util.Collections.synchronizedList(new ArrayList<>());
        apis.stream().parallel().forEach(api -> {
            try {
                // Get API - Skip fetching heavy spec definition
                String apiDefinition = "";
                API apiArtifact = AzureAPIUtil.restAPItoAPI(api, apiDefinition, organization, environment);

                // Get current revision
                PagedIterable<ApiRevisionContract> revisions = manager.apiRevisions()
                        .listByService(resourceGroup, serviceName, api.name(), "isCurrent eq true",
                                null, null, Context.NONE);
                ApiRevisionContract revisionContract = revisions.stream().findFirst().orElse(null);
                if (revisionContract == null) {
                    throw new APIManagementException("Current API Revision not found for api: " + api.name());
                }
                String referenceArtifact = AzureAPIUtil.generateReferenceArtifact(apiArtifact, api, null,
                        revisionContract);

                retrievedAPIs.add(new DiscoveredAPI(apiArtifact, referenceArtifact));

            } catch (Exception e) {
                log.error("Error retrieving API metadata for API: " + api.name(), e);
            }
        });
        return new ArrayList<>(retrievedAPIs);
    }

    @Override
    public List<DiscoveredAPI> discoverAPI(List<String> apiIds) {
        log.debug("[LOGGING] Azure Connector: discoverAPI(List<String> apiIds) "
                + "called in parallel with IDs: " + apiIds);
        PagedIterable<ApiContract> apis = manager.apis()
                .listByService(resourceGroup, serviceName, "isCurrent eq true",
                        null, /* top */
                        null, /* skip */
                        null, /* tags */
                        null, /* expandApiVersionSet */
                        Context.NONE
                );
        List<DiscoveredAPI> retrievedAPIs = java.util.Collections.synchronizedList(new ArrayList<>());
        apis.stream().parallel().forEach(api -> {
            try {
                // Calculate deterministic UUID
                String uuidSeed = api.name() + "-" + organization;
                String deterministicUuid = java.util.UUID.nameUUIDFromBytes(
                        uuidSeed.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
                String version = api.apiVersion() != null ? api.apiVersion() : "1.0.0";
                String compositeKey = api.displayName() + ":" + version;

                if (apiIds.contains(deterministicUuid) || apiIds.contains(compositeKey)) {
                    log.debug("[LOGGING] Azure Connector: MATCH FOUND. "
                            + "Fetching spec for Azure API: " + api.name());
                    // Get API Definition only if it is in the requested apiIds
                    String apiDefinition = AzureAPIUtil.getRestApiDefinition(manager, httpClient, api);
                    API apiArtifact = AzureAPIUtil.restAPItoAPI(api, apiDefinition, organization, environment);

                    // Get current revision
                    PagedIterable<ApiRevisionContract> revisions = manager.apiRevisions()
                            .listByService(resourceGroup, serviceName, api.name(), "isCurrent eq true",
                                    null, null, Context.NONE);
                    ApiRevisionContract revisionContract = revisions.stream().findFirst().orElse(null);
                    if (revisionContract == null) {
                        throw new APIManagementException("Current API Revision not found for api: " + api.name());
                    }
                    String referenceArtifact = AzureAPIUtil.generateReferenceArtifact(apiArtifact, api, null,
                            revisionContract);

                    retrievedAPIs.add(new DiscoveredAPI(apiArtifact, referenceArtifact));
                }
            } catch (Exception e) {
                log.error("Error retrieving API definition for API: " + api.name(), e);
            }
        });
        return new ArrayList<>(retrievedAPIs);
    }

    @Override
    public boolean isAPIUpdated(String existingReferenceArtifact, String newReferenceArtifact) {
        if (existingReferenceArtifact == null || newReferenceArtifact == null) {
            if (existingReferenceArtifact == null) {
                log.error("Existing reference artifact null when checking if discovered Azure API is updated.");
            }
            if (newReferenceArtifact == null) {
                log.error("New reference artifact null when checking if discovered Azure API is updated.");
            }
            return true;
        }
        JsonObject existingArtifact = JsonParser.parseString(existingReferenceArtifact).getAsJsonObject();
        JsonObject newArtifact = JsonParser.parseString(newReferenceArtifact).getAsJsonObject();

        long existingRevisionCreatedTime = existingArtifact
                .get(AzureConstants.AZURE_EXTERNAL_REFERENCE_CREATED_TIME_EPOCH).getAsLong();
        long newRevisionCreatedTime = newArtifact
                .get(AzureConstants.AZURE_EXTERNAL_REFERENCE_CREATED_TIME_EPOCH).getAsLong();
        return existingRevisionCreatedTime != newRevisionCreatedTime;
    }
}
