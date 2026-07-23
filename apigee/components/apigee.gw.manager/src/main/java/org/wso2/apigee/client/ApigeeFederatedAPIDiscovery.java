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
            String vhost = (environment.getVhosts() != null && !environment.getVhosts().isEmpty())
                    ? environment.getVhosts().get(0).getHost()
                    : "";
            deploymentConfigObject.addProperty(DEPLOYMENT_VHOST, vhost);
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
     * This prevents duplicate APIs when redeploying.
     */
    @Override
    public List<DiscoveredAPI> discoverAPI() {
        List<DiscoveredAPI> retrievedAPIs = java.util.Collections.synchronizedList(new ArrayList<>());
        try {
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

            proxyNames.parallelStream().forEach(proxyName -> {
                try {
                    DiscoveredAPI discoveredAPI = processProxy(org, proxyName, accessToken, true);
                    if (discoveredAPI != null) {
                        log.debug("Discovered API: '" + discoveredAPI.getApi().getId().getApiName()
                                + "' (UUID: " + discoveredAPI.getApi().getUuid() + ")");
                        retrievedAPIs.add(discoveredAPI);
                    }
                } catch (Exception e) {
                    log.error("Error discovering Apigee proxy '" + proxyName + "': " + e.getMessage(), e);
                    // Continue with next proxy instead of failing completely
                }
            });
        } catch (Exception e) {
            log.error("Error during Apigee API discovery: " + e.getMessage(), e);
        }

        return new ArrayList<>(retrievedAPIs);
    }

    @Override
    public List<DiscoveredAPI> discoverMetadata() {
        log.debug("[LOGGING] Apigee Connector: discoverMetadata() called. Fetching metadata for Apigee API proxies in parallel.");
        List<DiscoveredAPI> retrievedAPIs = java.util.Collections.synchronizedList(new ArrayList<>());
        try {
            try {
                credentials.refreshIfExpired();
            } catch (Exception e) {
                log.warn("Could not refresh GCP credentials (network issue?); "
                         + "skipping discovery cycle: " + e.getMessage());
                return retrievedAPIs;
            }
            String accessToken = credentials.getAccessToken().getTokenValue();
            String org = this.apigeeOrganization;

            List<String> proxyNames = ApigeeAPIUtil.listApiProxies(org, accessToken);

            proxyNames.parallelStream().forEach(proxyName -> {
                try {
                    // fetchSpec=false: metadata crawl skips the heavy OpenAPI download
                    DiscoveredAPI discoveredAPI = processProxy(org, proxyName, accessToken, false);
                    if (discoveredAPI != null) {
                        retrievedAPIs.add(discoveredAPI);
                    }
                } catch (Exception e) {
                    log.error("Error discovering Apigee proxy metadata '"
                              + proxyName + "': " + e.getMessage(), e);
                }
            });
        } catch (Exception e) {
            log.error("Error during Apigee API metadata discovery: " + e.getMessage(), e);
        }

        return new ArrayList<>(retrievedAPIs);
    }

    @Override
    public List<DiscoveredAPI> discoverAPI(List<String> apiIds) {
        log.debug("[LOGGING] Apigee Connector: discoverAPI(List<String> apiIds) called with IDs: " + apiIds);
        List<DiscoveredAPI> retrievedAPIs = java.util.Collections.synchronizedList(new ArrayList<>());
        try {
            try {
                credentials.refreshIfExpired();
            } catch (Exception e) {
                log.warn("Could not refresh GCP credentials (network issue?); "
                         + "skipping discovery cycle: " + e.getMessage());
                return retrievedAPIs;
            }
            String accessToken = credentials.getAccessToken().getTokenValue();
            String org = this.apigeeOrganization;

            List<String> proxyNames = ApigeeAPIUtil.listApiProxies(org, accessToken);

            proxyNames.parallelStream().forEach(proxyName -> {
                try {
                    String normalizedProxyName = proxyName == null ? "" : proxyName.trim();
                    String normalizedOrganization = organization == null ? "" : organization.trim();
                    String uuidSeed = normalizedProxyName + "-" + normalizedOrganization;
                    String deterministicUuid = java.util.UUID.nameUUIDFromBytes(
                            uuidSeed.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
                    String compositeKey = proxyName + ":" + ApigeeConstants.DEFAULT_VERSION;

                    if (apiIds.contains(deterministicUuid) || apiIds.contains(compositeKey)) {
                        log.debug("[LOGGING] Apigee Connector: MATCH FOUND. Fetching full specification (OAS definition) for Apigee proxy: " + proxyName);
                        DiscoveredAPI discoveredAPI = processProxy(org, proxyName, accessToken, true);
                        if (discoveredAPI != null) {
                            retrievedAPIs.add(discoveredAPI);
                        }
                    }
                } catch (Exception e) {
                    log.error("Error discovering Apigee proxy '" + proxyName + "': " + e.getMessage(), e);
                }
            });
        } catch (Exception e) {
            log.error("Error during Apigee API discovery: " + e.getMessage(), e);
        }

        return new ArrayList<>(retrievedAPIs);
    }

    /**
     * Processes a single Apigee proxy and maps it to a {@link DiscoveredAPI}.
     * <p>
     * Returns {@code null} (without throwing) in two expected non-error cases:
     * <ul>
     *   <li>The proxy is not deployed to the configured Apigee environment.</li>
     *   <li>The OpenAPI spec could not be constructed (only when {@code fetchSpec=true}).</li>
     * </ul>
     *
     * @param org         Apigee organisation ID
     * @param proxyName   name of the API proxy
     * @param accessToken GCP OAuth 2.0 bearer token
     * @param fetchSpec   {@code true} to download the full OpenAPI spec (used by full discovery);
     *                    {@code false} to skip the spec download and use an empty string instead
     *                    (used by metadata-only discovery)
     * @return a fully populated {@link DiscoveredAPI}, or {@code null} if the proxy should be skipped
     * @throws Exception if an unexpected error occurs during any Apigee API call
     */
    private DiscoveredAPI processProxy(String org, String proxyName,
                                       String accessToken, boolean fetchSpec) throws Exception {
        // 2. Check if the proxy is deployed to the configured environment
        boolean deployed = ApigeeAPIUtil.isProxyDeployedToEnvironment(
                org, proxyName, apigeeEnvironment, accessToken);

        if (!deployed) {
            // Skip undeployed proxies — WSO2's FederatedAPIDiscoveryRunner will
            // automatically call deleteDeployment for any API that disappears
            // from the discovered list, keeping both systems in sync.
            log.debug("Skipping proxy '" + proxyName + "' because it is not deployed to environment: "
                    + apigeeEnvironment);
            return null;
        }

        // ---------------------------------------------------------------
        // DEPLOYED PROXY: Full discovery with actual OpenAPI spec
        // ---------------------------------------------------------------
        String revision = ApigeeAPIUtil.getLatestDeployedRevision(
                org, proxyName, apigeeEnvironment, accessToken);

        String apiDefinition = "";
        if (fetchSpec) {
            // Attempt to retrieve an OpenAPI spec; fall back to a generated stub
            apiDefinition = ApigeeAPIUtil.getApiProxyOpenAPISpec(
                    org, proxyName, revision, environment, accessToken);

        }

        // Get revision details to extract basepath
        JsonObject revisionDetails = ApigeeAPIUtil.getApiProxyRevisionDetails(
                org, proxyName, revision, accessToken);
        String basepath = ApigeeAPIUtil.getProxyBasepath(revisionDetails);

        // Get proxy metadata
        JsonObject proxyMetadata = ApigeeAPIUtil.getApiProxyMetadata(
                org, proxyName, accessToken);

        // Convert to WSO2 API model
        API api = ApigeeAPIUtil.proxyToAPI(
                proxyName, proxyMetadata, apiDefinition, organization, environment,
                this.apigeeOrganization, basepath, true);

        // Build reference artifact
        String referenceArtifact = ApigeeAPIUtil.createReferenceArtifact(
                proxyName, revision, apiDefinition, true);

        return new DiscoveredAPI(api, referenceArtifact);
    }

    @Override
    public boolean isAPIUpdated(String existingReferenceArtifact, String newReferenceArtifact) {
        if (existingReferenceArtifact == null || newReferenceArtifact == null) {
            return true;
        }
        try {
            com.google.gson.JsonObject existingJson = com.google.gson.JsonParser
                    .parseString(existingReferenceArtifact).getAsJsonObject();
            com.google.gson.JsonObject newJson = com.google.gson.JsonParser
                    .parseString(newReferenceArtifact).getAsJsonObject();

            if (existingJson.has("proxyName") && newJson.has("proxyName")
                    && existingJson.has("revision") && newJson.has("revision")
                    && existingJson.has("deployed") && newJson.has("deployed")) {

                String existingProxyName = existingJson.get("proxyName").getAsString();
                String newProxyName = newJson.get("proxyName").getAsString();
                String existingRevision = existingJson.get("revision").getAsString();
                String newRevision = newJson.get("revision").getAsString();
                boolean existingDeployed = existingJson.get("deployed").getAsBoolean();
                boolean newDeployed = newJson.get("deployed").getAsBoolean();

                if (!existingProxyName.equals(newProxyName)
                        || !existingRevision.equals(newRevision)
                        || existingDeployed != newDeployed) {
                    return true;
                }

                // Retrieve flags, defaulting to false if missing (for backward compatibility)
                boolean existingIsMetadataOnly = existingJson.has("isMetadataOnly")
                        && existingJson.get("isMetadataOnly").getAsBoolean();
                boolean newIsMetadataOnly = newJson.has("isMetadataOnly")
                        && newJson.get("isMetadataOnly").getAsBoolean();

                boolean existingIsFallback = existingJson.has("isFallback")
                        && existingJson.get("isFallback").getAsBoolean();
                boolean newIsFallback = newJson.has("isFallback")
                        && newJson.get("isFallback").getAsBoolean();

                // 1. If the new scan has no spec (metadata-only discovery), keep the existing spec in WSO2
                if (newIsMetadataOnly) {
                    return false;
                }

                String existingHash = existingJson.has("specHash")
                        ? existingJson.get("specHash").getAsString() : "0";
                String newHash = newJson.has("specHash")
                        ? newJson.get("specHash").getAsString() : "0";

                // 2. If the existing artifact had no spec but the new artifact has a spec, trigger import
                if ((existingIsMetadataOnly || "0".equals(existingHash)) && !newIsMetadataOnly) {
                    return true;
                }

                // 3. If the new spec is a wildcard fallback but the existing spec was a real spec,
                // do not overwrite the real spec (prevents network drops from destroying specs)
                if (newIsFallback && !existingIsFallback && !existingIsMetadataOnly) {
                    return false;
                }

                // 4. Compare hashes
                if (!existingHash.equals(newHash)) {
                    return true;
                }

                return false;
            }
            return !existingReferenceArtifact.equals(newReferenceArtifact);
        } catch (Exception e) {
            log.error("Error parsing Apigee reference artifact", e);
            return !existingReferenceArtifact.equals(newReferenceArtifact);
        }
    }
}