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

package org.wso2.apigee.client;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.gson.JsonObject;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.apigee.client.util.ApigeeAPIUtil;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.FederatedAPIDiscovery;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.DiscoveredAPI;
import org.wso2.carbon.apimgt.api.model.Environment;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.wso2.carbon.apimgt.impl.importexport.ImportExportConstants.DEPLOYMENT_NAME;
import static org.wso2.carbon.apimgt.impl.importexport.ImportExportConstants.DEPLOYMENT_VHOST;
import static org.wso2.carbon.apimgt.impl.importexport.ImportExportConstants.DISPLAY_ON_DEVPORTAL_OPTION;

/**
 * Federated API discovery implementation for Google Apigee (Apigee X / hybrid).
 * <p>
 * This class discovers API proxies deployed on a specific Apigee organization +
 * environment and converts them into {@link DiscoveredAPI} objects that WSO2
 * APIM can import and manage.
 * <p>
 * Authentication is performed via a GCP Service-Account JSON key that is
 * exchanged for an OAuth 2.0 access-token using the
 * {@code google-auth-library-oauth2-http} library.
 */
public class
ApigeeFederatedAPIDiscovery implements FederatedAPIDiscovery {

    private static final Log log = LogFactory.getLog(ApigeeFederatedAPIDiscovery.class);

    private Environment environment;
    private GoogleCredentials credentials;
    private String organization;
    private String apigeeOrganization;
    private String apigeeEnvironment;
    private JsonObject deploymentConfigObject;

    /**
     * Initialise the discovery client.  Called once by the APIM framework when
     * the gateway environment is first loaded.
     *
     * @param environment  the WSO2 Environment model carrying the additional
     *                     properties configured by the admin
     * @param organization the APIM tenant / organization string
     */
    @Override
    public void init(Environment environment, String organization)
            throws APIManagementException {
        log.debug("Initializing Apigee Federated API Discovery for environment: " + environment.getName());
        try {
            this.environment = environment;
            this.organization = organization;

            // Read connection properties from the Environment
            String org = environment.getAdditionalProperties().get(ApigeeConstants.APIGEE_ORGANIZATION);
            if (org == null || org.trim().isEmpty()) {
                // Backward compatibility for previously saved gateway environments.
                // Ignore legacy value when it equals tenant organization (e.g. carbon.super),
                // because that indicates APIM's reserved organization field collision.
                String legacyOrg = environment.getAdditionalProperties().get(ApigeeConstants.APIGEE_ORGANIZATION_LEGACY);
                if (legacyOrg != null && !legacyOrg.trim().isEmpty() && !legacyOrg.trim().equals(organization)) {
                    org = legacyOrg.trim();
                }
            }
            String env = environment.getAdditionalProperties().get(ApigeeConstants.APIGEE_ENVIRONMENT);
            String credentialsJson = environment.getAdditionalProperties()
                    .get(ApigeeConstants.APIGEE_SERVICE_ACCOUNT_CREDENTIALS);

            if (org == null || org.trim().isEmpty() || env == null || credentialsJson == null) {
                throw new APIManagementException(
                        "Missing required Apigee environment configurations. " +
                                "Ensure 'apigee_organization', 'environment', and 'service_account_credentials' are all provided.");
            }

            this.apigeeOrganization = org.trim();
            this.apigeeEnvironment = env;

            // Read the service-account JSON key directly from the credentials string and create scoped credentials
            InputStream stream = new ByteArrayInputStream(credentialsJson.trim().getBytes(StandardCharsets.UTF_8));
            this.credentials = GoogleCredentials.fromStream(stream)
                    .createScoped(Collections.singletonList(ApigeeConstants.APIGEE_OAUTH_SCOPE));
            stream.close();

            // Prepare deployment config object reused when building DiscoveredAPI instances
            this.deploymentConfigObject = new JsonObject();
            deploymentConfigObject.addProperty(DEPLOYMENT_NAME, environment.getName());
            deploymentConfigObject.addProperty(DEPLOYMENT_VHOST, environment.getVhosts().get(0).getHost());
            deploymentConfigObject.addProperty(DISPLAY_ON_DEVPORTAL_OPTION, true);

            log.debug("Initialization completed for Apigee Federated API Discovery, org=" + org + ", env=" + env);

        } catch (APIManagementException e) {
            throw e;
        } catch (Exception e) {
            throw new APIManagementException(
                    "Error occurred while initializing Apigee Federated API Discovery", e);
        }
    }

    /**
     * Discover all API proxies in the configured Apigee organisation.
     * <p>
     * Workflow:
     * <ol>
     *   <li>List all API proxies in the organisation.</li>
     *   <li>For each proxy, check if it is deployed to the target environment.</li>
     *   <li>If deployed: fetch OpenAPI spec and return the API.</li>
     *   <li>If NOT deployed: create a minimal API with empty endpoints and return it.</li>
     *   <li>This ensures WSO2 tracks deployment status changes via the reference artifact.</li>
     * </ol>
     * <p>
     * The reference artifact includes a "deployed" field so that deployment status
     * changes (deployed → undeployed → redeployed) are detected as updates.
     * This prevents duplicate APIs when redeploying.
     */
    @Override
    public List<DiscoveredAPI> discoverAPI() {
        List<DiscoveredAPI> retrievedAPIs = new ArrayList<>();
        try {
            // Ensure access token is valid.
            // refreshIfExpired() can throw on network failure (UnresolvedAddressException, etc.).
            // In that case we return an empty list — the framework will retain its previously
            // stored API records and will NOT treat them as new on the next run (provided
            // isAPIUpdated handles null existingReferenceArtifact safely, which it does below).
            try {
                credentials.refreshIfExpired();
            } catch (Exception e) {
                log.warn("Could not refresh GCP credentials (network issue?); "
                        + "skipping discovery cycle: " + e.getMessage());
                return retrievedAPIs; // return empty — framework keeps existing records
            }
            String accessToken = credentials.getAccessToken().getTokenValue();

            String org = this.apigeeOrganization;

            // 1. List all API proxies (returns empty list on network error — see ApigeeAPIUtil)
            List<String> proxyNames = ApigeeAPIUtil.listApiProxies(org, accessToken);

            for (String proxyName : proxyNames) {
                try {
                    // 2. Check if the proxy is deployed to the configured environment
                    boolean deployed = ApigeeAPIUtil.isProxyDeployedToEnvironment(
                            org, proxyName, apigeeEnvironment, accessToken);

                    String revision;
                    String apiDefinition;
                    String basepath;

                    if (deployed) {
                        // ---------------------------------------------------------------
                        // DEPLOYED PROXY: Full discovery with actual OpenAPI spec
                        // ---------------------------------------------------------------
                        revision = ApigeeAPIUtil.getLatestDeployedRevision(
                                org, proxyName, apigeeEnvironment, accessToken);

                        // Attempt to retrieve an OpenAPI spec; fall back to a generated stub
                        apiDefinition = ApigeeAPIUtil.getApiProxyOpenAPISpec(
                                org, proxyName, revision, environment, accessToken);

                        if (apiDefinition == null) {
                            log.debug("Skipping proxy '" + proxyName + "' because OpenAPI spec could not be constructed.");
                            continue;
                        }

                        // Get revision details to extract basepath
                        JsonObject revisionDetails = ApigeeAPIUtil.getApiProxyRevisionDetails(
                                org, proxyName, revision, accessToken);
                        basepath = ApigeeAPIUtil.getProxyBasepath(revisionDetails);

                    } else {
                        // ---------------------------------------------------------------
                        // UNDEPLOYED PROXY: Minimal API with empty endpoints
                        // ---------------------------------------------------------------
                        // Use revision "0" to indicate undeployed state
                        revision = "0";
                        
                        // Build minimal OpenAPI spec for undeployed proxy
                        apiDefinition = ApigeeAPIUtil.buildUndeployedOpenAPISpec(
                                proxyName, environment);
                        
                        // Use default basepath
                        basepath = "/" + proxyName.toLowerCase();
                    }

                    // Get proxy metadata
                    JsonObject proxyMetadata = ApigeeAPIUtil.getApiProxyMetadata(
                            org, proxyName, accessToken);

                    // Convert to WSO2 API model
                    API api = ApigeeAPIUtil.proxyToAPI(
                            proxyName, proxyMetadata, apiDefinition, organization, environment,
                            this.apigeeOrganization, basepath, deployed);
                    
                    log.debug("Discovered API: '" + api.getId().getApiName() + "' (UUID: " + api.getUuid() 
                            + ", deployed: " + deployed + ")");

                    // Build reference artifact including deployment status
                    String referenceArtifact = ApigeeAPIUtil.createReferenceArtifact(
                            proxyName, revision, apiDefinition, deployed);

                    DiscoveredAPI discoveredAPI = new DiscoveredAPI(api, referenceArtifact);
                    retrievedAPIs.add(discoveredAPI);

                } catch (Exception e) {
                    log.error("Error discovering Apigee proxy '" + proxyName + "': " + e.getMessage(), e);
                    // Continue with next proxy instead of failing completely
                }
            }
        } catch (Exception e) {
            log.error("Error during Apigee API discovery: " + e.getMessage(), e);
        }

        return retrievedAPIs;
    }

    /**
     * Compares two reference artifact strings to determine whether the remote
     * API proxy has changed since the last sync.
     * <p>
     * A null {@code existingReferenceArtifact} means the framework has no prior record
     * for this API (e.g. first discovery, or state lost after a restart). Returning
     * {@code true} here causes an update/re-import rather than a fresh create, which
     * prevents the framework from appending the gateway name to avoid context collisions.
     */
    @Override
    public boolean isAPIUpdated(String existingReferenceArtifact, String newReferenceArtifact) {
        if (existingReferenceArtifact == null) {
            return true;
        }
        return !existingReferenceArtifact.equals(newReferenceArtifact);
    }
}