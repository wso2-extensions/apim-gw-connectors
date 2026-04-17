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

package org.wso2.apigee.client.util;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.apigee.client.ApigeeConstants;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.APIIdentifier;
import org.wso2.carbon.apimgt.api.model.Environment;

import org.wso2.carbon.apimgt.impl.APIConstants;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Utility class for interacting with the Google Apigee Management API v1.
 * <p>
 * All HTTP calls use the Java 11 {@link HttpClient}.  Every public method
 * requires a valid OAuth 2.0 access-token obtained from a GCP
 * Service-Account.
 */
public class ApigeeAPIUtil {

    private static final Log log = LogFactory.getLog(ApigeeAPIUtil.class);

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private static final Gson GSON = new Gson();

    private ApigeeAPIUtil(){
        // Utility class — prevent instantiation
    }

    // -----------------------------------------------------------------------
    //  Discovery helpers (used by ApigeeFederatedAPIDiscovery)
    // -----------------------------------------------------------------------

    /**
     * Lists all API proxy names in the given Apigee organization.
     * <p>
     * GET https://apigee.googleapis.com/v1/organizations/{org}/apis
     *
     * @param org         Apigee organization (GCP project ID)
     * @param accessToken Bearer token
     * @return list of proxy names
     */
    public static List<String> listApiProxies(String org, String accessToken) throws APIManagementException {
        String url = ApigeeConstants.APIGEE_MGMT_API_BASE
                + "/organizations/" + org + "/apis";
        String responseBody;
        try {
            responseBody = executeGet(url, accessToken);
        } catch (Exception e) {
            log.warn("Network error listing Apigee proxies for org '" + org
                    + "'; returning empty list: " + e.getMessage());
            return new ArrayList<>();
        }
        List<String> names = new ArrayList<>();

        JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
        if (json.has(ApigeeConstants.JSON_KEY_PROXIES)) {
            JsonArray proxies = json.getAsJsonArray(ApigeeConstants.JSON_KEY_PROXIES);
            for (JsonElement el : proxies) {
                JsonObject proxy = el.getAsJsonObject();
                names.add(proxy.get(ApigeeConstants.JSON_KEY_NAME).getAsString());
            }
        }
        return names;
    }

    /**
     * Checks whether a given API proxy has at least one revision deployed to
     * the specified Apigee environment.
     * <p>
     * GET https://apigee.googleapis.com/v1/organizations/{org}/apis/{api}/deployments
     */
    public static boolean isProxyDeployedToEnvironment(String org, String proxyName,
                                                       String environment, String accessToken)
            throws APIManagementException {
        String url = ApigeeConstants.APIGEE_MGMT_API_BASE
                + "/organizations/" + org + "/apis/" + proxyName + "/deployments";
        String responseBody;
        try {
            responseBody = executeGet(url, accessToken);
        } catch (Exception e) {
            log.warn("Network error checking deployment for proxy '" + proxyName
                    + "'; treating as undeployed: " + e.getMessage());
            return false;
        }
        JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();

        if (json.has(ApigeeConstants.JSON_KEY_DEPLOYMENTS)) {
            JsonArray deployments = json.getAsJsonArray(ApigeeConstants.JSON_KEY_DEPLOYMENTS);
            for (JsonElement el : deployments) {
                JsonObject dep = el.getAsJsonObject();
                if (dep.has(ApigeeConstants.JSON_KEY_ENVIRONMENT)
                        && environment.equals(dep.get(ApigeeConstants.JSON_KEY_ENVIRONMENT).getAsString())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns the latest deployed revision number for a proxy in a given environment.
     * <p>
     * GET https://apigee.googleapis.com/v1/organizations/{org}/environments/{env}/apis/{api}/deployments
     */
    public static String getLatestDeployedRevision(String org, String proxyName,
                                                   String environment, String accessToken)
            throws APIManagementException {
        String url = ApigeeConstants.APIGEE_MGMT_API_BASE
                + "/organizations/" + org + "/environments/" + environment
                + "/apis/" + proxyName + "/deployments";
        String responseBody;
        try {
            responseBody = executeGet(url, accessToken);
        } catch (Exception e) {
            log.warn("Network error fetching revision for proxy '" + proxyName
                    + "'; defaulting to revision 1: " + e.getMessage());
            return "1";
        }
        JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();

        if (json.has(ApigeeConstants.JSON_KEY_DEPLOYMENTS)) {
            JsonArray deployments = json.getAsJsonArray(ApigeeConstants.JSON_KEY_DEPLOYMENTS);
            String latestRevision = null;
            for (JsonElement el : deployments) {
                JsonObject dep = el.getAsJsonObject();
                if (dep.has(ApigeeConstants.JSON_KEY_REVISION)) {
                    String rev = dep.get(ApigeeConstants.JSON_KEY_REVISION).getAsString();
                    if (latestRevision == null || Integer.parseInt(rev) > Integer.parseInt(latestRevision)) {
                        latestRevision = rev;
                    }
                }
            }
            if (latestRevision != null) {
                return latestRevision;
            }
        }
        // Fallback — return "1" (first revision)
        return "1";
    }

    /**
     * Attempts to retrieve an OpenAPI specification for the proxy.
     * <p>
     * API Hub is attempted first. If not available, it falls back to XML reconstruction
     * and then to a wildcard OpenAPI stub.
     *
     * @param org         Apigee organization
     * @param proxyName   Name of the API proxy
     * @param revision    Revision number
     * @param environment Gateway environment containing hostname config
     * @param accessToken OAuth access token
     * @return OpenAPI spec JSON string
     */
    public static String getApiProxyOpenAPISpec(String org, String proxyName,
                                                String revision, Environment environment, String accessToken)
            throws APIManagementException {

        // Resolve basepath first; fall back to proxy-name-derived path if network fails.
        String basepath = "/" + proxyName.toLowerCase();
        try {
            JsonObject revisionDetails = getApiProxyRevisionDetails(org, proxyName, revision, accessToken);
            String fetchedBasepath = getProxyBasepath(revisionDetails);
            if (fetchedBasepath != null && !fetchedBasepath.isEmpty()) {
                basepath = fetchedBasepath;
            }
        } catch (Exception e) {
            log.warn("Could not fetch revision details for proxy '" + proxyName
                    + "'; using fallback basepath '" + basepath + "': " + e.getMessage());
        }
        String apigeeBaseUrl = resolveApigeeBaseUrl(org, environment, basepath);

        try {
            // Get API Hub location from environment config (defaults to "global")
            String apiHubLocation = ApigeeConstants.DEFAULT_API_HUB_LOCATION;
            if (environment.getAdditionalProperties() != null
                    && environment.getAdditionalProperties().containsKey(ApigeeConstants.APIGEE_API_HUB_LOCATION)) {
                String configuredLocation = environment.getAdditionalProperties()
                        .get(ApigeeConstants.APIGEE_API_HUB_LOCATION);
                if (configuredLocation != null && !configuredLocation.trim().isEmpty()) {
                    apiHubLocation = configuredLocation.trim();
                }
            }
            String apiHubSpec = fetchSpecFromApiHub(org, proxyName, apiHubLocation, accessToken);
            if (apiHubSpec != null) {
                return normalizeOpenApiTitle(
                        replaceServerUrl(apiHubSpec, apigeeBaseUrl, proxyName), proxyName);
            }

            String xmlSpec = buildSpecFromProxyBundle(org, proxyName, revision, apigeeBaseUrl, accessToken);
            if (xmlSpec != null) {
                return normalizeOpenApiTitle(
                        replaceServerUrl(xmlSpec, apigeeBaseUrl, proxyName), proxyName);
            }
        } catch (Exception e) {
            // Network failure — fall through to wildcard spec rather than throwing.
            log.warn("Network error fetching spec for proxy '" + proxyName
                    + "'; falling back to wildcard spec: " + e.getMessage());
        }

        // Always return a valid spec so this proxy appears in every discovery result.
        log.debug("Using wildcard spec fallback for proxy '" + proxyName + "'");
        return normalizeOpenApiTitle(
                buildWildcardOpenAPISpec(proxyName, basepath, apigeeBaseUrl), proxyName);
    }

    // -----------------------------------------------------------------------
    //  Apigee API Hub spec retrieval
    // -----------------------------------------------------------------------

    /**
     * Attempts to fetch an OpenAPI spec from Apigee API Hub.
     * <p>
     * Workflow:
     * <ol>
     *   <li>Search API Hub for an API matching the proxy name (displayName filter)</li>
     *   <li>Get the API versions and find the latest one</li>
     *   <li>List the specs for that version</li>
     *   <li>Fetch the spec content (Base64 encoded)</li>
     * </ol>
     *
     * @param org         GCP project ID (Apigee organization)
     * @param proxyName   Name of the API proxy to search for
     * @param location    API Hub location (e.g., "global", "us-west1", "us-east1")
     * @param accessToken OAuth access token
     * @return OpenAPI spec JSON string, or null if not found
     */
    private static String fetchSpecFromApiHub(String org, String proxyName, String location, String accessToken) {
        try {
            String apiUuid = searchApiInHub(org, proxyName, location, accessToken);
            if (apiUuid == null) {
                return null;
            }

            String versionUuid = getLatestApiVersion(org, apiUuid, location, accessToken);
            if (versionUuid == null) {
                return null;
            }

            String specFullName = getSpecForVersion(org, apiUuid, versionUuid, location, accessToken);
            if (specFullName == null) {
                return null;
            }

            String specContent = fetchSpecContent(specFullName, accessToken);
            return specContent;

        } catch (Exception e) {
            log.debug("Error fetching spec from API Hub for proxy '" + proxyName + "': " + e.getMessage());
            return null;
        }
    }

    /**
     * Searches for an API in API Hub by display name.
     * GET https://apihub.googleapis.com/v1/projects/{project}/locations/{location}/apis?filter=displayName="{proxyName}"
     *
     * @return API UUID (name field from the response), or null if not found
     */
    private static String searchApiInHub(String org, String proxyName, String location, String accessToken) {
        try {
            // API Hub uses snake_case 'display_name' not camelCase 'displayName'
            String filter = "display_name=\"" + proxyName + "\"";
            String url = ApigeeConstants.APIGEE_API_HUB_BASE
                    + "/projects/" + org + "/locations/" + location + "/apis"
                    + "?filter=" + java.net.URLEncoder.encode(filter, java.nio.charset.StandardCharsets.UTF_8);

            String responseBody = executeGetOptional(url, accessToken);
            if (responseBody == null) {
                return null;
            }

            JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();

            if (json.has("apis") && json.get("apis").isJsonArray()) {
                JsonArray apis = json.getAsJsonArray("apis");
                if (apis.size() > 0) {
                    JsonObject api = apis.get(0).getAsJsonObject();
                    if (api.has("name")) {
                        String fullName = api.get("name").getAsString();
                        String apiUuid = extractResourceId(fullName);
                        return apiUuid;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Error while searching API Hub for proxy '" + proxyName + "': " + e.getMessage());
        }
        return null;
    }

    /**
     * Gets the latest version for an API in API Hub.
     * GET https://apihub.googleapis.com/v1/projects/{project}/locations/{location}/apis/{apiId}/versions
     *
     * @return Version UUID, or null if not found
     */
    private static String getLatestApiVersion(String org, String apiUuid, String location, String accessToken) {
        try {
            String url = ApigeeConstants.APIGEE_API_HUB_BASE
                    + "/projects/" + org + "/locations/" + location + "/apis/" + apiUuid + "/versions";

            String responseBody = executeGetOptional(url, accessToken);
            if (responseBody == null) {
                return null;
            }

            JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();

            if (json.has("versions") && json.get("versions").isJsonArray()) {
                JsonArray versions = json.getAsJsonArray("versions");
                if (versions.size() > 0) {
                    JsonObject version = versions.get(0).getAsJsonObject();
                    if (version.has("name")) {
                        String fullName = version.get("name").getAsString();
                        String versionUuid = extractResourceId(fullName);
                        return versionUuid;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Error while fetching API Hub versions for API '" + apiUuid + "': " + e.getMessage());
        }
        return null;
    }

    /**
     * Gets the spec full name for an API version.
     * GET https://apihub.googleapis.com/v1/projects/{project}/locations/{location}/apis/{apiId}/versions/{versionId}/specs
     *
     * @return Spec full resource name (e.g., "projects/.../locations/.../apis/.../versions/.../specs/..."), or null if not found
     */
    private static String getSpecForVersion(String org, String apiUuid, String versionUuid, String location, String accessToken) {
        try {
            String url = ApigeeConstants.APIGEE_API_HUB_BASE
                    + "/projects/" + org + "/locations/" + location + "/apis/" + apiUuid
                    + "/versions/" + versionUuid + "/specs";

            String responseBody = executeGetOptional(url, accessToken);
            if (responseBody == null) {
                return null;
            }

            JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();

            if (json.has("specs") && json.get("specs").isJsonArray()) {
                JsonArray specs = json.getAsJsonArray("specs");
                if (specs.size() > 0) {
                    JsonObject spec = specs.get(0).getAsJsonObject();
                    if (spec.has("name")) {
                        return spec.get("name").getAsString();
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Error while fetching API Hub specs for version '" + versionUuid + "': " + e.getMessage());
        }
        return null;
    }

    /**
     * Fetches the spec content from API Hub.
     * GET https://apihub.googleapis.com/v1/{specFullName}:contents
     *
     * @param specFullName Full resource name like "projects/.../locations/.../apis/.../versions/.../specs/..."
     * @return Decoded spec content, or null if not found
     */
    private static String fetchSpecContent(String specFullName, String accessToken) {
        try {
            String url = ApigeeConstants.APIGEE_API_HUB_BASE + "/" + specFullName + ":contents";

            String responseBody = executeGetOptional(url, accessToken);
            if (responseBody == null) {
                return null;
            }

            JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();

            if (json.has("contents")) {
                String base64Content = json.get("contents").getAsString();
                byte[] decodedBytes = java.util.Base64.getDecoder().decode(base64Content);
                return new String(decodedBytes, java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.debug("Error while fetching API Hub spec content for '" + specFullName + "': " + e.getMessage());
        }
        return null;
    }

    /**
     * Extracts the resource ID from a full resource name.
     * Example: "projects/my-project/locations/global/apis/abc-123" -> "abc-123"
     */
    private static String extractResourceId(String fullResourceName) {
        if (fullResourceName == null || fullResourceName.isEmpty()) {
            return null;
        }
        int lastSlash = fullResourceName.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < fullResourceName.length() - 1) {
            return fullResourceName.substring(lastSlash + 1);
        }
        return fullResourceName;
    }

    // -----------------------------------------------------------------------
    //  Core: reconstruct OpenAPI spec by downloading the proxy bundle ZIP
    //  and parsing proxies/default.xml from it.
    //
    //  The Apigee X Management API does NOT expose individual proxy endpoint
    //  resources at /proxies/{name} — that sub-path returns 404.  The only
    //  reliable way to read the conditional flows is to download the full
    //  proxy bundle via:
    //    GET .../organizations/{org}/apis/{api}/revisions/{rev}?format=bundle
    //  which returns a ZIP containing (among other things):
    //    apiproxy/proxies/default.xml
    //  That XML carries the <Flows> element with each flow's <Condition>.
    // -----------------------------------------------------------------------

    /**
     * Downloads the proxy revision bundle as a ZIP, extracts
     * {@code apiproxy/proxies/default.xml} (or the first {@code proxies/*.xml}
     * found), parses each {@code <Flow>/<Condition>} for
     * {@code MatchesPath} + {@code request.verb}, and builds a valid
     * OpenAPI 3.0.1 spec from the results.
     *
     * @return an OpenAPI 3.0.1 JSON string, or {@code null} if no flows
     *         with MatchesPath conditions could be found
     */
    private static String buildSpecFromProxyBundle(String org, String proxyName,
                                                   String revision,
                                                   String apigeeBaseUrl,
                                                   String accessToken) {
        String bundleUrl = ApigeeConstants.APIGEE_MGMT_API_BASE
                + "/organizations/" + org + "/apis/" + proxyName
                + "/revisions/" + revision + "?format=bundle";

        byte[] zipBytes;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(bundleUrl))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .timeout(Duration.ofSeconds(60))
                    .build();
            HttpResponse<byte[]> response = HTTP_CLIENT.send(
                    request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() >= 400) {
                return null;
            }
            zipBytes = response.body();
        } catch (Exception e) {
            return null;
        }

        String proxyEndpointXml = null;
        Map<String, String> policyXmlMap = new HashMap<>();
        
        try (java.util.zip.ZipInputStream zis =
                     new java.util.zip.ZipInputStream(
                              new java.io.ByteArrayInputStream(zipBytes))) {
            java.util.zip.ZipEntry entry;
            String fallbackXml = null;
            
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                
                // Read proxy endpoint files
                if (name.contains("proxies/") && name.endsWith(".xml")) {
                    byte[] buf = zis.readAllBytes();
                    String xml = new String(buf, java.nio.charset.StandardCharsets.UTF_8);
                    if (name.endsWith("proxies/default.xml")) {
                        proxyEndpointXml = xml;
                    } else if (fallbackXml == null) {
                        fallbackXml = xml;
                    }
                }
                // Read policy files for query/header parameter extraction
                else if (name.contains("policies/") && name.endsWith(".xml")) {
                    byte[] buf = zis.readAllBytes();
                    String policyXml = new String(buf, java.nio.charset.StandardCharsets.UTF_8);
                    // Extract policy name from path (e.g., "apiproxy/policies/ExtractVars.xml" -> "ExtractVars")
                    String policyName = name.substring(name.lastIndexOf('/') + 1, name.lastIndexOf('.'));
                    policyXmlMap.put(policyName, policyXml);
                }
                
                zis.closeEntry();
            }

            if (proxyEndpointXml == null) {
                proxyEndpointXml = fallbackXml;
            }
        } catch (Exception e) {
            return null;
        }

        if (proxyEndpointXml == null) {
            return null;
        }
        String result = ApigeeOpenAPISpecBuilder.buildFromProxyBundle(
                proxyEndpointXml, policyXmlMap, proxyName, apigeeBaseUrl);

        return result;
    }

    // -----------------------------------------------------------------------
    //  Shared URL resolution helper
    // -----------------------------------------------------------------------

    /**
     * Resolves the fully-qualified Apigee base URL from the environment
     * configuration and the proxy basepath.
     */
    private static String resolveApigeeBaseUrl(String org, Environment environment, String basepath) {
        String hostname = environment.getAdditionalProperties().get(ApigeeConstants.APIGEE_API_HOSTNAME);
        if (hostname != null && !hostname.trim().isEmpty()) {
            return "https://" + hostname.trim() + basepath;
        }
        String apigeeEnv = environment.getAdditionalProperties().get(ApigeeConstants.APIGEE_ENVIRONMENT);
        return "https://" + org + "-" + apigeeEnv + ".apigee.net" + basepath;
    }

    // -----------------------------------------------------------------------
    //  Existing helpers (unchanged)
    // -----------------------------------------------------------------------

    /**
     * Retrieves the API proxy metadata (name, revision list, metaData timestamps, etc.).
     */
    public static JsonObject getApiProxyMetadata(String org, String proxyName,
                                                 String accessToken) throws APIManagementException {
        String url = ApigeeConstants.APIGEE_MGMT_API_BASE
                + "/organizations/" + org + "/apis/" + proxyName;
        try {
            String responseBody = executeGet(url, accessToken);
            return JsonParser.parseString(responseBody).getAsJsonObject();
        } catch (Exception e) {
            log.warn("Network error fetching metadata for proxy '" + proxyName
                    + "'; returning empty metadata: " + e.getMessage());
            return new JsonObject();
        }
    }

    /**
     * Retrieves the API proxy revision details including basepaths.
     */
    public static JsonObject getApiProxyRevisionDetails(String org, String proxyName,
                                                        String revision, String accessToken)
            throws APIManagementException {
        String url = ApigeeConstants.APIGEE_MGMT_API_BASE
                + "/organizations/" + org + "/apis/" + proxyName
                + "/revisions/" + revision;
        try {
            String responseBody = executeGet(url, accessToken);
            return JsonParser.parseString(responseBody).getAsJsonObject();
        } catch (Exception e) {
            log.debug("Could not fetch revision details for proxy '" + proxyName + "' revision " + revision);
            return new JsonObject();
        }
    }

    /**
     * Extracts the basepath from proxy revision details.
     * Returns empty string if no basepath is configured.
     */
    public static String getProxyBasepath(JsonObject revisionDetails) {
        if (revisionDetails.has(ApigeeConstants.JSON_KEY_BASE_PATHS)
                && revisionDetails.get(ApigeeConstants.JSON_KEY_BASE_PATHS).isJsonArray()) {
            JsonArray basepaths = revisionDetails.getAsJsonArray(ApigeeConstants.JSON_KEY_BASE_PATHS);
            if (basepaths.size() > 0) {
                return basepaths.get(0).getAsString();
            }
        }
        return "";
    }

    /**
     * Converts Apigee proxy metadata into a WSO2 {@link API} model.
     */
    /**
     * Converts Apigee proxy metadata into a WSO2 {@link API} model.
     *
     * <p><b>Clean API naming:</b> Unlike AWS/Azure connectors, this implementation
     * uses the raw Apigee proxy name without any WSO2 environment suffix.
     *
     * <p>This approach assumes a single WSO2 gateway environment will be configured
     * per Apigee setup. If multiple WSO2 environments discover the same Apigee environment,
     * name collisions may occur.
     *
     * @param proxyName         the raw Apigee API proxy name (e.g. "AES_test")
     * @param proxyMetadata     JSON object returned by the Apigee Management API
     * @param apiDefinition     the OpenAPI spec (JSON string)
     * @param organization      the WSO2 APIM tenant / organization
     * @param environment       the WSO2 Environment model (carries the gateway name)
     * @param apigeeOrganization the Apigee organization/project id
     * @param basepath          the proxy basepath extracted from revision details
     * @param deployed          whether the proxy is currently deployed to the environment
     * @return a fully-populated WSO2 {@link API} model ready for import
     */
    public static API proxyToAPI(String proxyName, JsonObject proxyMetadata,
                                 String apiDefinition, String organization,
                                 Environment environment, String apigeeOrganization,
                                 String basepath, boolean deployed) {

        String version = ApigeeConstants.DEFAULT_VERSION;

        // ---------------------------------------------------------------
        // Use clean API name (no WSO2 environment suffix)
        // ---------------------------------------------------------------
        String apiName = proxyName;
        log.debug("Using clean API name: '" + apiName + "' (no environment suffix)");

        APIIdentifier apiIdentifier = new APIIdentifier("admin", apiName, version);
        API api = new API(apiIdentifier);

        // Display name matches API name
        api.setDisplayName(proxyName);

        // ---------------------------------------------------------------
        // Generate deterministic UUID
        //
        // UUID Seed = proxyName + organization
        // Example: "wadeHari-carbon.super"
        //
        // This ensures:
        //   - Same proxy discovered multiple times = SAME UUID (idempotent)
        //   - Stable UUID even if gateway/apigee environment labels are edited
        // ---------------------------------------------------------------
        String normalizedProxyName = proxyName == null ? "" : proxyName.trim();
        String normalizedOrganization = organization == null ? "" : organization.trim();
        String uuidSeed = normalizedProxyName + "-" + normalizedOrganization;
        String deterministicUuid = java.util.UUID.nameUUIDFromBytes(
                uuidSeed.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        api.setUuid(deterministicUuid);
        
        log.debug("Generated UUID for '" + proxyName + "' using seed: " + uuidSeed);

        api.setDescription("Apigee API Proxy: " + proxyName);
        api.setContext("/" + proxyName.toLowerCase().replace(" ", "-"));
        api.setContextTemplate("/" + proxyName.toLowerCase().replace(" ", "-"));
        api.setOrganization(organization);

        api.setSwaggerDefinition(normalizeOpenApiTitle(apiDefinition, proxyName));
        api.setRevision(false);
        api.setLastUpdated(new Date());
        api.setCreatedTime(Long.toString(System.currentTimeMillis()));
        api.setInitiatedFromGateway(true);
        api.setGatewayVendor("external");
        api.setEnableSubscriberVerification(false);
        api.setGatewayType(environment.getGatewayType());

        List<String> securitySchemes = new ArrayList<>();
        securitySchemes.add("api_key");
        api.setApiSecurity("api_key");

        List<String> keyManagers = new ArrayList<>();
        keyManagers.add("Resident Key Manager");
        api.setKeyManagers(keyManagers);

        // ---------------------------------------------------------------
        // Endpoint configuration
        // For undeployed proxies, use empty/placeholder URLs
        // ---------------------------------------------------------------
        JsonObject endpointConfig = new JsonObject();
        endpointConfig.addProperty("endpoint_type", "http");

        String executionUrl;
        if (deployed) {
            // DEPLOYED: Use actual Apigee endpoint URL
            String hostname = environment.getAdditionalProperties().get(ApigeeConstants.APIGEE_API_HOSTNAME);
            if (hostname != null && !hostname.trim().isEmpty()) {
                executionUrl = "https://" + hostname.trim() + basepath;
            } else {
                String orgForUrl = apigeeOrganization;
                if (orgForUrl == null || orgForUrl.trim().isEmpty()) {
                    orgForUrl = environment.getAdditionalProperties().get(ApigeeConstants.APIGEE_ORGANIZATION);
                }
                if ((orgForUrl == null || orgForUrl.trim().isEmpty())
                        && environment.getAdditionalProperties() != null) {
                    orgForUrl = environment.getAdditionalProperties().get(ApigeeConstants.APIGEE_ORGANIZATION_LEGACY);
                }
                executionUrl = "https://"
                        + orgForUrl
                        + "-"
                        + environment.getAdditionalProperties().get(ApigeeConstants.APIGEE_ENVIRONMENT)
                        + ".apigee.net" + basepath;
            }
        } else {
            // NOT DEPLOYED: Use empty placeholder URL
            // This prevents dev portal from crashing with blank screen
            executionUrl = "";
        }

        JsonObject prod = new JsonObject();
        prod.addProperty(ApigeeConstants.URL_PROP, executionUrl);
        JsonObject sand = new JsonObject();
        sand.addProperty(ApigeeConstants.URL_PROP, executionUrl);
        endpointConfig.add(ApigeeConstants.PRODUCTION_ENDPOINTS, prod);
        endpointConfig.add(ApigeeConstants.SANDBOX_ENDPOINTS, sand);
        api.setEndpointConfig(endpointConfig.toString());

        api.setApiLevelPolicy(null);
        api.setVisibility("PUBLIC");
        api.setAccessControl("ALL");
        api.setSubscriptionAvailability("CURRENT_TENANT");
        api.setTransports("https");
        
        // Newly discovered APIs should remain in CREATED state and require manual publishing.
        api.setStatus(APIConstants.CREATED);

        return api;
    }


    // -----------------------------------------------------------------------
    //  Reference artifact helpers
    // -----------------------------------------------------------------------

    /**
     * Creates a JSON reference artifact that can be stored by APIM and used
     * for change detection and later operations (undeploy, update).
     * <p>
     * Includes deployment status so that deploy/undeploy changes are detected.
     */
    public static String createReferenceArtifact(String proxyName, String revision,
                                                 String apiDefinition, boolean deployed) {
        JsonObject ref = new JsonObject();
        ref.addProperty("proxyName", proxyName);
        ref.addProperty("revision", revision);
        ref.addProperty("deployed", deployed);
        ref.addProperty("specHash", String.valueOf(apiDefinition != null ? apiDefinition.hashCode() : 0));
        return GSON.toJson(ref);
    }

    /**
     * Overloaded method for backward compatibility.
     * Defaults to deployed=true.
     */
    public static String createReferenceArtifact(String proxyName, String revision,
                                                 String apiDefinition) {
        return createReferenceArtifact(proxyName, revision, apiDefinition, true);
    }

    // -----------------------------------------------------------------------
    //  OpenAPI Spec URL Replacement
    // -----------------------------------------------------------------------

    /**
     * Replaces the server URLs in an OpenAPI spec with the Apigee proxy URL.
     * <p>
     * This ensures that the "Try It Out" feature in WSO2 Dev Portal uses the
     * Apigee proxy URL instead of the backend URL from the original spec.
     * <p>
     * Supports both JSON and YAML formats.
     *
     * @param specContent   OpenAPI spec as JSON or YAML string
     * @param apigeeBaseUrl Apigee proxy base URL (e.g., "https://35.23.43.5.nip.io/api.demo-ecommerce.com/v1")
     * @param proxyName     Name of the proxy (for logging)
     * @return Modified OpenAPI spec with replaced server URLs
     */
    private static String replaceServerUrl(String specContent, String apigeeBaseUrl, String proxyName) {
        try {
            // Detect format: YAML starts with "openapi:" or has newlines with colons
            boolean isYaml = specContent.trim().startsWith("openapi:") || 
                           (!specContent.trim().startsWith("{") && specContent.contains("\n") && specContent.contains(": "));
            
            if (isYaml) {
                return replaceServerUrlInYaml(specContent, apigeeBaseUrl, proxyName);
            } else {
                return replaceServerUrlInJson(specContent, apigeeBaseUrl, proxyName);
            }
        } catch (Exception e) {
            log.debug("Failed to replace server URL for proxy '" + proxyName + "': " + e.getMessage());
            return specContent;
        }
    }

    /**
     * Forces the OpenAPI info.title to match the discovered proxy name.
     * This prevents APIM import from deriving suffixed names (e.g. "-apigee")
     * from stale titles carried in externally attached specs.
     */
    private static String normalizeOpenApiTitle(String specContent, String proxyName) {
        if (specContent == null || specContent.trim().isEmpty() || proxyName == null || proxyName.trim().isEmpty()) {
            return specContent;
        }

        try {
            boolean isYaml = specContent.trim().startsWith("openapi:")
                    || (!specContent.trim().startsWith("{") && specContent.contains("\n") && specContent.contains(": "));
            if (isYaml) {
                return normalizeOpenApiTitleInYaml(specContent, proxyName.trim());
            }
            return normalizeOpenApiTitleInJson(specContent, proxyName.trim());
        } catch (Exception e) {
            log.debug("Failed to normalize OpenAPI title for proxy '" + proxyName + "': " + e.getMessage());
            return specContent;
        }
    }

    private static String normalizeOpenApiTitleInJson(String specJson, String proxyName) {
        JsonObject spec = JsonParser.parseString(specJson).getAsJsonObject();
        JsonObject info;
        if (spec.has("info") && spec.get("info").isJsonObject()) {
            info = spec.getAsJsonObject("info");
        } else {
            info = new JsonObject();
            spec.add("info", info);
        }
        info.addProperty("title", proxyName);
        return GSON.toJson(spec);
    }

    /**
     * Forces info.title to match the proxy name in a YAML OpenAPI spec.
     * Uses SnakeYAML to parse into an object tree and mutate safely.
     */
    @SuppressWarnings("unchecked")
    private static String normalizeOpenApiTitleInYaml(String specYaml, String proxyName) {
        try {
            Yaml yaml = buildYaml();
            Map<String, Object> spec = yaml.load(specYaml);
            if (spec == null) {
                return specYaml;
            }

            Object infoObj = spec.get("info");
            Map<String, Object> info;
            if (infoObj instanceof Map) {
                info = (Map<String, Object>) infoObj;
            } else {
                info = new java.util.LinkedHashMap<>();
                spec.put("info", info);
            }
            info.put("title", proxyName);

            return yaml.dump(spec);
        } catch (Exception e) {
            log.debug("Failed to normalize YAML title for proxy '" + proxyName + "': " + e.getMessage());
            return specYaml;
        }
    }

    /**
     * Builds a SnakeYAML {@link Yaml} instance configured for block-style
     * output that is compatible with OpenAPI tooling.
     */
    private static Yaml buildYaml() {
        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setPrettyFlow(true);
        opts.setIndent(2);
        return new Yaml(opts);
    }

    /**
     * Replaces server URLs in a JSON OpenAPI spec.
     */
    private static String replaceServerUrlInJson(String specJson, String apigeeBaseUrl, String proxyName) {
        JsonObject spec = JsonParser.parseString(specJson).getAsJsonObject();
        
        // Replace with Apigee proxy URL
        JsonArray servers = new JsonArray();
        JsonObject server = new JsonObject();
        server.addProperty("url", apigeeBaseUrl);
        servers.add(server);
        spec.add("servers", servers);
        
        return GSON.toJson(spec);
    }

    /**
     * Replaces server URLs in a YAML OpenAPI spec using SnakeYAML.
     * Parses the YAML into an object tree, replaces the servers list,
     * then serializes back — structurally safe regardless of formatting.
     */
    @SuppressWarnings("unchecked")
    private static String replaceServerUrlInYaml(String specYaml, String apigeeBaseUrl, String proxyName) {
        try {
            Yaml yaml = buildYaml();
            Map<String, Object> spec = yaml.load(specYaml);
            if (spec == null) {
                return specYaml;
            }

            // Build servers list: [{url: <apigeeBaseUrl>}]
            Map<String, Object> serverEntry = new java.util.LinkedHashMap<>();
            serverEntry.put("url", apigeeBaseUrl);
            List<Object> servers = new ArrayList<>();
            servers.add(serverEntry);
            spec.put("servers", servers);

            return yaml.dump(spec);
        } catch (Exception e) {
            log.debug("Failed YAML server URL replacement for proxy '" + proxyName + "': " + e.getMessage());
            return specYaml;
        }
    }

    // -----------------------------------------------------------------------
    //  Undeployed API helpers
    // -----------------------------------------------------------------------

    /**
     * Builds a minimal OpenAPI spec for an undeployed proxy.
     * <p>
     * The spec has empty servers array so WSO2 dev portal shows no endpoint
     * instead of crashing with a blank screen.
     */
    public static String buildUndeployedOpenAPISpec(String proxyName, Environment environment) {
        JsonObject spec = new JsonObject();
        spec.addProperty("openapi", "3.0.1");
        
        JsonObject info = new JsonObject();
        info.addProperty("title", proxyName);
        info.addProperty("description", "Apigee proxy '" + proxyName + "' is currently NOT DEPLOYED to environment '"
                + environment.getName() + "'. Deploy the proxy in Apigee to enable API operations.");
        info.addProperty("version", ApigeeConstants.DEFAULT_VERSION);
        spec.add("info", info);
        
        // Empty servers array indicates no endpoints available
        spec.add("servers", new JsonArray());
        
        // Minimal paths - just a root with info about undeployed state
        JsonObject paths = new JsonObject();
        JsonObject rootPath = new JsonObject();
        JsonObject getOp = new JsonObject();
        getOp.addProperty("summary", "API not deployed");
        getOp.addProperty("description", "This API proxy is not currently deployed to the Apigee environment. "
                + "Deploy the proxy in Apigee to access API operations.");
        getOp.addProperty("operationId", "undeployed_info");
        
        JsonObject responses = new JsonObject();
        JsonObject response503 = new JsonObject();
        response503.addProperty("description", "Service unavailable - API not deployed");
        responses.add("503", response503);
        getOp.add("responses", responses);
        
        rootPath.add("get", getOp);
        paths.add("/", rootPath);
        spec.add("paths", paths);
        
        // Security schemes
        JsonObject components = new JsonObject();
        JsonObject securitySchemes = new JsonObject();
        JsonObject defaultScheme = new JsonObject();
        defaultScheme.addProperty("type", "oauth2");
        JsonObject flows = new JsonObject();
        JsonObject implicit = new JsonObject();
        implicit.addProperty("authorizationUrl", "https://localhost:9443/oauth2/authorize");
        implicit.add("scopes", new JsonObject());
        flows.add("implicit", implicit);
        defaultScheme.add("flows", flows);
        securitySchemes.add("default", defaultScheme);
        components.add("securitySchemes", securitySchemes);
        spec.add("components", components);
        
        return GSON.toJson(spec);
    }

    /**
     * Builds a wildcard OpenAPI spec when XML reconstruction fails.
     * <p>
     * Creates a catch-all `/*` path with all HTTP methods (GET, PUT, POST, DELETE, PATCH)
     * pointing to the actual Apigee proxy basepath.
     *
     * @param proxyName      Name of the proxy
     * @param basepath       Proxy basepath (e.g., "/wild/1")
     * @param apigeeBaseUrl  Full Apigee base URL (e.g., "https://example.com/wild/1")
     * @return OpenAPI 3.0.1 spec as JSON string
     */
    public static String buildWildcardOpenAPISpec(String proxyName, String basepath, String apigeeBaseUrl) {
        JsonObject spec = new JsonObject();
        spec.addProperty("openapi", "3.0.1");
        
        JsonObject info = new JsonObject();
        info.addProperty("title", proxyName);
        info.addProperty("description", "Wildcard API for Apigee proxy '" + proxyName 
                + "'. All paths and methods are proxied to the backend.");
        info.addProperty("version", ApigeeConstants.DEFAULT_VERSION);
        spec.add("info", info);
        
        // Server with actual basepath
        JsonArray servers = new JsonArray();
        JsonObject server = new JsonObject();
        server.addProperty("url", apigeeBaseUrl);
        servers.add(server);
        spec.add("servers", servers);
        
        // Wildcard path with all HTTP methods
        JsonObject paths = new JsonObject();
        JsonObject wildcardPath = new JsonObject();
        
        // Create operation object for all methods
        String[] methods = {"get", "put", "post", "delete", "patch"};
        for (String method : methods) {
            JsonObject operation = new JsonObject();
            operation.addProperty("summary", "Wildcard " + method.toUpperCase());
            operation.addProperty("description", "Proxies all " + method.toUpperCase() + " requests to the backend");
            operation.addProperty("operationId", method + "_wildcard");
            
            JsonObject responses = new JsonObject();
            JsonObject response200 = new JsonObject();
            response200.addProperty("description", "Successful response from backend");
            responses.add("200", response200);
            operation.add("responses", responses);
            
            // Add security and WSO2-specific attributes
            JsonArray security = new JsonArray();
            JsonObject securityItem = new JsonObject();
            securityItem.add("default", new JsonArray());
            security.add(securityItem);
            operation.add("security", security);
            operation.addProperty("x-auth-type", "Application & Application User");
            operation.addProperty("x-throttling-tier", "Unlimited");
            
            JsonObject appSecurity = new JsonObject();
            JsonArray securityTypes = new JsonArray();
            securityTypes.add("api_key");
            appSecurity.add("security-types", securityTypes);
            appSecurity.addProperty("optional", false);
            operation.add("x-wso2-application-security", appSecurity);
            
            wildcardPath.add(method, operation);
        }
        
        paths.add("/*", wildcardPath);
        spec.add("paths", paths);
        
        // Security schemes
        JsonObject components = new JsonObject();
        JsonObject securitySchemes = new JsonObject();
        JsonObject defaultScheme = new JsonObject();
        defaultScheme.addProperty("type", "oauth2");
        JsonObject flows = new JsonObject();
        JsonObject implicit = new JsonObject();
        implicit.addProperty("authorizationUrl", "https://localhost:9443/oauth2/authorize");
        implicit.add("scopes", new JsonObject());
        flows.add("implicit", implicit);
        defaultScheme.add("flows", flows);
        securitySchemes.add("default", defaultScheme);
        components.add("securitySchemes", securitySchemes);
        spec.add("components", components);
        
        return GSON.toJson(spec);
    }

    /**
     * Gets the latest revision number for a proxy (whether deployed or not).
     * <p>
     * GET https://apigee.googleapis.com/v1/organizations/{org}/apis/{api}
     */
    public static String getLatestRevisionNumber(String org, String proxyName,
                                                  String accessToken) throws APIManagementException {
        JsonObject metadata = getApiProxyMetadata(org, proxyName, accessToken);
        if (metadata.has("revision") && metadata.get("revision").isJsonArray()) {
            JsonArray revisions = metadata.getAsJsonArray("revision");
            if (revisions.size() > 0) {
                return revisions.get(revisions.size() - 1).getAsString();
            }
        }
        return "1";
    }

    // -----------------------------------------------------------------------
    //  Private HTTP helpers
    // -----------------------------------------------------------------------

    private static String executeGet(String url, String accessToken) throws APIManagementException {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Accept", "application/json, */*;q=0.8")
                    .GET()
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                log.error("Apigee API GET " + url + " returned HTTP " + response.statusCode()
                        + ": " + response.body());
                throw new APIManagementException("Apigee API returned HTTP " + response.statusCode()
                        + " for GET " + url);
            }

            return response.body();
        } catch (APIManagementException e) {
            throw e;
        } catch (Exception e) {
            // Catch ALL exceptions including RuntimeException subclasses such as
            // UnresolvedAddressException (DNS failure / no network), which extends
            // RuntimeException and bypasses IOException|InterruptedException.
            throw new APIManagementException("Error executing GET " + url, e);
        }
    }

    /**
     * Executes a GET request for API Hub endpoints without throwing exceptions.
     * Used for optional API Hub calls where failures should not break the discovery flow.
     *
     * @return response body if successful, null if request fails
     */
    private static String executeGetOptional(String url, String accessToken) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Accept", "application/json, */*;q=0.8")
                    .GET()
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                return null;
            }

            return response.body();
        } catch (Exception e) {
            return null;
        }
    }
}