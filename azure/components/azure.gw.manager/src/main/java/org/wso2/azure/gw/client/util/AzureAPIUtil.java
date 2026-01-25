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

package org.wso2.azure.gw.client.util;

import com.azure.core.http.HttpClient;
import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpMethod;
import com.azure.core.http.HttpPipeline;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.HttpResponse;
import com.azure.core.http.rest.PagedIterable;
import com.azure.core.util.Context;
import com.azure.core.util.IterableStream;
import com.azure.resourcemanager.apimanagement.ApiManagementManager;
import com.azure.resourcemanager.apimanagement.fluent.models.PolicyContractInner;
import com.azure.resourcemanager.apimanagement.models.ApiContract;
import com.azure.resourcemanager.apimanagement.models.ApiOperationPoliciesCreateOrUpdateResponse;
import com.azure.resourcemanager.apimanagement.models.ApiPoliciesCreateOrUpdateResponse;
import com.azure.resourcemanager.apimanagement.models.ApiRevisionContract;
import com.azure.resourcemanager.apimanagement.models.ApiVersionSetContract;
import com.azure.resourcemanager.apimanagement.models.ContentFormat;
import com.azure.resourcemanager.apimanagement.models.OperationContract;
import com.azure.resourcemanager.apimanagement.models.PolicyContentFormat;
import com.azure.resourcemanager.apimanagement.models.PolicyIdName;
import com.azure.resourcemanager.apimanagement.models.Protocol;
import com.azure.resourcemanager.apimanagement.models.ApiType;
import com.azure.resourcemanager.apimanagement.models.VersioningScheme;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobClientBuilder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.azure.gw.client.AzureConstants;
import org.wso2.azure.gw.client.model.ExportEnvelope;
import org.wso2.azure.gw.client.policy.AzurePolicyBuilder;
import org.wso2.azure.gw.client.policy.AzurePolicyBuilderFactory;
import org.wso2.azure.gw.client.policy.policies.AzureCORSPolicy;
import org.wso2.azure.gw.client.policy.policies.AzureJWTPolicy;
import org.wso2.azure.gw.client.policy.policies.AzureRateLimitPolicy;
import org.wso2.azure.gw.client.policy.policies.AzureSetHeaderPolicy;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.APIIdentifier;
import org.wso2.carbon.apimgt.api.model.Environment;
import org.wso2.carbon.apimgt.api.model.OperationPolicy;
import org.wso2.carbon.apimgt.api.model.Tier;
import org.wso2.carbon.apimgt.api.model.URITemplate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * This class contains utility methods to interact with Azure API Gateway.
 */
public class AzureAPIUtil {
    private static final Log log = LogFactory.getLog(AzureAPIUtil.class);

    /**
     * Deploys an API to the Azure API Management Gateway.
     *
     * @param api          The API object containing the details to be deployed.
     * @param manager      The Azure ApiManagementManager instance for managing APIs.
     * @param resourceGroup The Azure resource group where the API will be deployed.
     * @param serviceName  The name of the Azure API Management service.
     * @return A JSON string containing the reference artifact with UUID and path, or null if deployment fails.
     */
    public static String deployRestAPI(API api, ApiManagementManager manager, String resourceGroup,
                                       String serviceName) throws APIManagementException {
        try {
            String openAPI = api.getSwaggerDefinition();

            String endpointConfig = api.getEndpointConfig();
            if (StringUtils.isEmpty(endpointConfig)) {
                throw new APIManagementException("Endpoint configuration is empty for API: " + api.getId());
            }
            JsonObject endpointConfigJson = JsonParser.parseString(endpointConfig).getAsJsonObject();
            JsonObject prodEndpoints = endpointConfigJson != null &&
                      endpointConfigJson.has("production_endpoints") &&
                      endpointConfigJson.get("production_endpoints").isJsonObject()
                    ? endpointConfigJson.getAsJsonObject("production_endpoints")
                    : null;
            String productionEndpoint = (prodEndpoints != null
                    && prodEndpoints.has("url")
                    && !prodEndpoints.get("url").isJsonNull())
                    ? prodEndpoints.get("url").getAsString()
                    : null;
            if (productionEndpoint == null) {
                throw new APIManagementException("Production endpoint URL is null for API: " + api.getId());
            }
            productionEndpoint = productionEndpoint.endsWith("/") ?
                    productionEndpoint.substring(0, productionEndpoint.length() - 1) : productionEndpoint;

            List<String> wso2Transports = Arrays.asList(api.getTransports().split(","));
            List<Protocol> azureTransports = new ArrayList<>();
            if (wso2Transports.contains("http")) {
                azureTransports.add(Protocol.HTTP);
            }
            if (wso2Transports.contains("https")) {
                azureTransports.add(Protocol.HTTPS);
            }

            String versionSetId = api.getId().getApiName();
            ApiVersionSetContract versionSetContract = manager.apiVersionSets().define(versionSetId)
                    .withExistingService(resourceGroup, serviceName).withDisplayName(versionSetId)
                    .withVersioningScheme(VersioningScheme.SEGMENT).create();

            ApiContract apiContract = manager.apis()
                    .define(api.getUuid()) // Use UUID as the API name since name needs to be unique
                    .withExistingService(resourceGroup, serviceName)
                    .withDisplayName(api.getId().getApiName())
                    .withPath(getContextWithoutVersion(api.getContext(), api.getId().getVersion()))
                    .withServiceUrl(productionEndpoint)
                    .withValue(openAPI)
                    .withFormat(ContentFormat.OPENAPI)
                    .withApiVersionSetId(versionSetContract.id())
                    .withApiVersion(api.getId().getVersion())
                    .withSubscriptionRequired(false)
                    .withProtocols(azureTransports)
                    .create();

            if (api.getDescription() != null && !api.getDescription().isEmpty()) {
                apiContract.update().withDescription(api.getDescription()).withIfMatch("*").apply();
            }

            if (log.isDebugEnabled()) {
                log.debug("API deployed successfully to Azure Gateway: " + api.getUuid());
            }

            AzurePolicyBuilderFactory policyBuilderFactory = new AzurePolicyBuilderFactory();
            AzurePolicyBuilder apiLevelPolicyBuilder =
                    policyBuilderFactory.newPolicyBuilder();
            apiLevelPolicyBuilder.addPolicy(new AzureCORSPolicy(api.getCorsConfiguration()),
                    AzureConstants.POLICY_DIRECTION_REQUEST);

            //configure API level policies
            List<OperationPolicy> apiPolicies = api.getApiPolicies();
            if (apiPolicies != null) {
                for (OperationPolicy policy : apiPolicies) {
                    addPoliciesToPolicyBuilder(policy, apiLevelPolicyBuilder);
                }
            }

            String apiLevelPolicyContent = apiLevelPolicyBuilder.build();
            if (apiLevelPolicyContent != null) {
                ApiPoliciesCreateOrUpdateResponse response = manager.serviceClient().getApiPolicies().
                        createOrUpdateWithResponse(resourceGroup, serviceName, apiContract.name(), PolicyIdName.POLICY,
                                new PolicyContractInner().withFormat(PolicyContentFormat.XML)
                                        .withValue(apiLevelPolicyContent), "*", Context.NONE);
                if (response.getStatusCode() / 100 != 2) {
                    String errBody = response.getValue().value();
                    log.error("Failed to attach Azure policies: HTTP " + response.getStatusCode() + " body=" + errBody);
                    throw new APIManagementException("Failed to attach Azure policies: HTTP " + response.getStatusCode()
                            + " body=" + errBody);
                }
            }

            // Configure Operation level policies
            IterableStream<OperationContract> operationContracts =
                    manager.apiOperations().listByApi(resourceGroup, serviceName, apiContract.name());

            for (URITemplate resource : api.getUriTemplates()) {
                for (OperationPolicy policy : resource.getOperationPolicies()) {
                    AzurePolicyBuilder operationLevelPolicyBuilder =
                            policyBuilderFactory.newPolicyBuilder();
                    addPoliciesToPolicyBuilder(policy, operationLevelPolicyBuilder);
                    String operationLevelPolicyContent = operationLevelPolicyBuilder.build();

                    PolicyContractInner resourceLevelJWTPolicy = new PolicyContractInner()
                            .withFormat(PolicyContentFormat.XML)
                            .withValue(operationLevelPolicyContent);

                    String operationId = null;
                    for (OperationContract operationContract : operationContracts) {
                        if (operationContract.method().equals(resource.getHTTPVerb()) &&
                                operationContract.urlTemplate().equals(resource.getUriTemplate())) {
                            operationId = operationContract.name();
                            break;
                        }
                    }
                    if (operationId == null) {
                        throw new APIManagementException("Azure API operation not found for resource: " +
                                resource.getUriTemplate());
                    }

                    ApiOperationPoliciesCreateOrUpdateResponse response = manager.serviceClient()
                            .getApiOperationPolicies().createOrUpdateWithResponse(resourceGroup, serviceName,
                                    apiContract.name(), operationId, PolicyIdName.POLICY, resourceLevelJWTPolicy,
                                    "*", Context.NONE);
                }
            }

            if (log.isDebugEnabled()) {
                log.debug("API deployed successfully to Azure Gateway: " + api.getUuid());
            }

            PagedIterable<ApiRevisionContract> revisions = manager.apiRevisions().listByService(resourceGroup,
                serviceName, apiContract.name(), "isCurrent eq true", /* top */ null, /* skip */ null, Context.NONE);
            ApiRevisionContract revisionContract = revisions.stream().findFirst().orElse(null);
            if (revisionContract == null) {
                throw new APIManagementException("Created API Revision not found for api: " + api.getDisplayName());
            }

            return generateReferenceArtifact(api, apiContract, versionSetContract, revisionContract);
        } catch (Exception e) {
            throw new APIManagementException("Error while deploying API to Azure Gateway: " + api.getId(), e);
        }
    }

    /**
     * Deploys a websocket API to the Azure API Management Gateway.
     *
     * @param api          The API object containing the details to be deployed.
     * @param manager      The Azure ApiManagementManager instance for managing APIs.
     * @param resourceGroup The Azure resource group where the API will be deployed.
     * @param serviceName  The name of the Azure API Management service.
     * @return A JSON string containing the reference artifact with UUID and path, or null if deployment fails.
     */
    public static String deployWebSocketAPI(API api, ApiManagementManager manager, String resourceGroup,
                                       String serviceName) throws APIManagementException {
        try{
            String endpointConfig = api.getEndpointConfig();
            if (StringUtils.isEmpty(endpointConfig)) {
                throw new APIManagementException("Endpoint configuration is empty for API: " + api.getId());
            }
            JsonObject endpointConfigJson = JsonParser.parseString(endpointConfig).getAsJsonObject();
            JsonObject prodEndpoints = endpointConfigJson != null &&
                      endpointConfigJson.has("production_endpoints") &&
                      endpointConfigJson.get("production_endpoints").isJsonObject()
                    ? endpointConfigJson.getAsJsonObject("production_endpoints")
                    : null;
            String productionEndpoint = (prodEndpoints != null
                    && prodEndpoints.has("url")
                    && !prodEndpoints.get("url").isJsonNull())
                    ? prodEndpoints.get("url").getAsString()
                    : null;
            if (productionEndpoint == null) {
                throw new APIManagementException("Production endpoint URL is null for API: " + api.getId());
            }
            productionEndpoint = productionEndpoint.endsWith("/") ?
                    productionEndpoint.substring(0, productionEndpoint.length() - 1) : productionEndpoint;

            List<String> wso2Transports = Arrays.asList(api.getTransports().split(","));
            List<Protocol> azureTransports = new ArrayList<>();
            if (wso2Transports.contains("ws")) {
                azureTransports.add(Protocol.WS);
            }
            if (wso2Transports.contains("wss")) {
                azureTransports.add(Protocol.WSS);
            }
            if (azureTransports.isEmpty()) {
                azureTransports.add(Protocol.WS);
                azureTransports.add(Protocol.WSS);
            }

            String versionSetId = api.getId().getApiName();
            ApiVersionSetContract versionSetContract = manager.apiVersionSets().define(versionSetId)
                    .withExistingService(resourceGroup, serviceName).withDisplayName(versionSetId)
                    .withVersioningScheme(VersioningScheme.SEGMENT).create();

            ApiContract apiContract = manager.apis()
                    .define(api.getUuid()) // Use UUID as the API name since name needs to be unique
                    .withExistingService(resourceGroup, serviceName)
                    .withDisplayName(api.getId().getApiName())
                    .withPath(getContextWithoutVersion(api.getContext(), api.getId().getVersion()))
                    .withServiceUrl(productionEndpoint)
                    .withApiType(ApiType.WEBSOCKET)
                    .withApiVersionSetId(versionSetContract.id())
                    .withApiVersion(api.getId().getVersion())
                    .withSubscriptionRequired(false)
                    .withProtocols(azureTransports)
                    .create();

            if (api.getDescription() != null && !api.getDescription().isEmpty()) {
                apiContract.update().withDescription(api.getDescription()).withIfMatch("*").apply();
            }

            if (log.isDebugEnabled()) {
                log.debug("API deployed successfully to Azure Gateway: " + api.getUuid());
            }

            PagedIterable<ApiRevisionContract> revisions = manager.apiRevisions().listByService(resourceGroup,
                serviceName, apiContract.name(), "isCurrent eq true", /* top */ null, /* skip */ null, Context.NONE);
            ApiRevisionContract revisionContract = revisions.stream().findFirst().orElse(null);
            if (revisionContract == null) {
                throw new APIManagementException("Created API Revision not found for api: " + api.getDisplayName());
            }

            return generateReferenceArtifact(api, apiContract, versionSetContract, revisionContract);
        }catch (Exception e){
            throw new APIManagementException("Error while deploying WebSocket API to Azure Gateway: " + api.getId(), e);
        }
    }

    public static String generateReferenceArtifact(API api, ApiContract apiContract,
                                                   ApiVersionSetContract versionSetContract,
                                                   ApiRevisionContract apiRevisionContract) {
        JsonObject referenceArtifact = new JsonObject();
        referenceArtifact.addProperty(AzureConstants.AZURE_EXTERNAL_REFERENCE_UUID, api.getUuid());
        referenceArtifact.addProperty(AzureConstants.AZURE_EXTERNAL_REFERENCE_CONTEXT, api.getContext());
        referenceArtifact.addProperty(AzureConstants.AZURE_EXTERNAL_REFERENCE_ID, apiContract.id());
        referenceArtifact.addProperty(AzureConstants.AZURE_EXTERNAL_REFERENCE_ARTIFACT_TYPE, apiContract.type());
        referenceArtifact.addProperty(AzureConstants.AZURE_EXTERNAL_REFERENCE_DISPLAY_NAME,
                apiContract.displayName());
        referenceArtifact.addProperty(AzureConstants.AZURE_EXTERNAL_REFERENCE_VERSION, apiContract.apiVersion());
        referenceArtifact.addProperty(AzureConstants.AZURE_EXTERNAL_REFERENCE_PATH, apiContract.path());
        referenceArtifact.addProperty(AzureConstants.AZURE_EXTERNAL_REFERENCE_SERVICE_URL,
                apiContract.serviceUrl());
        referenceArtifact.addProperty(AzureConstants.AZURE_EXTERNAL_REFERENCE_VERSION_SET_ID,
                apiContract.apiVersionSetId());
        if (versionSetContract != null) {
            referenceArtifact.addProperty(
                    AzureConstants.AZURE_EXTERNAL_REFERENCE_VERSIONING_SCHEME,
                    versionSetContract.versioningScheme().toString());
        }
        referenceArtifact.addProperty(AzureConstants.AZURE_EXTERNAL_REFERENCE_CREATED_TIME_EPOCH,
                apiRevisionContract.createdDateTime().toInstant().toEpochMilli());
        Gson gson = new Gson();
        return gson.toJson(referenceArtifact);
    }

    private static void addPoliciesToPolicyBuilder(OperationPolicy policy, AzurePolicyBuilder policyBuilder)
            throws APIManagementException {
        if (policy.getPolicyName().equals(AzureConstants.AZURE_OAUTH2_OPERATION_POLICY_NAME)) {
            String openIdURL = policy.getParameters()
                    .get(AzureConstants.AZURE_OAUTH2_OPERATION_POLICY_PARAMETER_OPENID_URL).toString();
            policyBuilder.addPolicy(new AzureJWTPolicy(openIdURL), policy.getDirection());
        } else if (policy.getPolicyName().equals(AzureConstants.AZURE_SET_HEADER_POLICY_NAME)) {
            String headerName = policy.getParameters()
                    .get(AzureConstants.AZURE_SET_HEADER_POLICY_HEADER_NAME).toString();
            String headerValue = policy.getParameters()
                    .get(AzureConstants.AZURE_SET_HEADER_POLICY_HEADER_VALUE).toString();
            String existsAction = policy.getParameters()
                    .get(AzureConstants.AZURE_SET_HEADER_POLICY_EXISTS_ACTION).toString();
            policyBuilder.addPolicy(new AzureSetHeaderPolicy(headerName, headerValue,
                    existsAction), policy.getDirection());
        } else if (policy.getPolicyName().equals(AzureConstants.AZURE_RATE_LIMIT_POLICY_NAME)) {
            String calls = policy.getParameters().get(AzureConstants.AZURE_RATE_LIMIT_POLICY_CALLS).toString();
            String renewalPeriod = policy.getParameters().get(AzureConstants.AZURE_RATE_LIMIT_POLICY_RENEWAL_PERIOD)
                    .toString();

            Object retryAfterHeaderNameObject = policy.getParameters()
                    .get(AzureConstants.AZURE_RATE_LIMIT_POLICY_RETRY_AFTER_HEADER_NAME);
            String retryAfterHeaderName =
                    retryAfterHeaderNameObject != null ? retryAfterHeaderNameObject.toString() : null;

            Object retryAfterVariableNameObject = policy.getParameters()
                    .get(AzureConstants.AZURE_RATE_LIMIT_POLICY_RETRY_AFTER_VARIABLE_NAME);
            String retryAfterVariableName =
                    retryAfterVariableNameObject != null ? retryAfterVariableNameObject.toString() : null;

            Object remainingCallsHeaderNameObject = policy.getParameters()
                    .get(AzureConstants.AZURE_RATE_LIMIT_POLICY_REMAINING_CALLS_HEADER_NAME);
            String remainingCallsHeaderName =
                    remainingCallsHeaderNameObject != null ? remainingCallsHeaderNameObject.toString() : null;

            Object remainingCallsVariableNameObject = policy.getParameters()
                    .get(AzureConstants.AZURE_RATE_LIMIT_POLICY_REMAINING_CALLS_VARIABLE_NAME);
            String remainingCallsVariableName =
                    remainingCallsVariableNameObject != null ? remainingCallsVariableNameObject.toString() : null;

            Object totalCallsHeaderNameObject = policy.getParameters()
                    .get(AzureConstants.AZURE_RATE_LIMIT_POLICY_TOTAL_CALLS_HEADER_NAME);
            String totalCallsHeaderName =
                    totalCallsHeaderNameObject != null ? totalCallsHeaderNameObject.toString() : null;
            policyBuilder.addPolicy(new AzureRateLimitPolicy(calls, renewalPeriod, retryAfterHeaderName,
                    retryAfterVariableName, remainingCallsHeaderName, remainingCallsVariableName, totalCallsHeaderName),
                    policy.getDirection());
        } else {
            throw new APIManagementException("Unsupported Azure policy: " + policy.getPolicyName());
        }
    }

    private static String getContextWithoutVersion (String contextWithVersion, String version) {
        if (contextWithVersion == null || version == null) {
            return contextWithVersion;
        }
        return contextWithVersion.replace("/" + version, "");
    }

    /**
     * Deletes a deployed API from the Azure API Management Gateway.
     *
     * @param externalReference The external reference containing the UUID of the API to be deleted.
     * @param manager           The Azure ApiManagementManager instance for managing APIs.
     * @param resourceGroup     The Azure resource group where the API is deployed.
     * @param serviceName       The name of the Azure API Management service.
     * @return true
     * @throws APIManagementException If there is an error during the deletion process.
     */
    public static boolean deleteDeployment(String externalReference, ApiManagementManager manager, String resourceGroup,
                                           String serviceName) throws APIManagementException {
        JsonObject root = JsonParser.parseString(externalReference).getAsJsonObject();
        String uuid = root.get(AzureConstants.AZURE_EXTERNAL_REFERENCE_UUID).getAsString();
        manager.apis().delete(resourceGroup, serviceName, uuid, "*", true, Context.NONE);
        return true;
    }

    public static String getRestApiDefinition(ApiManagementManager manager, HttpClient httpClient, ApiContract api)
            throws APIManagementException {
        // This method is implemented with raw REST api calls as the sdk is buggy at the moment

        // Export API
        HttpPipeline pipeline = manager.serviceClient().getHttpPipeline();
        String exportUrl = "https://management.azure.com" + api.id() + "?format=" +
                AzureConstants.AZURE_OPENAPI_EXPORT_FORMAT + "&export=true&api-version=" +
                AzureConstants.AZURE_OPENAPI_EXPORT_VERSION;
        HttpRequest exportReq = new HttpRequest(HttpMethod.GET, exportUrl)
                .setHeaders(new HttpHeaders().set("Accept", "application/json"));
        HttpResponse exportRes = pipeline.send(exportReq).block();
        if (exportRes == null) {
            throw new APIManagementException("No response from ARM export endpoint");
        }
        if (exportRes.getStatusCode() / 100 != 2) {
            String errBody = exportRes.getBodyAsString().block();
            throw new APIManagementException(
                    "Export failed: HTTP " + exportRes.getStatusCode() + " body=" + errBody);
        }
        String exportBody = exportRes.getBodyAsString().block();
        if (exportBody == null || exportBody.isEmpty()) {
            throw new APIManagementException("Export returned empty body");
        }

        ObjectMapper objectMapper = new ObjectMapper();
        ExportEnvelope envelope = null;
        try {
            envelope = objectMapper.readValue(exportBody, ExportEnvelope.class);
        } catch (JsonProcessingException e) {
            throw new APIManagementException("Error parsing export response: " + e.getMessage(), e);
        }

        String content;
        try {
            String sasLink = envelope.getLink();
            BlobClient blob = new BlobClientBuilder()
                    .httpClient(httpClient)
                    .endpoint(sasLink).
                    buildClient();
            content = blob.downloadContent().toString();
        } catch (Exception e) {
            throw new APIManagementException("Failed to download OpenAPI: " + e.getMessage(), e);
        }
        if (content == null) {
            throw new APIManagementException("Downloaded OpenAPI content was null");
        }

        return content;
    }

    public static API restAPItoAPI(ApiContract apiContract, String apiDefinition, String organization,
                                   Environment environment) {
        APIIdentifier apiIdentifier = new APIIdentifier("admin", apiContract.displayName(),
                apiContract.apiVersion() != null ? apiContract.apiVersion() : "1.0.0");

        API api = new API(apiIdentifier);

        String context = "/";
        context += apiContract.path().isEmpty() ? AzureConstants.AZURE_NO_CONTEXT : apiContract.path();
        String contextTemplate = context + "/{version}";
        context += "/" + apiIdentifier.getVersion();

        api.setDisplayName(apiContract.displayName());
        api.setUuid(UUID.randomUUID().toString());
        api.setDescription(apiContract.description());
        api.setContext(context);
        api.setContextTemplate(contextTemplate);
        api.setOrganization(organization);
        api.setSwaggerDefinition(apiDefinition);
        api.setRevision(false);
        api.setInitiatedFromGateway(true);
        api.setGatewayVendor("external");
        api.setGatewayType(environment.getGatewayType());
        if (apiContract.serviceUrl() != null) {
            api.setEndpointConfig(AzureAPIUtil.buildEndpointConfigJson(
                    apiContract.serviceUrl(), apiContract.serviceUrl(), false, false));
        }
        api.setAvailableTiers(new HashSet<>(java.util.Collections.singleton(new Tier("Unlimited"))));
        return api;
    }

 
    /**websocket API to wso2 API*/
    public static API websocketAPIToAPI(ApiContract apiContract, String organization,
                                        Environment environment) {
        boolean isDefaultVersion = apiContract.apiVersion() == null;
        APIIdentifier apiIdentifier = new APIIdentifier(
            "admin",
            apiContract.displayName(),
            isDefaultVersion ? "1.0.0" : apiContract.apiVersion()
        );                                        
        API api = new API(apiIdentifier); 

        String context = "/";
        String path = apiContract.path().isEmpty() ? AzureConstants.AZURE_NO_CONTEXT : apiContract.path();
        context += getContextWithoutVersion(path, apiIdentifier.getVersion());
        String contextTemplate = context;

        api.setDisplayName(apiContract.displayName());
        api.setUuid(UUID.randomUUID().toString());
        api.setDescription(apiContract.description());
        api.setContext(context);
        api.setContextTemplate(contextTemplate);
        api.setOrganization(organization);
        api.setRevision(false);
        api.setInitiatedFromGateway(true);
        if (isDefaultVersion) {
            api.setAsDefaultVersion(true);
        }
        api.setGatewayVendor("external");
        api.setGatewayType(environment.getGatewayType());
        api.setType("WS");
        api.setTransports("ws,wss");
        String asyncApiDefinition = loadAsyncApiTemplate(apiContract.displayName(), apiIdentifier.getVersion());


        api.setAsyncApiDefinition(asyncApiDefinition);
        api.setSwaggerDefinition(asyncApiDefinition);
        if (apiContract.serviceUrl() != null) {
            api.setEndpointConfig(AzureAPIUtil.buildEndpointConfigJson(
                    apiContract.serviceUrl(), apiContract.serviceUrl(), false, true));
        }
        api.setAvailableTiers(new HashSet<>(java.util.Collections.singleton(new Tier("Unlimited"))));
        return api; 
    }

    /**
     * Build endpointConfig JSON using Gson.
     * Both production and sandbox endpoints are included.
     */

    private static String loadAsyncApiTemplate(String title, String version) {

        try (java.io.InputStream inputStream = AzureAPIUtil.class.getClassLoader()
                .getResourceAsStream("asyncapi-template.json")) {
            if (inputStream == null) {
                log.error("AsyncAPI template file not found in resources");
                return null;
            }
            try (java.util.Scanner scanner = new java.util.Scanner(inputStream, "UTF-8")) {
                String content = scanner.useDelimiter("\\A").next();
                return content.replace("${TITLE}", title)
                        .replace("${VERSION}", version);
            }
        } catch (java.io.IOException e) {
            log.error("Error loading AsyncAPI template", e);
            return null;
        }
    }

    public static String buildEndpointConfigJson(String productionUrl, String sandboxUrl, boolean failOver, boolean isWebSocket) {
        JsonObject endpointConfig = new JsonObject();
        endpointConfig.addProperty("endpoint_type", isWebSocket ? "ws" : "http");
        endpointConfig.addProperty("failOver", failOver);

        JsonObject prod = new JsonObject();
        prod.addProperty("template_not_supported", false);
        prod.addProperty("url", productionUrl);

        JsonObject sand = new JsonObject();
        sand.addProperty("template_not_supported", false);
        sand.addProperty("url", sandboxUrl);

        endpointConfig.add("production_endpoints", prod);
        endpointConfig.add("sandbox_endpoints", sand);

        return endpointConfig.toString();
    }
}
