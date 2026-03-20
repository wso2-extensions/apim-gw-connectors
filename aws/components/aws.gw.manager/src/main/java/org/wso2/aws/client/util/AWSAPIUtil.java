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

package org.wso2.aws.client.util;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.APIIdentifier;
import org.wso2.carbon.apimgt.api.model.Environment;
import org.wso2.carbon.apimgt.api.model.OperationPolicy;
import org.wso2.carbon.apimgt.api.model.Tier;
import org.wso2.carbon.apimgt.api.model.URITemplate;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.CreateAuthorizerRequest;
import software.amazon.awssdk.services.apigateway.model.CreateAuthorizerResponse;
import software.amazon.awssdk.services.apigateway.model.CreateDeploymentRequest;
import software.amazon.awssdk.services.apigateway.model.CreateDeploymentResponse;
import software.amazon.awssdk.services.apigateway.model.DeleteAuthorizerRequest;
import software.amazon.awssdk.services.apigateway.model.DeleteDeploymentRequest;
import software.amazon.awssdk.services.apigateway.model.DeleteRestApiRequest;
import software.amazon.awssdk.services.apigateway.model.DeleteStageRequest;
import software.amazon.awssdk.services.apigateway.model.Deployment;
import software.amazon.awssdk.services.apigateway.model.GetAuthorizersRequest;
import software.amazon.awssdk.services.apigateway.model.GetAuthorizersResponse;
import software.amazon.awssdk.services.apigateway.model.GetDeploymentsRequest;
import software.amazon.awssdk.services.apigateway.model.GetDeploymentsResponse;
import software.amazon.awssdk.services.apigateway.model.GetExportRequest;
import software.amazon.awssdk.services.apigateway.model.GetExportResponse;
import software.amazon.awssdk.services.apigateway.model.GetIntegrationRequest;
import software.amazon.awssdk.services.apigateway.model.GetIntegrationResponse;
import software.amazon.awssdk.services.apigateway.model.GetMethodRequest;
import software.amazon.awssdk.services.apigateway.model.GetMethodResponse;
import software.amazon.awssdk.services.apigateway.model.GetResourcesRequest;
import software.amazon.awssdk.services.apigateway.model.GetResourcesResponse;
import software.amazon.awssdk.services.apigateway.model.GetRestApiRequest;
import software.amazon.awssdk.services.apigateway.model.GetRestApiResponse;
import software.amazon.awssdk.services.apigateway.model.GetRestApisRequest;
import software.amazon.awssdk.services.apigateway.model.GetRestApisResponse;
import software.amazon.awssdk.services.apigateway.model.GetStagesRequest;
import software.amazon.awssdk.services.apigateway.model.GetStagesResponse;
import software.amazon.awssdk.services.apigateway.model.ImportRestApiRequest;
import software.amazon.awssdk.services.apigateway.model.ImportRestApiResponse;
import software.amazon.awssdk.services.apigateway.model.IntegrationType;
import software.amazon.awssdk.services.apigateway.model.Method;
import software.amazon.awssdk.services.apigateway.model.Op;
import software.amazon.awssdk.services.apigateway.model.PatchOperation;
import software.amazon.awssdk.services.apigateway.model.PutIntegrationRequest;
import software.amazon.awssdk.services.apigateway.model.PutIntegrationResponseRequest;
import software.amazon.awssdk.services.apigateway.model.PutMode;
import software.amazon.awssdk.services.apigateway.model.PutRestApiRequest;
import software.amazon.awssdk.services.apigateway.model.PutRestApiResponse;
import software.amazon.awssdk.services.apigateway.model.Resource;
import software.amazon.awssdk.services.apigateway.model.RestApi;
import software.amazon.awssdk.services.apigateway.model.UpdateMethodRequest;
import software.amazon.awssdk.services.apigateway.model.UpdateRestApiRequest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.wso2.aws.client.AWSConstants.AWS_APIKEY_AUTHORIZER_POLICY_NAME;
import static org.wso2.aws.client.AWSConstants.DEFAULT_VERSION;
import static org.wso2.aws.client.AWSConstants.JSON_PAYLOAD_TYPE;
import static org.wso2.aws.client.AWSConstants.OPEN_API_VERSION;
import static org.wso2.aws.client.AWSConstants.OPERATION_POLICY_API;
import static org.wso2.aws.client.AWSConstants.OPERATION_POLICY_ARN_PARAMETER;
import static org.wso2.aws.client.AWSConstants.OPERATION_POLICY_ROLE_PARAMETER;
import static org.wso2.aws.client.AWSConstants.PRODUCTION_ENDPOINTS;
import static org.wso2.aws.client.AWSConstants.SANDBOX_ENDPOINTS;
import static org.wso2.aws.client.AWSConstants.URL_PROP;

/**
 * This class contains utility methods to interact with AWS API Gateway
 */
public class AWSAPIUtil {
    private static final Log log = LogFactory.getLog(AWSAPIUtil.class);
    private static final String API_KEY_SECURITY = "api_key";
    private static final String API_KEY_SOURCE_HEADER = "HEADER";
    private static final String AWS_API_KEY_HEADER = "x-api-key";
    private static final String AWS_REFERENCE_API_KEY_ENABLED = "apiKeySecurityEnabled";
    private static final String AWS_REFERENCE_API_KEY_HEADER = "apiKeyHeader";

    public static String importRestAPI(API api, ApiGatewayClient apiGatewayClient, String region,
                                       String stage) throws APIManagementException {

        String openAPI = api.getSwaggerDefinition();
        String apiId = null;

        try {
            ImportRestApiRequest importApiRequest = ImportRestApiRequest.builder()
                    .body(SdkBytes.fromUtf8String(openAPI))
                    .failOnWarnings(false)
                    .build();

            //import rest API with the openapi definition
            ImportRestApiResponse importApiResponse = apiGatewayClient.importRestApi(importApiRequest);
            apiId = importApiResponse.id();
            if (requiresNativeApiKey(api)) {
                apiGatewayClient.updateRestApi(UpdateRestApiRequest.builder()
                        .restApiId(apiId)
                        .patchOperations(PatchOperation.builder().op(Op.REPLACE).path("/apiKeySource")
                                .value(API_KEY_SOURCE_HEADER).build())
                        .build());
            }

            //add integrations for each resource
            GetResourcesRequest getResourcesRequest = GetResourcesRequest.builder().restApiId(apiId).build();
            GetResourcesResponse getResourcesResponse = apiGatewayClient.getResources(getResourcesRequest);

            String endpointConfig = api.getEndpointConfig();
            JSONParser parser = new JSONParser();
            JSONObject endpointConfigJson = (JSONObject) parser.parse(endpointConfig);
            JSONObject prodEndpoints = (JSONObject) endpointConfigJson.get("production_endpoints");
            String productionEndpoint = (String) prodEndpoints.get("url");

            productionEndpoint = productionEndpoint.charAt(productionEndpoint.length() - 1) == '/' ?
                    productionEndpoint.substring(0, productionEndpoint.length() - 1) : productionEndpoint;

            List<Resource> resources = getResourcesResponse.items();
            for (Resource resource : resources) {
                Map<String, Method> resourceMethods = resource.resourceMethods();
                if (!resourceMethods.isEmpty()) {
                    //check and configure CORS
                    GatewayUtil.configureOptionsCallForCORS(apiId, resource, apiGatewayClient);

                    for (Map.Entry entry : resourceMethods.entrySet()) {
                        GetMethodRequest getMethodRequest = GetMethodRequest.builder()
                                .restApiId(apiId)
                                .resourceId(resource.id())
                                .httpMethod(entry.getKey().toString())
                                .build();
                        GetMethodResponse getMethodResponse = apiGatewayClient.getMethod(getMethodRequest);
                        Map<String, Boolean> requestParamsFromMethod = getMethodResponse.requestParameters();

                        Map<String, String> requestParametersToBeAddedInIntegration = new HashMap<>();

                        //check for request params and add required mapping in integration
                        for (Map.Entry<String, Boolean> paramEntry : requestParamsFromMethod.entrySet()) {
                            String key = paramEntry.getKey();
                            String paramName = key.substring(key.lastIndexOf(".") + 1);

                            String prefix = "method.request.";
                            int startIndex = key.indexOf(prefix) + prefix.length();
                            int endIndex = key.indexOf('.', startIndex);
                            String location = key.substring(startIndex, endIndex != -1 ? endIndex : key.length());

                            requestParametersToBeAddedInIntegration.put(
                                    "integration.request." + location + "." + paramName,
                                    "method.request." + location + "." + paramName);
                        }

                        PutIntegrationRequest putIntegrationRequest = PutIntegrationRequest.builder()
                                .httpMethod(entry.getKey().toString())
                                .integrationHttpMethod(entry.getKey().toString())
                                .resourceId(resource.id())
                                .restApiId(apiId)
                                .type(IntegrationType.HTTP)
                                .requestParameters(requestParametersToBeAddedInIntegration)
                                .uri(productionEndpoint + resource.path())
                                .build();
                        apiGatewayClient.putIntegration(putIntegrationRequest);

                        //Configure default output mapping
                        PutIntegrationResponseRequest putIntegrationResponseRequest =
                                PutIntegrationResponseRequest.builder()
                                        .httpMethod(entry.getKey().toString())
                                        .resourceId(resource.id())
                                        .restApiId(apiId)
                                        .statusCode("200")
                                        .responseTemplates(Map.of("application/json", ""))
                                        .build();
                        apiGatewayClient.putIntegrationResponse(putIntegrationResponseRequest);

                // Check and process API Key Authorizer policy
                String authorizerId = processApiKeyAuthorizerPolicy(api, apiId, region, apiGatewayClient);
                if (authorizerId != null && !"OPTIONS".equalsIgnoreCase(entry.getKey().toString())) {
                    // Attach authorizer to method
                    attachAuthorizerToMethod(apiId, resource.id(), entry.getKey().toString(), authorizerId,
                            apiGatewayClient);
                } else if (requiresNativeApiKey(api) &&
                        !"OPTIONS".equalsIgnoreCase(entry.getKey().toString())) {
                    // Fallback to native AWS API key if no custom authorizer is configured
                    List<PatchOperation> patchOperations = new ArrayList<>();
                    patchOperations.add(PatchOperation.builder().op(Op.REPLACE).path("/apiKeyRequired")
                            .value("true").build());
                    UpdateMethodRequest updateMethodRequest = UpdateMethodRequest.builder().restApiId(apiId)
                            .resourceId(resource.id()).httpMethod(entry.getKey().toString())
                            .patchOperations(patchOperations).build();
                    apiGatewayClient.updateMethod(updateMethodRequest);
                }

                //configure CORS Headers at request Method level
                GatewayUtil.configureCORSHeadersAtMethodLevel(apiId, resource, entry.getKey().toString(),
                        apiGatewayClient);
            }
        }
    }

    CreateDeploymentRequest createDeploymentRequest = CreateDeploymentRequest.builder().restApiId(apiId)
            .stageName(stage).build();
    apiGatewayClient.createDeployment(createDeploymentRequest);
} catch (Exception e) {
    try {
        GatewayUtil.rollbackDeployment(apiGatewayClient, apiId);
    } catch (APIManagementException ex) {
        throw new APIManagementException("Error occurred while rolling back deployment: " + ex.getMessage());
    }
    throw new APIManagementException("Error occurred while importing API: " + e.getMessage());
}

GetRestApiRequest getRestApiRequest = GetRestApiRequest.builder().restApiId(apiId).build();

return apiGatewayClient.getRestApi(getRestApiRequest).toString();
}

    public static String reimportRestAPI(String referenceArtifact, API api, ApiGatewayClient apiGatewayClient,
                                         String region, String stage) throws APIManagementException {
        String awsApiId = GatewayUtil.getAWSApiIdFromReferenceArtifact(referenceArtifact);
        try {
            String openAPI = api.getSwaggerDefinition();

            PutRestApiRequest reimportApiRequest = PutRestApiRequest.builder()
                    .restApiId(awsApiId)
                    .body(SdkBytes.fromUtf8String(openAPI))
                    .failOnWarnings(false)
                    .mode(PutMode.OVERWRITE)
                    .build();
            PutRestApiResponse reimportApiResponse = apiGatewayClient.putRestApi(reimportApiRequest);

            awsApiId = reimportApiResponse.id();
            if (requiresNativeApiKey(api)) {
                apiGatewayClient.updateRestApi(UpdateRestApiRequest.builder()
                        .restApiId(awsApiId)
                        .patchOperations(PatchOperation.builder().op(Op.REPLACE).path("/apiKeySource")
                                .value(API_KEY_SOURCE_HEADER).build())
                        .build());
            }

            //add integrations for each resource
            GetResourcesRequest getResourcesRequest = GetResourcesRequest.builder()
                    .restApiId(awsApiId)
                    .build();
            GetResourcesResponse getResourcesResponse = apiGatewayClient.getResources(getResourcesRequest);

            String endpointConfig = api.getEndpointConfig();
            JSONParser parser = new JSONParser();
            JSONObject endpointConfigJson = (JSONObject) parser.parse(endpointConfig);
            JSONObject prodEndpoints = (JSONObject) endpointConfigJson.get("production_endpoints");
            String productionEndpoint = (String) prodEndpoints.get("url");

            productionEndpoint = productionEndpoint.charAt(productionEndpoint.length() - 1) == '/' ?
                    productionEndpoint.substring(0, productionEndpoint.length() - 1) : productionEndpoint;

            List<Resource> resources = getResourcesResponse.items();
            for (Resource resource : resources) {
                Map<String, Method> resourceMethods = resource.resourceMethods();
                if (!resourceMethods.isEmpty()) {
                    //check and configure CORS
                    GatewayUtil.configureOptionsCallForCORS(awsApiId, resource, apiGatewayClient);

                    for (Map.Entry entry : resourceMethods.entrySet()) {
                        GetMethodRequest getMethodRequest = GetMethodRequest.builder()
                                .restApiId(awsApiId)
                                .resourceId(resource.id())
                                .httpMethod(entry.getKey().toString())
                                .build();
                        GetMethodResponse getMethodResponse = apiGatewayClient.getMethod(getMethodRequest);
                        Map<String, Boolean> requestParamsFromMethod = getMethodResponse.requestParameters();

                        Map<String, String> requestParametersToBeAddedInIntegration = new HashMap<>();

                        //check for request params and add required mapping in integration
                        for (Map.Entry<String, Boolean> paramEntry : requestParamsFromMethod.entrySet()) {
                            String key = paramEntry.getKey();
                            String paramName = key.substring(key.lastIndexOf(".") + 1);

                            String prefix = "method.request.";
                            int startIndex = key.indexOf(prefix) + prefix.length();
                            int endIndex = key.indexOf('.', startIndex);
                            String location = key.substring(startIndex, endIndex != -1 ? endIndex : key.length());

                            requestParametersToBeAddedInIntegration.put(
                                    "integration.request." + location + "." + paramName,
                                    "method.request." + location + "." + paramName);
                        }

                        PutIntegrationRequest putIntegrationRequest = PutIntegrationRequest.builder()
                                .httpMethod(entry.getKey().toString())
                                .integrationHttpMethod(entry.getKey().toString())
                                .resourceId(resource.id())
                                .restApiId(awsApiId)
                                .requestParameters(requestParametersToBeAddedInIntegration)
                                .type(IntegrationType.HTTP)
                                .uri(productionEndpoint + resource.path())
                                .build();
                        apiGatewayClient.putIntegration(putIntegrationRequest);

                        //Configure default output mapping
                        PutIntegrationResponseRequest putIntegrationResponseRequest =
                                PutIntegrationResponseRequest.builder()
                                        .httpMethod(entry.getKey().toString())
                                        .resourceId(resource.id())
                                        .restApiId(awsApiId)
                                        .statusCode("200")
                                        .responseTemplates(Map.of(JSON_PAYLOAD_TYPE, ""))
                                        .build();
                        apiGatewayClient.putIntegrationResponse(putIntegrationResponseRequest);

                // Check and process API Key Authorizer policy
                String authorizerId = processApiKeyAuthorizerPolicy(api, awsApiId, region, apiGatewayClient);
                if (authorizerId != null && !"OPTIONS".equalsIgnoreCase(entry.getKey().toString())) {
                    // Attach authorizer to method
                    attachAuthorizerToMethod(awsApiId, resource.id(), entry.getKey().toString(), authorizerId,
                            apiGatewayClient);
                } else if (requiresNativeApiKey(api) &&
                        !"OPTIONS".equalsIgnoreCase(entry.getKey().toString())) {
                    // Fallback to native AWS API key if no custom authorizer is configured
                    List<PatchOperation> patchOperations = new ArrayList<>();
                    patchOperations.add(PatchOperation.builder().op(Op.REPLACE).path("/apiKeyRequired")
                            .value("true").build());
                    UpdateMethodRequest updateMethodRequest = UpdateMethodRequest.builder().restApiId(awsApiId)
                            .resourceId(resource.id()).httpMethod(entry.getKey().toString())
                            .patchOperations(patchOperations).build();
                    apiGatewayClient.updateMethod(updateMethodRequest);
                }

                //configure CORS Headers at request Method level
                GatewayUtil.configureCORSHeadersAtMethodLevel(awsApiId, resource, entry.getKey().toString(),
                        apiGatewayClient);
            }
        }
    }

    // re-deploy API
    CreateDeploymentRequest createDeploymentRequest = CreateDeploymentRequest.builder().restApiId(awsApiId)
            .stageName(stage).build();
    CreateDeploymentResponse createDeploymentResponse =
            apiGatewayClient.createDeployment(createDeploymentRequest);
    String deploymentId = createDeploymentResponse.id();

    GetDeploymentsRequest getDeploymentsRequest = GetDeploymentsRequest.builder().restApiId(awsApiId).build();
    GetDeploymentsResponse getDeploymentsResponse = apiGatewayClient.getDeployments(getDeploymentsRequest);
    List<Deployment> deployments = getDeploymentsResponse.items();
    for (Deployment deployment : deployments) {
        if (!deployment.id().equals(deploymentId)) {
            DeleteDeploymentRequest deleteDeploymentRequest = DeleteDeploymentRequest.builder()
                    .deploymentId(deployment.id())
                    .restApiId(awsApiId)
                    .build();
            apiGatewayClient.deleteDeployment(deleteDeploymentRequest);
        }
    }

    GetRestApiRequest getRestApiRequest = GetRestApiRequest.builder().restApiId(awsApiId).build();
    return apiGatewayClient.getRestApi(getRestApiRequest).toString();
        } catch (Exception e) {
            throw new APIManagementException("Error occurred while re-importing API: " + e.getMessage());
        }
    }

    public static void deleteDeployment(String referenceArtifact, ApiGatewayClient apiGatewayClient, String stage)
            throws APIManagementException {
        String awsApiId = GatewayUtil.getAWSApiIdFromReferenceArtifact(referenceArtifact);
        GetStagesRequest getStagesRequest = GetStagesRequest.builder().restApiId(awsApiId).build();
        GetStagesResponse getStagesResponse = apiGatewayClient.getStages(getStagesRequest);
        if (getStagesResponse.item() != null && !getStagesResponse.item().isEmpty()) {
            // Delete the stage before deleting the deployment
            DeleteStageRequest deleteStageRequest = DeleteStageRequest.builder()
                    .restApiId(awsApiId)
                    .stageName(stage)
                    .build();
            apiGatewayClient.deleteStage(deleteStageRequest);

            GetDeploymentsRequest getDeploymentsRequest = GetDeploymentsRequest.builder().restApiId(awsApiId).build();
            GetDeploymentsResponse getDeploymentsResponse = apiGatewayClient.getDeployments(getDeploymentsRequest);
            List<Deployment> deployments = getDeploymentsResponse.items();
            for (Deployment deployment : deployments) {
                DeleteDeploymentRequest deleteDeploymentRequest = DeleteDeploymentRequest.builder()
                        .deploymentId(deployment.id())
                        .restApiId(awsApiId)
                        .build();
                apiGatewayClient.deleteDeployment(deleteDeploymentRequest);
            }
        }
    }

    /**
     * This method is used to get Rest APIs from AWS API Gateway.
     *
     * @param client APIGatewayClient object
     * @return List of RestApi objects
     */
    public static List<RestApi> getRestApis(ApiGatewayClient client) {

        GetRestApisRequest restApisRequest = GetRestApisRequest.builder().build();
        GetRestApisResponse restApisResponse = client.getRestApis(restApisRequest);
        return restApisResponse.items();
    }


    /**
     * This method is used to get the API definition from AWS API Gateway.
     *
     * @param client APIGatewayClient object
     * @param apiId  ID of the Rest API
     * @return API definition in OpenAPI format
     */
    public static String getRestApiDefinition(ApiGatewayClient client, String apiId, String stage) {
        GetExportRequest getExportRequest = GetExportRequest.builder()
                .restApiId(apiId)
                .stageName(stage) // Assuming a default stage or make it configurable
                .exportType(OPEN_API_VERSION) // Or "oas30" for OpenAPI 3.0
                .accepts(JSON_PAYLOAD_TYPE)
                .build();
        GetExportResponse getExportResponse = client.getExport(getExportRequest);
        return getExportResponse.body().asUtf8String();
    }

    /**
     * This method retrieves the stage names for a given API ID.
     *
     * @param client APIGatewayClient object
     * @param apiId  ID of the Rest API
     * @return Stage name or null if no stages are found
     */
    public static String getStageNames(ApiGatewayClient client, String apiId) {
        GetStagesRequest request = GetStagesRequest.builder().restApiId(apiId).build();
        GetStagesResponse result = client.getStages(request);
        if (result.item().isEmpty()) {
            return null;
        }
        return result.item().get(0).stageName();
    }

    /**
     * Converts a RestApi object to an API object.
     *
     * @param restApi       The RestApi object to convert.
     * @param apiDefinition The OpenAPI definition of the API.
     * @param organization  The organization name.
     * @param environment   The environment in which the API is deployed.
     * @return An API object representing the RestApi.
     */
    public static API restAPItoAPI(RestApi restApi, String apiDefinition, String organization,
                                   Environment environment) {
        String name = restApi.name() == null ? restApi.id() : restApi.name();
        String version = restApi.version() == null ? DEFAULT_VERSION : restApi.version();
        APIIdentifier apiIdentifier = new APIIdentifier("admin", name, version);
        API api = new API(apiIdentifier);
        api.setDisplayName(restApi.name());
        api.setUuid(restApi.id());
        api.setDescription(restApi.description());
        api.setContext(restApi.name().toLowerCase().replace(" ", "-"));
        api.setContextTemplate(restApi.name().toLowerCase().replace(" ", "-"));
        api.setOrganization(organization);
        api.setSwaggerDefinition(apiDefinition);
        api.setRevision(false);
        api.setLastUpdated(Date.from(restApi.createdDate()));
        api.setCreatedTime(Long.toString(restApi.createdDate().toEpochMilli()));
        api.setInitiatedFromGateway(true);
        api.setGatewayVendor("external");
        api.setEnableSubscriberVerification(false);
        api.setGatewayType(environment.getGatewayType());
        api.setAvailableTiers(new HashSet<>(Collections.singleton(new Tier("Unlimited"))));
        return api;
    }

    /**
     * Sets the endpoint configuration for the API based on the provided RestApi.
     *
     * @param api     The API object to set the endpoint configuration for.
     * @param restApi The RestApi object containing endpoint information.
     * @param client  The ApiGatewayClient to interact with AWS API Gateway.
     */
    public static void setEndpointConfig(API api, RestApi restApi, ApiGatewayClient client) {
        String restApiId = restApi.id();
        String endpointUrls = getEndpointUrls(restApiId, client);
        if (endpointUrls != null) {
            JsonObject endpointConfig = new JsonObject();
            endpointConfig.addProperty("endpoint_type", "http");

            JsonObject prod = new JsonObject();
            prod.addProperty(URL_PROP, endpointUrls);

            JsonObject sand = new JsonObject();
            sand.addProperty(URL_PROP, endpointUrls);
            endpointConfig.add(PRODUCTION_ENDPOINTS, prod);
            endpointConfig.add(SANDBOX_ENDPOINTS, sand);
            api.setEndpointConfig(endpointConfig.toString());
        } else {
            log.warn("No endpoint URLs found for API: " + restApi.name());
        }
    }

    private static String getEndpointUrls(String restApiId, ApiGatewayClient client) {
        GetResourcesResponse resources = client.getResources(GetResourcesRequest.builder()
                .restApiId(restApiId)
                .build());

        if (resources == null || !resources.hasItems()) {
            return null;
        }
        String selectedUri = null;
        String selectedKey = null;

        for (Resource resource : resources.items()) {
            Map<String, Method> resourceMethods = resource.resourceMethods();
            if (resourceMethods == null || resourceMethods.isEmpty()) {
                continue;
            }
            String resourcePath = resource.path() != null ? resource.path() : "";
            for (String httpMethod : resourceMethods.keySet()) {
                if ("OPTIONS".equalsIgnoreCase(httpMethod)) {
                    continue;
                }
                try {
                    GetIntegrationResponse response = client.getIntegration(GetIntegrationRequest.builder()
                            .restApiId(restApiId)
                            .resourceId(resource.id())
                            .httpMethod(httpMethod)
                            .build());
                    String integrationUri = response.uri();
                    if (response.type() == IntegrationType.MOCK || !isValidEndpointUrl(integrationUri)) {
                        continue;
                    }
                    String candidateKey = resourcePath + "#" + httpMethod.toUpperCase();
                    if (selectedKey == null || candidateKey.compareTo(selectedKey) < 0) {
                        selectedKey = candidateKey;
                        selectedUri = integrationUri;
                    }
                } catch (Exception e) {
                    if (log.isDebugEnabled()) {
                        log.debug("Error getting integration for resource " + resource.id() + " and method "
                                + httpMethod + ": " + e.getMessage());
                    }
                }
            }
        }
        return selectedUri;
    }

    private static boolean isValidEndpointUrl(String integrationUri) {
        return integrationUri != null && !integrationUri.isEmpty()
                && (integrationUri.startsWith("http://") || integrationUri.startsWith("https://"));
    }

    public static ApiKeySecurityContext resolveApiKeySecurityContext(String restApiId, ApiGatewayClient client) {
        if (restApiId == null || client == null) {
            return new ApiKeySecurityContext(false, null);
        }
        GetResourcesResponse resources = client.getResources(GetResourcesRequest.builder()
                .restApiId(restApiId)
                .build());
        if (resources == null || !resources.hasItems()) {
            return new ApiKeySecurityContext(false, null);
        }
        for (Resource resource : resources.items()) {
            Map<String, Method> resourceMethods = resource.resourceMethods();
            if (resourceMethods == null || resourceMethods.isEmpty()) {
                continue;
            }
            for (Map.Entry<String, Method> entry : resourceMethods.entrySet()) {
                String method = entry.getKey();
                if ("OPTIONS".equalsIgnoreCase(method)) {
                    continue;
                }
                GetMethodResponse getMethodResponse = client.getMethod(GetMethodRequest.builder()
                        .restApiId(restApiId)
                        .resourceId(resource.id())
                        .httpMethod(method)
                        .build());
                if (Boolean.TRUE.equals(getMethodResponse.apiKeyRequired())) {
                    return new ApiKeySecurityContext(true, AWS_API_KEY_HEADER);
                }
            }
        }
        return new ApiKeySecurityContext(false, null);
    }

    /**
     * Creates a reference artifact by combining a given RestApi object and its Swagger definition in JSON format.
     *
     * @param restApi               The RestApi object containing API details.
     * @param swaggerDefinitionJson The Swagger/OpenAPI definition of the API in JSON format.
     * @return A JSON string that represents the combined reference artifact.
     */
    public static String createReferenceArtifact(RestApi restApi, String swaggerDefinitionJson,
                                                 ApiKeySecurityContext apiKeySecurityContext) {
        Gson G = new Gson();
        JsonArray arr = new JsonArray();
        JsonObject restApiJson = G.toJsonTree(restApi).getAsJsonObject();
        if (apiKeySecurityContext != null) {
            restApiJson.addProperty(AWS_REFERENCE_API_KEY_ENABLED, apiKeySecurityContext.isEnabled());
            if (apiKeySecurityContext.getHeaderName() != null) {
                restApiJson.addProperty(AWS_REFERENCE_API_KEY_HEADER, apiKeySecurityContext.getHeaderName());
            }
        }
        arr.add(restApiJson);
        arr.add(JsonParser.parseString(swaggerDefinitionJson));
        return G.toJson(arr);
    }

    /**
     * Deletes a Rest API from AWS API Gateway using its external reference (API ID).
     *
     * @param externalReference The external reference (API ID) of the Rest API to be deleted.
     * @param apiGatewayClient  The ApiGatewayClient instance used to interact with AWS API Gateway.
     */
    public static void deleteAPI(String externalReference, ApiGatewayClient apiGatewayClient)
            throws APIManagementException {
        String referenceArtifact = GatewayUtil.getAWSApiIdFromReferenceArtifact(externalReference);
        GetRestApiRequest getRestApiRequest = GetRestApiRequest.builder().restApiId(referenceArtifact).build();
        GetRestApiResponse restApi = apiGatewayClient.getRestApi(getRestApiRequest);
        if (restApi != null) {
            apiGatewayClient.deleteRestApi(DeleteRestApiRequest.builder().restApiId(referenceArtifact).build());
        }
    }

    private static boolean requiresNativeApiKey(API api) {
        return api != null && api.getApiSecurity() != null && api.getApiSecurity().contains(API_KEY_SECURITY);
    }

    public static final class ApiKeySecurityContext {
        private final boolean enabled;
        private final String headerName;

        public ApiKeySecurityContext(boolean enabled, String headerName) {
            this.enabled = enabled;
            this.headerName = headerName;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public String getHeaderName() {
            return headerName;
        }
    }

    /**
     * Processes API level operation policies and creates/attaches Lambda authorizer if awsApiKeyAuthorizer
     * policy is present.
     *
     * @param api The API object containing policies
     * @param restApiId The AWS REST API ID
     * @param region The AWS region
     * @param apiGatewayClient The ApiGatewayClient instance
     * @return The created authorizer ID, or null if no authorizer was created
     * @throws APIManagementException If an error occurs while processing policies or creating authorizer
     */
    public static String processApiKeyAuthorizerPolicy(API api, String restApiId, String region,
                                                       ApiGatewayClient apiGatewayClient)
            throws APIManagementException {
        if (api == null || restApiId == null) {
            return null;
        }

        // Check for API-level policies
        List<OperationPolicy> apiPolicies = api.getApiPolicies();
        if (apiPolicies != null) {
            for (OperationPolicy policy : apiPolicies) {
                if (isApiKeyAuthorizerPolicy(policy)) {
                    String authorizerId = createAndAttachApiKeyAuthorizer(policy, restApiId, region, apiGatewayClient);
                    if (authorizerId != null && log.isDebugEnabled()) {
                        log.debug("Created and attached API Key Authorizer for API: " + restApiId);
                    }
                    return authorizerId;
                }
            }
        }

        // Check for operation-level policies
        Set<URITemplate> uriTemplates = api.getUriTemplates();
        if (uriTemplates != null) {
            for (URITemplate uriTemplate : uriTemplates) {
                List<OperationPolicy> operationPolicies = uriTemplate.getOperationPolicies();
                if (operationPolicies != null) {
                    for (OperationPolicy policy : operationPolicies) {
                        if (isApiKeyAuthorizerPolicy(policy)) {
                            String authorizerId = createAndAttachApiKeyAuthorizer(policy, restApiId, region,
                                    apiGatewayClient);
                            if (authorizerId != null && log.isDebugEnabled()) {
                                log.debug("Created and attached API Key Authorizer for API: " + restApiId);
                            }
                            return authorizerId;
                        }
                    }
                }
            }
        }

        return null;
    }

    /**
     * Checks if the given operation policy is an API Key Authorizer policy.
     *
     * @param policy The operation policy to check
     * @return true if the policy is an API Key Authorizer policy, false otherwise
     */
    private static boolean isApiKeyAuthorizerPolicy(OperationPolicy policy) {
        if (policy == null || policy.getPolicyName() == null) {
            return false;
        }
        return AWS_APIKEY_AUTHORIZER_POLICY_NAME.equals(policy.getPolicyName()) ||
                (policy.getPolicyName().startsWith(AWS_APIKEY_AUTHORIZER_POLICY_NAME) &&
                        policy.getPolicyName().contains(":"));
    }

    /**
     * Creates a Lambda authorizer for API key validation and attaches it to the API.
     *
     * @param policy The operation policy containing Lambda ARN and role ARN
     * @param restApiId The AWS REST API ID
     * @param region The AWS region
     * @param apiGatewayClient The ApiGatewayClient instance
     * @return The created authorizer ID, or null if creation failed
     * @throws APIManagementException If an error occurs while creating the authorizer
     */
    private static String createAndAttachApiKeyAuthorizer(OperationPolicy policy, String restApiId,
                                                          String region, ApiGatewayClient apiGatewayClient)
            throws APIManagementException {
        Map<String, Object> parameters = policy.getParameters();
        if (parameters == null) {
            throw new APIManagementException("API Key Authorizer policy parameters are missing");
        }

        String lambdaArn = (String) parameters.get(OPERATION_POLICY_ARN_PARAMETER);
        String invokeRoleArn = (String) parameters.get(OPERATION_POLICY_ROLE_PARAMETER);

        if (lambdaArn == null || invokeRoleArn == null) {
            throw new APIManagementException("Lambda ARN or Invoke Role ARN is missing in " +
                    "API Key Authorizer policy");
        }

        // Delete existing authorizers with the same name to avoid conflicts
        deleteExistingAuthorizers(restApiId, apiGatewayClient);

        // Create the Lambda authorizer
        CreateAuthorizerRequest createAuthorizerRequest = CreateAuthorizerRequest.builder()
                .restApiId(restApiId)
                .name("wso2-api-key-authorizer")
                .type(software.amazon.awssdk.services.apigateway.model.AuthorizerType.REQUEST)
                .identitySource("method.request.header." + AWS_API_KEY_HEADER)
                .authorizerUri("arn:aws:apigateway:" + region + ":lambda:path/2015-03-31/functions/" + lambdaArn +
                        "/invocations")
                .authorizerCredentials(invokeRoleArn)
                .authorizerResultTtlInSeconds(0)
                .build();

        CreateAuthorizerResponse response = apiGatewayClient.createAuthorizer(createAuthorizerRequest);
        String authorizerId = response.id();

        if (log.isDebugEnabled()) {
            log.debug("Created Lambda authorizer for API key validation: " + authorizerId);
        }

        return authorizerId;
    }

    /**
     * Attaches the authorizer to a specific method.
     *
     * @param restApiId The AWS REST API ID
     * @param resourceId The resource ID
     * @param httpMethod The HTTP method
     * @param authorizerId The authorizer ID to attach
     * @param apiGatewayClient The ApiGatewayClient instance
     * @throws APIManagementException If an error occurs while attaching the authorizer
     */
    public static void attachAuthorizerToMethod(String restApiId, String resourceId, String httpMethod,
                                                 String authorizerId, ApiGatewayClient apiGatewayClient)
            throws APIManagementException {
        if (restApiId == null || resourceId == null || httpMethod == null || authorizerId == null) {
            return;
        }

        // Attach authorizer to the method
        List<PatchOperation> patchOperations = new ArrayList<>();
        patchOperations.add(PatchOperation.builder()
                .op(Op.REPLACE)
                .path("/authorizationType")
                .value("CUSTOM")
                .build());
        patchOperations.add(PatchOperation.builder()
                .op(Op.REPLACE)
                .path("/authorizerId")
                .value(authorizerId)
                .build());

        UpdateMethodRequest updateMethodRequest = UpdateMethodRequest.builder()
                .restApiId(restApiId)
                .resourceId(resourceId)
                .httpMethod(httpMethod)
                .patchOperations(patchOperations)
                .build();

        apiGatewayClient.updateMethod(updateMethodRequest);

        if (log.isDebugEnabled()) {
            log.debug("Attached authorizer " + authorizerId + " to method " + httpMethod +
                    " on resource " + resourceId);
        }
    }

    /**
     * Attaches the authorizer to all methods of the API except OPTIONS.
     *
     * @param restApiId The AWS REST API ID
     * @param authorizerId The authorizer ID to attach
     * @param apiGatewayClient The ApiGatewayClient instance
     * @throws APIManagementException If an error occurs while attaching the authorizer
     */
    public static void attachAuthorizerToMethods(String restApiId, String authorizerId,
                                                  ApiGatewayClient apiGatewayClient)
            throws APIManagementException {
        if (restApiId == null || authorizerId == null) {
            return;
        }

        GetResourcesResponse resources = apiGatewayClient.getResources(
                GetResourcesRequest.builder().restApiId(restApiId).build());

        if (resources == null || !resources.hasItems()) {
            return;
        }

        for (Resource resource : resources.items()) {
            Map<String, Method> resourceMethods = resource.resourceMethods();
            if (resourceMethods == null || resourceMethods.isEmpty()) {
                continue;
            }

            for (Map.Entry<String, Method> entry : resourceMethods.entrySet()) {
                String httpMethod = entry.getKey();
                if ("OPTIONS".equalsIgnoreCase(httpMethod)) {
                    continue;
                }

                // Attach authorizer to the method
                attachAuthorizerToMethod(restApiId, resource.id(), httpMethod, authorizerId, apiGatewayClient);
            }
        }
    }

    /**
     * Deletes existing authorizers from the API to avoid conflicts.
     *
     * @param restApiId The AWS REST API ID
     * @param apiGatewayClient The ApiGatewayClient instance
     */
    private static void deleteExistingAuthorizers(String restApiId, ApiGatewayClient apiGatewayClient) {
        try {
            GetAuthorizersResponse authorizersResponse = apiGatewayClient.getAuthorizers(
                    GetAuthorizersRequest.builder().restApiId(restApiId).build());

            if (authorizersResponse != null && authorizersResponse.items() != null) {
                for (software.amazon.awssdk.services.apigateway.model.Authorizer authorizer :
                        authorizersResponse.items()) {
                    // Delete authorizers with our naming pattern
                    if (authorizer.name() != null && authorizer.name().startsWith("wso2-")) {
                        apiGatewayClient.deleteAuthorizer(DeleteAuthorizerRequest.builder()
                                .restApiId(restApiId)
                                .authorizerId(authorizer.id())
                                .build());
                        if (log.isDebugEnabled()) {
                            log.debug("Deleted existing authorizer: " + authorizer.id());
                        }
                    }
                }
            }
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("Error deleting existing authorizers: " + e.getMessage());
            }
        }
    }
}
