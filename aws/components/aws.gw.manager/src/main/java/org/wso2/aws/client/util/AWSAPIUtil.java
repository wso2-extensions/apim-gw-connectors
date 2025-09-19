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
import org.wso2.aws.client.AWSConstants;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.APIIdentifier;
import org.wso2.carbon.apimgt.api.model.Environment;
import org.wso2.carbon.apimgt.api.model.OperationPolicy;
import org.wso2.carbon.apimgt.api.model.URITemplate;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.Authorizer;
import software.amazon.awssdk.services.apigateway.model.CreateDeploymentRequest;
import software.amazon.awssdk.services.apigateway.model.CreateDeploymentResponse;
import software.amazon.awssdk.services.apigateway.model.DeleteDeploymentRequest;
import software.amazon.awssdk.services.apigateway.model.DeleteRestApiRequest;
import software.amazon.awssdk.services.apigateway.model.DeleteStageRequest;
import software.amazon.awssdk.services.apigateway.model.Deployment;
import software.amazon.awssdk.services.apigateway.model.GetAuthorizersRequest;
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

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.wso2.aws.client.AWSConstants.DEFAULT_VERSION;
import static org.wso2.aws.client.AWSConstants.JSON_PAYLOAD_TYPE;
import static org.wso2.aws.client.AWSConstants.OPEN_API_VERSION;
import static org.wso2.aws.client.AWSConstants.PRODUCTION_ENDPOINTS;
import static org.wso2.aws.client.AWSConstants.SANDBOX_ENDPOINTS;
import static org.wso2.aws.client.AWSConstants.URL_PROP;

/**
 * This class contains utility methods to interact with AWS API Gateway
 */
public class AWSAPIUtil {
    private static final Log log = LogFactory.getLog(AWSAPIUtil.class);

    public static String importRestAPI(API api, ApiGatewayClient apiGatewayClient, String region,
                                       String stage) throws APIManagementException {

        String openAPI = api.getSwaggerDefinition();
        String apiId = null;
        Map<String, String> authorizers = new HashMap<>();
        Map<String, String> pathToArnMapping = new HashMap<>();

        try {
            ImportRestApiRequest importApiRequest = ImportRestApiRequest.builder()
                    .body(SdkBytes.fromUtf8String(openAPI))
                    .failOnWarnings(false)
                    .build();

            //import rest API with the openapi definition
            ImportRestApiResponse importApiResponse = apiGatewayClient.importRestApi(importApiRequest);
            apiId = importApiResponse.id();

            //add integrations for each resource
            GetResourcesRequest getResourcesRequest = GetResourcesRequest.builder().restApiId(apiId).build();
            GetResourcesResponse getResourcesResponse = apiGatewayClient.getResources(getResourcesRequest);

            //configure authorizers
            List<OperationPolicy> apiPolicies = api.getApiPolicies();
            if (apiPolicies != null) {
                for (OperationPolicy policy : apiPolicies) {
                    if (policy.getPolicyName().equals(AWSConstants.AWS_OPERATION_POLICY_NAME)) {
                        String lambdaArnAPI = policy.getParameters()
                                .get(AWSConstants.OPERATION_POLICY_ARN_PARAMETER).toString();
                        String invokeRoleArn = policy.getParameters()
                                .get(AWSConstants.OPERATION_POLICY_ROLE_PARAMETER).toString();

                        String key = lambdaArnAPI + "|" + invokeRoleArn;
                        pathToArnMapping.put(AWSConstants.OPERATION_POLICY_API, key);

                        String name = lambdaArnAPI.substring(lambdaArnAPI.lastIndexOf(':') + 1) + "-" +
                                invokeRoleArn.substring(invokeRoleArn.lastIndexOf('/') + 1);

                        authorizers.put(key, GatewayUtil.getAuthorizer(apiId, name, lambdaArnAPI,
                                invokeRoleArn, region, apiGatewayClient).id());
                        break;
                    }
                }
            }

            for (URITemplate resource : api.getUriTemplates()) {
                for (OperationPolicy policy : resource.getOperationPolicies()) {
                    if (policy.getPolicyName().equals(AWSConstants.AWS_OPERATION_POLICY_NAME)) {
                        String resourceLambdaARN = policy.getParameters()
                                .get(AWSConstants.OPERATION_POLICY_ARN_PARAMETER).toString();
                        String invokeRoleArnResource = policy.getParameters()
                                .get(AWSConstants.OPERATION_POLICY_ROLE_PARAMETER).toString();

                        String key = resourceLambdaARN + "|" + invokeRoleArnResource;
                        pathToArnMapping.put(resource.getUriTemplate().toLowerCase()
                                + "|" + resource.getHTTPVerb().toLowerCase(), key);
                        if (!authorizers.containsKey(key)) {
                            String name = resourceLambdaARN
                                    .substring(resourceLambdaARN.lastIndexOf(':') + 1) + "-" +
                                    invokeRoleArnResource
                                            .substring(invokeRoleArnResource.lastIndexOf('/') + 1);
                            authorizers.put(key, GatewayUtil.getAuthorizer(apiId, name, resourceLambdaARN,
                                    invokeRoleArnResource, region, apiGatewayClient).id());
                        }
                        break;
                    }
                }
            }

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

                        String key = resource.path().toLowerCase() + "|" + entry.getKey().toString().toLowerCase();
                        boolean isAuthorizerFound = false;
                        if (authorizers.containsKey(pathToArnMapping.get(key))) {
                            isAuthorizerFound = true;
                        } else {
                            key = "API";
                            if (authorizers.containsKey(pathToArnMapping.get(key))) {
                                isAuthorizerFound = true;
                            } else {
                                if (log.isDebugEnabled()) {
                                    log.debug("Authorizer not found for the resource: " + resource.path() + " at API " +
                                            "or Resource levels");
                                }
                            }
                        }
                        if (isAuthorizerFound) {
                            String authorizerId = authorizers.get(pathToArnMapping.get(key));

                            //configure authorizer
                            UpdateMethodRequest updateMethodRequest = UpdateMethodRequest.builder().restApiId(apiId)
                                    .resourceId(resource.id()).httpMethod(entry.getKey().toString())
                                    .patchOperations(PatchOperation.builder().op(Op.REPLACE).path("/authorizationType")
                                                    .value("CUSTOM").build(),
                                            PatchOperation.builder().op(Op.REPLACE).path("/authorizerId")
                                                    .value(authorizerId).build()).build();
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
        List<String> currentARNs = new ArrayList<>();
        Map<String, String> authorizers = new HashMap<>();
        Map<String, String> pathToArnMapping = new HashMap<>();
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

            //configure authorizers
            GetAuthorizersRequest getAuthorizersRequest = GetAuthorizersRequest.builder().restApiId(awsApiId).build();
            List<Authorizer> existingAuthorizers = apiGatewayClient.getAuthorizers(getAuthorizersRequest).items();

            for (Authorizer authorizer : existingAuthorizers) {
                String regex = "arn:aws:apigateway:[^:]+:lambda:path/2015-03-31/functions/([^/]+)/invocations";
                Pattern compiledPattern = Pattern.compile(regex);
                Matcher matcher = compiledPattern.matcher(authorizer.authorizerUri());
                String credentials = authorizer.authorizerCredentials();
                String arn = null;
                if (matcher.find()) {
                    arn = matcher.group(1);
                }
                authorizers.put(arn + "|" + credentials, authorizer.id());
                currentARNs.add(arn + "|" + credentials);
            }

            List<OperationPolicy> apiPolicies = api.getApiPolicies();
            if (apiPolicies != null) {
                for (OperationPolicy policy : apiPolicies) {
                    if (policy.getPolicyName().equals(AWSConstants.AWS_OPERATION_POLICY_NAME)) {
                        String lambdaArnAPI = policy.getParameters()
                                .get(AWSConstants.OPERATION_POLICY_ARN_PARAMETER).toString();
                        String invokeRoleArn = policy.getParameters()
                                .get(AWSConstants.OPERATION_POLICY_ROLE_PARAMETER).toString();

                        String key = lambdaArnAPI + "|" + invokeRoleArn;
                        pathToArnMapping.put(AWSConstants.OPERATION_POLICY_API, key);

                        if (!authorizers.containsKey(key)) {
                            String name = lambdaArnAPI.substring(lambdaArnAPI.lastIndexOf(':') + 1) + "-" +
                                    invokeRoleArn.substring(invokeRoleArn.lastIndexOf('/') + 1);

                            authorizers.put(key, GatewayUtil.getAuthorizer(awsApiId, name, lambdaArnAPI,
                                    invokeRoleArn, region, apiGatewayClient).id());
                        }
                        break;
                    }
                }
            }

            Set<URITemplate> uriTemplates = api.getUriTemplates();
            if (uriTemplates != null) {
                for (URITemplate resource : uriTemplates) {
                    List<OperationPolicy> resourcePolicies = resource.getOperationPolicies();
                    if (resourcePolicies != null) {
                        for (OperationPolicy policy : resourcePolicies) {
                            if (policy.getPolicyName().equals(AWSConstants.AWS_OPERATION_POLICY_NAME)) {
                                String resourceLambdaARN = policy.getParameters()
                                        .get(AWSConstants.OPERATION_POLICY_ARN_PARAMETER).toString();
                                String invokeRoleArnResource = policy.getParameters()
                                        .get(AWSConstants.OPERATION_POLICY_ROLE_PARAMETER).toString();

                                String key = resourceLambdaARN + "|" + invokeRoleArnResource;
                                pathToArnMapping.put(resource.getUriTemplate().toLowerCase()
                                        + "|" + resource.getHTTPVerb().toLowerCase(), key);
                                if (!authorizers.containsKey(key)) {
                                    String name = resourceLambdaARN
                                            .substring(resourceLambdaARN.lastIndexOf(':') + 1) + "-" +
                                            invokeRoleArnResource
                                                    .substring(invokeRoleArnResource.lastIndexOf('/') + 1);
                                    authorizers.put(key, GatewayUtil.getAuthorizer(awsApiId, name, resourceLambdaARN,
                                            invokeRoleArnResource, region, apiGatewayClient).id());
                                }
                                break;
                            }
                        }
                    }
                }
            }

            //remove unused authorizers
            for (String arn : currentARNs) {
                if (!authorizers.containsKey(arn)) {
                    GatewayUtil.deleteAuthorizer(awsApiId, authorizers.get(arn), apiGatewayClient);
                    authorizers.remove(arn);
                }
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

                        String key = resource.path().toLowerCase() + "|" + entry.getKey().toString().toLowerCase();
                        boolean isAuthorizerFound = false;
                        if (authorizers.containsKey(pathToArnMapping.get(key))) {
                            isAuthorizerFound = true;
                        } else {
                            key = "API";
                            if (authorizers.containsKey(pathToArnMapping.get(key))) {
                                isAuthorizerFound = true;
                            } else {
                                if (log.isDebugEnabled()) {
                                    log.debug("Authorizer not found for the resource: " + resource.path() + " at API " +
                                            "or Resource levels");
                                }
                            }
                        }

                        if (isAuthorizerFound) {
                            String authorizerId = authorizers.get(pathToArnMapping.get(key));

                            UpdateMethodRequest updateMethodRequest = UpdateMethodRequest.builder().restApiId(awsApiId)
                                    .resourceId(resource.id()).httpMethod(entry.getKey().toString())
                                    .patchOperations(PatchOperation.builder().op(Op.REPLACE).path("/authorizationType")
                                                    .value("CUSTOM").build(),
                                            PatchOperation.builder().op(Op.REPLACE).path("/authorizerId")
                                                    .value(authorizerId).build()).build();
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

        if (resources.hasItems() && resources.items().get(0).resourceMethods() != null
                && !resources.items().get(0).resourceMethods().isEmpty()) {
            String httpMethod = resources.items().get(0).resourceMethods()
                    .keySet()
                    .iterator()
                    .next()
                    .toString();
            GetIntegrationRequest request = GetIntegrationRequest.builder()
                    .restApiId(restApiId)
                    .resourceId(resources.items().get(0).id())
                    .httpMethod(httpMethod)
                    .build();

            GetIntegrationResponse response = client.getIntegration(request);
            return response.uri();
        } else {
            return null;
        }
    }

    /**
     * Creates a reference artifact by combining a given RestApi object and its Swagger definition in JSON format.
     *
     * @param restApi               The RestApi object containing API details.
     * @param swaggerDefinitionJson The Swagger/OpenAPI definition of the API in JSON format.
     * @return A JSON string that represents the combined reference artifact.
     */
    public static String createReferenceArtifact(RestApi restApi, String swaggerDefinitionJson) {
        Gson G = new Gson();
        JsonArray arr = new JsonArray();
        arr.add(G.toJsonTree(restApi));
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
}
