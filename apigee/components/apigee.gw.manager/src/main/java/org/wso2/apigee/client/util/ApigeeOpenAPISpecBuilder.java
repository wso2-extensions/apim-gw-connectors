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
import com.google.gson.JsonObject;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds OpenAPI 3.0.1 specifications by reverse-engineering Apigee proxy bundles.
 * 
 * <p>Since Apigee does not provide OpenAPI specs directly via REST API (unlike AWS/Azure),
 * this class parses proxy bundle ZIP files to extract routing information and policy
 * configurations, then constructs a valid OpenAPI spec.
 * 
 * <p>Extraction capabilities:
 * <ul>
 *   <li>Paths and HTTP methods from proxy endpoint conditional flows</li>
 *   <li>Path parameters from wildcard patterns (e.g., /users/* → /users/{userId})</li>
 *   <li>Query parameters from ExtractVariables and VerifyAPIKey policies</li>
 *   <li>Header parameters from ExtractVariables and AssignMessage policies</li>
 * </ul>
 * 
 * @see ApigeeAPIUtil#getApiProxyOpenAPISpec
 */
public class ApigeeOpenAPISpecBuilder {

    private static final Log log = LogFactory.getLog(ApigeeOpenAPISpecBuilder.class);
    private static final Gson GSON = new Gson();

    // -----------------------------------------------------------------------
    //  Note: Condition parsing is now handled by ApigeeConditionParser class
    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------
    //  Container class for policy-extracted parameters
    // -----------------------------------------------------------------------

    /**
     * Container class for extracted query and header parameters from Apigee policies.
     */
    static class PolicyParameters {
        final Set<String> queryParams = new LinkedHashSet<>();
        final Set<String> headerParams = new LinkedHashSet<>();
    }

    // -----------------------------------------------------------------------
    //  Public entry points
    // -----------------------------------------------------------------------

    /**
     * Builds an OpenAPI spec from the proxy bundle ZIP contents.
     * 
     * @param proxyEndpointXml  Content of proxies/default.xml
     * @param policyXmlMap      Map of policy name → policy XML content
     * @param proxyName         Name of the Apigee proxy
     * @param apigeeBaseUrl     Base URL for the API (e.g., https://org-env.apigee.net/api)
     * @return OpenAPI 3.0.1 JSON string, or null if no flows found
     */
    public static String buildFromProxyBundle(String proxyEndpointXml,
                                               Map<String, String> policyXmlMap,
                                               String proxyName,
                                               String apigeeBaseUrl) {
        // Extract parameters from policies
        PolicyParameters policyParams = extractParametersFromPolicies(policyXmlMap, proxyName);
        log.debug("Proxy '" + proxyName + "': extracted " + policyParams.queryParams.size()
                + " query param(s), " + policyParams.headerParams.size() + " header param(s) from policies.");

        // Parse XML and build spec
        return buildSpecFromProxyEndpointXml(proxyName, apigeeBaseUrl, proxyEndpointXml, policyParams);
    }

    /**
     * Builds a minimal wildcard OpenAPI spec for proxies without conditional flows.
     * This is a fallback when the proxy uses passthrough routing.
     * 
     * @param proxyName     Name of the Apigee proxy
     * @param apigeeBaseUrl Base URL for the API
     * @return OpenAPI 3.0.1 JSON string with wildcard paths
     */
    public static String buildMinimalSpec(String proxyName, String apigeeBaseUrl) {
        JsonObject spec = new JsonObject();
        spec.addProperty("openapi", "3.0.1");

        // Info section
        JsonObject info = new JsonObject();
        info.addProperty("title", proxyName);
        info.addProperty("version", "1.0.0");
        info.addProperty("description", "Auto-generated minimum spec for Apigee proxy: " + proxyName);
        spec.add("info", info);

        // Servers section
        JsonArray servers = new JsonArray();
        JsonObject server = new JsonObject();
        server.addProperty("url", apigeeBaseUrl);
        servers.add(server);
        spec.add("servers", servers);

        // Global security requirement
        JsonArray globalSecurity = new JsonArray();
        JsonObject defaultSecurityReq = new JsonObject();
        defaultSecurityReq.add("default", new JsonArray());
        globalSecurity.add(defaultSecurityReq);
        spec.add("security", globalSecurity);

        // Paths section - wildcard for all methods
        String[] methods = {"get", "put", "post", "delete", "patch"};
        JsonObject wildcardPathItem = new JsonObject();

        for (String method : methods) {
            JsonObject op = new JsonObject();
            op.addProperty("summary", "Proxy " + method.toUpperCase() + " /*");
            op.addProperty("operationId", method + "_wildcard");

            // Add requestBody for POST, PUT, PATCH operations
            if ("post".equals(method) || "put".equals(method) || "patch".equals(method)) {
                JsonObject requestBody = new JsonObject();
                requestBody.addProperty("description", "Request payload");
                requestBody.addProperty("required", true);

                JsonObject content = new JsonObject();
                JsonObject applicationJson = new JsonObject();
                JsonObject schema = new JsonObject();
                schema.addProperty("type", "object");

                applicationJson.add("schema", schema);
                content.add("application/json", applicationJson);
                requestBody.add("content", content);

                op.add("requestBody", requestBody);
            }

            // Responses with content
            JsonObject responses = new JsonObject();
            JsonObject response200 = new JsonObject();
            response200.addProperty("description", "Successful response");
            JsonObject content200 = new JsonObject();
            JsonObject appJson200 = new JsonObject();
            JsonObject schema200 = new JsonObject();
            schema200.addProperty("type", "object");
            appJson200.add("schema", schema200);
            content200.add("application/json", appJson200);
            response200.add("content", content200);
            responses.add("200", response200);
            op.add("responses", responses);

            // Security at operation level
            JsonArray security = new JsonArray();
            JsonObject defaultSecurity = new JsonObject();
            defaultSecurity.add("default", new JsonArray());
            security.add(defaultSecurity);
            op.add("security", security);

            // WSO2 extensions
            op.addProperty("x-auth-type", "Application & Application User");
            op.addProperty("x-throttling-tier", "Unlimited");

            // WSO2 application security
            JsonObject appSecurity = new JsonObject();
            JsonArray securityTypes = new JsonArray();
            securityTypes.add("api_key");
            appSecurity.add("security-types", securityTypes);
            appSecurity.addProperty("optional", false);
            op.add("x-wso2-application-security", appSecurity);

            wildcardPathItem.add(method, op);
        }

        JsonObject paths = new JsonObject();
        paths.add("/*", wildcardPathItem);
        spec.add("paths", paths);

        // Components section (security schemes)
        spec.add("components", buildComponents());

        // WSO2 basePath
        spec.addProperty("x-wso2-basePath", "/" + proxyName + "/1.0.0");

        return GSON.toJson(spec);
    }

    // -----------------------------------------------------------------------
    //  Policy parameter extraction
    // -----------------------------------------------------------------------

    /**
     * Extracts query parameters and header parameters from Apigee policy XML files.
     */
    private static PolicyParameters extractParametersFromPolicies(Map<String, String> policyXmlMap,
                                                                   String proxyName) {
        PolicyParameters params = new PolicyParameters();

        if (policyXmlMap == null || policyXmlMap.isEmpty()) {
            log.debug("No policy files found for proxy '" + proxyName + "'.");
            return params;
        }

        javax.xml.parsers.DocumentBuilderFactory dbf =
                javax.xml.parsers.DocumentBuilderFactory.newInstance();
        try {
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (Exception e) {
            log.warn("Failed to set XML parser security feature: " + e.getMessage());
        }

        for (Map.Entry<String, String> entry : policyXmlMap.entrySet()) {
            String policyName = entry.getKey();
            String policyXml = entry.getValue();

            try {
                javax.xml.parsers.DocumentBuilder db = dbf.newDocumentBuilder();
                org.w3c.dom.Document doc = db.parse(
                        new java.io.ByteArrayInputStream(
                                policyXml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                doc.getDocumentElement().normalize();

                String rootElement = doc.getDocumentElement().getTagName();

                if ("ExtractVariables".equals(rootElement)) {
                    extractFromExtractVariablesPolicy(doc, params, policyName);
                } else if ("VerifyAPIKey".equals(rootElement)) {
                    extractFromVerifyAPIKeyPolicy(doc, params, policyName);
                } else if ("OAuthV2".equals(rootElement)) {
                    extractFromOAuthV2Policy(doc, params, policyName);
                } else if ("AssignMessage".equals(rootElement)) {
                    extractFromAssignMessagePolicy(doc, params, policyName);
                }

            } catch (Exception e) {
                log.warn("Failed to parse policy XML '" + policyName + "': " + e.getMessage());
            }
        }

        return params;
    }

    /**
     * Extracts query parameters and headers from ExtractVariables policy.
     */
    private static void extractFromExtractVariablesPolicy(org.w3c.dom.Document doc,
                                                           PolicyParameters params,
                                                           String policyName) {
        // Extract QueryParam elements
        NodeList queryParamNodes = doc.getElementsByTagName("QueryParam");
        for (int i = 0; i < queryParamNodes.getLength(); i++) {
            org.w3c.dom.Node node = queryParamNodes.item(i);
            if (node.getAttributes() != null) {
                org.w3c.dom.Node nameAttr = node.getAttributes().getNamedItem("name");
                if (nameAttr != null) {
                    String paramName = nameAttr.getNodeValue();
                    params.queryParams.add(paramName);
                    log.debug("ExtractVariables '" + policyName + "': found query param '" + paramName + "'");
                }
            }
        }

        // Extract Header elements
        NodeList headerNodes = doc.getElementsByTagName("Header");
        for (int i = 0; i < headerNodes.getLength(); i++) {
            org.w3c.dom.Node node = headerNodes.item(i);
            if (node.getAttributes() != null) {
                org.w3c.dom.Node nameAttr = node.getAttributes().getNamedItem("name");
                if (nameAttr != null) {
                    String headerName = nameAttr.getNodeValue();
                    if (!isCommonSecurityHeader(headerName)) {
                        params.headerParams.add(headerName);
                        log.debug("ExtractVariables '" + policyName + "': found header '" + headerName + "'");
                    }
                }
            }
        }
    }

    /**
     * Extracts API key location from VerifyAPIKey policy.
     */
    private static void extractFromVerifyAPIKeyPolicy(org.w3c.dom.Document doc,
                                                       PolicyParameters params,
                                                       String policyName) {
        NodeList apiKeyNodes = doc.getElementsByTagName("APIKey");
        for (int i = 0; i < apiKeyNodes.getLength(); i++) {
            org.w3c.dom.Node node = apiKeyNodes.item(i);
            if (node.getAttributes() != null) {
                org.w3c.dom.Node refAttr = node.getAttributes().getNamedItem("ref");
                if (refAttr != null) {
                    String ref = refAttr.getNodeValue();
                    if (ref.startsWith("request.header.")) {
                        String headerName = ref.substring("request.header.".length());
                        params.headerParams.add(headerName);
                        log.debug("VerifyAPIKey '" + policyName + "': API key in header '" + headerName + "'");
                    } else if (ref.startsWith("request.queryparam.")) {
                        String paramName = ref.substring("request.queryparam.".length());
                        params.queryParams.add(paramName);
                        log.debug("VerifyAPIKey '" + policyName + "': API key in query param '" + paramName + "'");
                    }
                }
            }
        }
    }

    /**
     * Extracts access token location from OAuthV2 policy.
     */
    private static void extractFromOAuthV2Policy(org.w3c.dom.Document doc,
                                                  PolicyParameters params,
                                                  String policyName) {
        NodeList accessTokenNodes = doc.getElementsByTagName("AccessToken");
        for (int i = 0; i < accessTokenNodes.getLength(); i++) {
            org.w3c.dom.Node node = accessTokenNodes.item(i);
            if (node.getAttributes() != null) {
                org.w3c.dom.Node refAttr = node.getAttributes().getNamedItem("ref");
                if (refAttr != null) {
                    String ref = refAttr.getNodeValue();
                    if (ref.startsWith("request.header.")) {
                        String headerName = ref.substring("request.header.".length());
                        if (!isCommonSecurityHeader(headerName)) {
                            params.headerParams.add(headerName);
                            log.debug("OAuthV2 '" + policyName + "': access token in header '" + headerName + "'");
                        }
                    } else if (ref.startsWith("request.queryparam.")) {
                        String paramName = ref.substring("request.queryparam.".length());
                        params.queryParams.add(paramName);
                        log.debug("OAuthV2 '" + policyName + "': access token in query param '" + paramName + "'");
                    }
                }
            }
        }
    }

    /**
     * Extracts headers from AssignMessage policy.
     */
    private static void extractFromAssignMessagePolicy(org.w3c.dom.Document doc,
                                                        PolicyParameters params,
                                                        String policyName) {
        NodeList headerNodes = doc.getElementsByTagName("Header");
        for (int i = 0; i < headerNodes.getLength(); i++) {
            org.w3c.dom.Node node = headerNodes.item(i);
            if (node.getAttributes() != null) {
                org.w3c.dom.Node nameAttr = node.getAttributes().getNamedItem("name");
                if (nameAttr != null) {
                    String headerName = nameAttr.getNodeValue();
                    org.w3c.dom.Node parentNode = node.getParentNode();
                    if (parentNode != null && "Add".equals(parentNode.getNodeName())) {
                        if (!isCommonSecurityHeader(headerName)) {
                            params.headerParams.add(headerName);
                            log.debug("AssignMessage '" + policyName + "': found header '" + headerName + "'");
                        }
                    }
                }
            }
        }
    }

    /**
     * Checks if a header name is a common security/auth header that's already handled.
     */
    private static boolean isCommonSecurityHeader(String headerName) {
        if (headerName == null) {
            return false;
        }
        String lower = headerName.toLowerCase();
        return lower.equals("authorization")
                || lower.equals("x-api-key")
                || lower.equals("apikey")
                || lower.equals("api-key");
    }

    // -----------------------------------------------------------------------
    //  Core OpenAPI spec building from proxy endpoint XML
    // -----------------------------------------------------------------------

    /**
     * Parses Apigee Proxy Endpoint XML and builds an OpenAPI 3.0.1 spec.
     */
    private static String buildSpecFromProxyEndpointXml(String proxyName,
                                                         String apigeeBaseUrl,
                                                         String xml,
                                                         PolicyParameters policyParams) {
        Map<String, Set<String>> pathVerbMap = new LinkedHashMap<>();

        try {
            javax.xml.parsers.DocumentBuilderFactory dbf =
                    javax.xml.parsers.DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            javax.xml.parsers.DocumentBuilder db = dbf.newDocumentBuilder();
            org.w3c.dom.Document doc = db.parse(
                    new java.io.ByteArrayInputStream(
                            xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            doc.getDocumentElement().normalize();

            NodeList flows = doc.getElementsByTagName("Flow");
            log.debug("Proxy '" + proxyName + "': found " + flows.getLength()
                    + " <Flow> element(s) in proxy endpoint XML.");

            for (int i = 0; i < flows.getLength(); i++) {
                Element flow = (Element) flows.item(i);
                NodeList conditions = flow.getElementsByTagName("Condition");
                if (conditions.getLength() == 0) {
                    continue;
                }
                String condition = conditions.item(0).getTextContent();
                if (condition == null || condition.trim().isEmpty()) {
                    continue;
                }
                condition = condition.trim();

                // Use dedicated parser for Apigee condition expressions
                ApigeeConditionParser.ParsedCondition parsed = ApigeeConditionParser.parse(condition);
                
                if (!parsed.isValid() || parsed.getPath() == null) {
                    log.debug("Proxy '" + proxyName + "' flow '" + flow.getAttribute("name")
                            + "': no MatchesPath in condition: " + condition);
                    continue;
                }

                String rawPath = parsed.getPath();
                String verb = parsed.getHttpVerb();
                if (verb == null) {
                    verb = "GET";
                }

                String oasPath = ApigeeConditionParser.toOpenAPIPath(rawPath);
                log.debug("Proxy '" + proxyName + "': mapped condition [" + condition
                        + "] → " + verb + " " + oasPath);
                pathVerbMap
                        .computeIfAbsent(oasPath, k -> new LinkedHashSet<>())
                        .add(verb.toLowerCase());
            }
        } catch (Exception e) {
            log.warn("Failed to parse proxy endpoint XML for '" + proxyName
                    + "': " + e.getMessage(), e);
            return null;
        }

        if (pathVerbMap.isEmpty()) {
            log.debug("No MatchesPath flows found in proxy endpoint XML for proxy '"
                    + proxyName + "'.");
            return null;
        }

        // Build OAS 3.0.1 spec
        JsonObject spec = new JsonObject();
        spec.addProperty("openapi", "3.0.1");

        // Info section
        JsonObject info = new JsonObject();
        info.addProperty("title", proxyName);
        info.addProperty("version", "1.0.0");
        info.addProperty("description",
                "OpenAPI spec reconstructed from Apigee Proxy Endpoint flows for proxy: " + proxyName);
        spec.add("info", info);

        // Servers section
        JsonArray servers = new JsonArray();
        JsonObject server = new JsonObject();
        server.addProperty("url", apigeeBaseUrl);
        servers.add(server);
        spec.add("servers", servers);

        // Global security requirement
        JsonArray globalSecurity = new JsonArray();
        JsonObject defaultSecurityReq = new JsonObject();
        defaultSecurityReq.add("default", new JsonArray());
        globalSecurity.add(defaultSecurityReq);
        spec.add("security", globalSecurity);

        // Paths section
        JsonObject paths = new JsonObject();
        for (Map.Entry<String, Set<String>> entry : pathVerbMap.entrySet()) {
            String oasPath = entry.getKey();
            Set<String> verbs = entry.getValue();
            JsonObject pathItem = new JsonObject();
            List<String> pathParams = extractPathParamNames(oasPath);
            if (!pathParams.isEmpty()) {
                pathItem.add("parameters", buildPathParameters(pathParams));
            }
            for (String verb : verbs) {
                pathItem.add(verb, buildOperation(proxyName, oasPath, verb, pathParams, policyParams));
            }
            paths.add(oasPath, pathItem);
        }
        spec.add("paths", paths);

        // Components section (security schemes)
        spec.add("components", buildComponents());

        // WSO2 basePath
        spec.addProperty("x-wso2-basePath", "/" + proxyName + "/1.0.0");

        log.debug("Reconstructed OpenAPI spec for proxy '" + proxyName
                + "' with " + pathVerbMap.size() + " path(s): " + pathVerbMap.keySet());
        return GSON.toJson(spec);
    }

    // -----------------------------------------------------------------------
    //  Path parameter extraction (kept here as it operates on OpenAPI paths, not Apigee conditions)
    // -----------------------------------------------------------------------

    /**
     * Extracts path parameter names from an OpenAPI path.
     */
    private static List<String> extractPathParamNames(String oasPath) {
        List<String> params = new ArrayList<>();
        Pattern p = Pattern.compile("\\{([^}]+)\\}");
        Matcher m = p.matcher(oasPath);
        while (m.find()) {
            params.add(m.group(1));
        }
        return params;
    }

    // -----------------------------------------------------------------------
    //  OpenAPI structure builders
    // -----------------------------------------------------------------------

    /**
     * Builds path parameters array for OpenAPI spec.
     */
    private static JsonArray buildPathParameters(List<String> paramNames) {
        JsonArray parameters = new JsonArray();
        for (String paramName : paramNames) {
            JsonObject param = new JsonObject();
            param.addProperty("name", paramName);
            param.addProperty("in", "path");
            param.addProperty("required", true);
            param.addProperty("description", "Path parameter: " + paramName);
            param.addProperty("style", "simple");
            param.addProperty("explode", false);
            JsonObject schema = new JsonObject();
            schema.addProperty("type", "string");
            param.add("schema", schema);
            parameters.add(param);
        }
        return parameters;
    }

    /**
     * Builds an OpenAPI operation object with all required elements.
     */
    private static JsonObject buildOperation(String proxyName, String oasPath,
                                             String verb, List<String> pathParams,
                                             PolicyParameters policyParams) {
        JsonObject op = new JsonObject();

        // Summary and description
        String summary = verb.toUpperCase() + " " + oasPath;
        op.addProperty("summary", summary);
        op.addProperty("description", "Reconstructed from Apigee Proxy Endpoint conditional flows.");

        // OperationId
        String opId = verb.toLowerCase() + oasPath
                .replace("{", "")
                .replace("}", "")
                .replace("/", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
        op.addProperty("operationId", opId);

        // Parameters (query and header from policies)
        JsonArray parameters = buildOperationParameters(policyParams);
        if (parameters.size() > 0) {
            op.add("parameters", parameters);
        }

        // RequestBody for POST, PUT, PATCH
        String verbLower = verb.toLowerCase();
        if ("post".equals(verbLower) || "put".equals(verbLower) || "patch".equals(verbLower)) {
            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("description", "Request payload");
            requestBody.addProperty("required", true);

            JsonObject content = new JsonObject();
            JsonObject applicationJson = new JsonObject();
            JsonObject schema = new JsonObject();
            schema.addProperty("type", "object");

            applicationJson.add("schema", schema);
            content.add("application/json", applicationJson);
            requestBody.add("content", content);

            op.add("requestBody", requestBody);
        }

        // Responses
        JsonObject responses = new JsonObject();

        JsonObject response200 = new JsonObject();
        response200.addProperty("description", "Successful response");
        JsonObject content200 = new JsonObject();
        JsonObject appJson200 = new JsonObject();
        JsonObject schema200 = new JsonObject();
        schema200.addProperty("type", "object");
        appJson200.add("schema", schema200);
        content200.add("application/json", appJson200);
        response200.add("content", content200);
        responses.add("200", response200);

        if ("post".equals(verbLower)) {
            JsonObject response201 = new JsonObject();
            response201.addProperty("description", "Created. Resource successfully created.");
            JsonObject content201 = new JsonObject();
            JsonObject appJson201 = new JsonObject();
            JsonObject schema201 = new JsonObject();
            schema201.addProperty("type", "object");
            appJson201.add("schema", schema201);
            content201.add("application/json", appJson201);
            response201.add("content", content201);
            responses.add("201", response201);
        }

        if (!pathParams.isEmpty()) {
            JsonObject response404 = new JsonObject();
            response404.addProperty("description", "Not Found. Resource does not exist.");
            JsonObject content404 = new JsonObject();
            JsonObject appJson404 = new JsonObject();
            JsonObject schema404 = new JsonObject();
            schema404.addProperty("type", "object");
            appJson404.add("schema", schema404);
            content404.add("application/json", appJson404);
            response404.add("content", content404);
            responses.add("404", response404);
        }

        op.add("responses", responses);

        // Security
        JsonArray security = new JsonArray();
        JsonObject defaultSecurity = new JsonObject();
        defaultSecurity.add("default", new JsonArray());
        security.add(defaultSecurity);
        op.add("security", security);

        // WSO2 extensions
        op.addProperty("x-auth-type", "Application & Application User");
        op.addProperty("x-throttling-tier", "Unlimited");

        JsonObject appSecurity = new JsonObject();
        JsonArray securityTypes = new JsonArray();
        securityTypes.add("api_key");
        appSecurity.add("security-types", securityTypes);
        appSecurity.addProperty("optional", false);
        op.add("x-wso2-application-security", appSecurity);

        return op;
    }

    /**
     * Builds operation parameters array from extracted policy parameters.
     */
    private static JsonArray buildOperationParameters(PolicyParameters policyParams) {
        JsonArray parameters = new JsonArray();

        if (policyParams == null) {
            return parameters;
        }

        // Query parameters
        for (String queryParam : policyParams.queryParams) {
            JsonObject param = new JsonObject();
            param.addProperty("name", queryParam);
            param.addProperty("in", "query");
            param.addProperty("required", false);
            param.addProperty("style", "form");
            param.addProperty("explode", true);
            param.addProperty("description", "Query parameter extracted from Apigee policies");

            JsonObject schema = new JsonObject();
            schema.addProperty("type", "string");
            param.add("schema", schema);

            parameters.add(param);
        }

        // Header parameters
        for (String headerParam : policyParams.headerParams) {
            JsonObject param = new JsonObject();
            param.addProperty("name", headerParam);
            param.addProperty("in", "header");
            param.addProperty("required", false);
            param.addProperty("style", "simple");
            param.addProperty("explode", false);
            param.addProperty("description", "Header parameter extracted from Apigee policies");

            JsonObject schema = new JsonObject();
            schema.addProperty("type", "string");
            param.add("schema", schema);

            parameters.add(param);
        }

        return parameters;
    }

    /**
     * Builds the OpenAPI components section with security schemes.
     */
    private static JsonObject buildComponents() {
        JsonObject components = new JsonObject();

        JsonObject securitySchemes = new JsonObject();
        JsonObject defaultScheme = new JsonObject();
        defaultScheme.addProperty("type", "oauth2");

        JsonObject flows = new JsonObject();
        JsonObject implicitFlow = new JsonObject();
        implicitFlow.addProperty("authorizationUrl", "https://localhost:9443/oauth2/authorize");
        implicitFlow.add("scopes", new JsonObject());
        flows.add("implicit", implicitFlow);
        defaultScheme.add("flows", flows);

        securitySchemes.add("default", defaultScheme);
        components.add("securitySchemes", securitySchemes);

        return components;
    }
}
