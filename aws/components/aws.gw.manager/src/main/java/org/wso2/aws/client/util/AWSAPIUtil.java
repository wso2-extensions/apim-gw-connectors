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
import software.amazon.awssdk.services.apigateway.model.GetMethodRequest;
import software.amazon.awssdk.services.apigateway.model.GetMethodResponse;
import software.amazon.awssdk.services.apigateway.model.GetResourcesRequest;
import software.amazon.awssdk.services.apigateway.model.GetResourcesResponse;
import software.amazon.awssdk.services.apigateway.model.GetRestApiRequest;
import software.amazon.awssdk.services.apigateway.model.GetRestApiResponse;
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
import software.amazon.awssdk.services.apigatewayv2.ApiGatewayV2Client;
import software.amazon.awssdk.services.apigatewayv2.model.GetRoutesRequest;
import software.amazon.awssdk.services.apigatewayv2.model.GetRoutesResponse;
import software.amazon.awssdk.services.apigatewayv2.model.Route;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
     * This method is used to get the API definition from AWS API Gateway.
     *
     * @param client APIGatewayClient object
     * @param apiId  ID of the Rest API
     * @return API definition in OpenAPI format
     */
    public static String getRestApiDefinition(ApiGatewayClient client, String apiId, String stage) {
        GetExportRequest getExportRequest = GetExportRequest.builder()
                .restApiId(apiId)
                .stageName(stage)
                .exportType(OPEN_API_VERSION)
                .accepts(JSON_PAYLOAD_TYPE)
                .build();
        GetExportResponse getExportResponse = client.getExport(getExportRequest);
        String rawDefinition = getExportResponse.body().asUtf8String();
        return resolveServerVariables(rawDefinition);
    }

    /**
     * Resolves server URL variables in the OpenAPI definition returned by AWS API Gateway.
     * <p>
     * AWS exports OpenAPI definitions with server URLs containing variables like:
     * {@code https://{restapi_id}.execute-api.{region}.amazonaws.com/{basePath}}
     * <p>
     * This method resolves those variables using their default values from the "variables" block,
     * producing a fully resolved URL like:
     * {@code https://abc123.execute-api.us-east-1.amazonaws.com/dev}
     * <p>
     * It also removes the "variables" block after resolution since it's no longer needed.
     *
     * @param apiDefinition The raw OpenAPI definition JSON string from AWS
     * @return The OpenAPI definition with resolved server URLs
     */
    public static String resolveServerVariables(String apiDefinition) {
        if (apiDefinition == null || apiDefinition.isEmpty()) {
            return apiDefinition;
        }
        try {
            JsonObject root = JsonParser.parseString(apiDefinition).getAsJsonObject();
            if (!root.has("servers") || !root.get("servers").isJsonArray()) {
                return apiDefinition;
            }
            JsonArray servers = root.getAsJsonArray("servers");
            for (int i = 0; i < servers.size(); i++) {
                JsonObject server = servers.get(i).getAsJsonObject();
                if (!server.has("url") || !server.has("variables")) {
                    continue;
                }
                String url = server.get("url").getAsString();
                JsonObject variables = server.getAsJsonObject("variables");
                // Replace each {variableName} with its default value
                for (String varName : variables.keySet()) {
                    JsonObject varObj = variables.getAsJsonObject(varName);
                    if (varObj != null && varObj.has("default")) {
                        String defaultValue = varObj.get("default").getAsString();
                        url = url.replace("{" + varName + "}", defaultValue);
                    }
                }
                server.addProperty("url", url);
                // Remove the variables block — no longer needed after resolution
                server.remove("variables");
            }
            return root.toString();
        } catch (Exception e) {
            log.warn("Failed to resolve server variables in API definition, returning raw definition", e);
            return apiDefinition;
        }
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
     * Sets the endpoint configuration for the API based on the provided RestApi.
     * Constructs the public-facing API Gateway invoke URL using the api ID, region, and stage,
     * since AWS SDK does not expose the invoke URL directly.
     *
     * @param api     The API object to set the endpoint configuration for.
     * @param restApi The RestApi object containing endpoint information.
     * @param region  The AWS region where the API Gateway is deployed (e.g., "us-east-1").
     * @param stage   The deployment stage name (e.g., "prod", "dev").
     */
    public static void setEndpointConfig(API api, RestApi restApi, String region, String stage) {
        String invokeUrl = getEndpointUrl(restApi.id(), region, stage);
        JsonObject endpointConfig = new JsonObject();
        endpointConfig.addProperty("endpoint_type", "http");

        JsonObject prod = new JsonObject();
        prod.addProperty(URL_PROP, invokeUrl);

        JsonObject sand = new JsonObject();
        sand.addProperty(URL_PROP, invokeUrl);
        endpointConfig.add(PRODUCTION_ENDPOINTS, prod);
        endpointConfig.add(SANDBOX_ENDPOINTS, sand);
        api.setEndpointConfig(endpointConfig.toString());
    }

    /**
     * Constructs the public-facing API Gateway invoke URL.
     * AWS does not provide a direct field for this; it must be built from the API ID, region, and stage.
     * Format: https://{api-id}.execute-api.{region}.amazonaws.com/{stage}
     *
     * @param restApiId The REST API identifier.
     * @param region    The AWS region (e.g., "us-east-1").
     * @param stage     The deployment stage name.
     * @return The fully constructed invoke URL.
     */
    private static String getEndpointUrl(String restApiId, String region, String stage) {
        return String.format("https://%s.execute-api.%s.amazonaws.com/%s", restApiId, region, stage);
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

    // ========================================================================
    // API Gateway V2 methods (WebSocket API support)
    // ========================================================================

    /**
     * Sets endpoint configuration on an API using a pre-built URL.
     * Used by V2 HTTP API builder where the URL is constructed from the V2 API ID.
     *
     * @param api The WSO2 API object to configure.
     * @param invokeUrl The fully constructed invoke URL.
     */
    public static void setEndpointConfigFromUrl(API api, String invokeUrl) {
        JsonObject endpointConfig = new JsonObject();
        endpointConfig.addProperty("endpoint_type", "http");

        JsonObject prod = new JsonObject();
        prod.addProperty(URL_PROP, invokeUrl);
        JsonObject sand = new JsonObject();
        sand.addProperty(URL_PROP, invokeUrl);

        endpointConfig.add(PRODUCTION_ENDPOINTS, prod);
        endpointConfig.add(SANDBOX_ENDPOINTS, sand);
        api.setEndpointConfig(endpointConfig.toString());
    }

    /**
     * Retrieves all routes for a given V2 WebSocket API.
     *
     * @param client The ApiGatewayV2Client instance.
     * @param apiId  The V2 API identifier.
     * @return List of Route objects (e.g., $connect, $disconnect, $default, custom routes).
     */
    public static List<Route> getWebSocketRoutes(ApiGatewayV2Client client, String apiId) {
        GetRoutesRequest request = GetRoutesRequest.builder().apiId(apiId).build();
        GetRoutesResponse response = client.getRoutes(request);
        return response.items();
    }

    /**
     * Checks whether a specific deployment stage exists for a V2 API.
     *
     * @param client    The ApiGatewayV2Client instance.
     * @param apiId     The V2 API identifier.
     * @param stageName The stage name to check for.
     * @return true if the stage exists, false otherwise.
     */
    public static boolean hasStage(ApiGatewayV2Client client, String apiId, String stageName) {
        try {
            software.amazon.awssdk.services.apigatewayv2.model.GetStagesRequest request =
                    software.amazon.awssdk.services.apigatewayv2.model.GetStagesRequest.builder()
                            .apiId(apiId).build();
            software.amazon.awssdk.services.apigatewayv2.model.GetStagesResponse response =
                    client.getStages(request);
            for (software.amazon.awssdk.services.apigatewayv2.model.Stage stage : response.items()) {
                if (stageName.equals(stage.stageName())) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            log.warn("Failed to check stages for V2 API: " + apiId, e);
            return false;
        }
    }

    /**
     * Constructs the public WebSocket endpoint URL for an AWS API Gateway V2 WebSocket API.
     * Format: wss://{api-id}.execute-api.{region}.amazonaws.com/{stage}
     *
     * @param apiId  The V2 API identifier.
     * @param region The AWS region (e.g., "us-east-1").
     * @param stage  The deployment stage name.
     * @return The fully constructed WebSocket endpoint URL.
     */
    public static String getWebSocketEndpointUrl(String apiId, String region, String stage) {
        return String.format("wss://%s.execute-api.%s.amazonaws.com/%s", apiId, region, stage);
    }

    /**
     * Builds an AsyncAPI 2.6.0 definition from AWS WebSocket API routes.
     * This is necessary because AWS API Gateway V2 does not export AsyncAPI definitions natively.
     *
     * @param apiName   The API display name.
     * @param version   The API version.
     * @param serverUrl The WebSocket server URL.
     * @param routes    The list of WebSocket routes.
     * @return The AsyncAPI definition as a JSON string.
     */
    public static String buildAsyncApiDefinition(String apiName, String version,
                                                  String serverUrl, List<Route> routes) {
        JsonObject asyncApi = new JsonObject();
        asyncApi.addProperty("asyncapi", AWSConstants.ASYNC_API_VERSION);

        JsonObject info = new JsonObject();
        info.addProperty("title", apiName);
        info.addProperty("version", version != null ? version : AWSConstants.DEFAULT_VERSION);
        asyncApi.add("info", info);

        JsonObject servers = new JsonObject();
        JsonObject production = new JsonObject();
        production.addProperty("url", serverUrl);
        production.addProperty("protocol", AWSConstants.PROTOCOL_WSS);
        servers.add("production", production);
        asyncApi.add("servers", servers);

        JsonObject channels = new JsonObject();
        if (routes != null) {
            for (Route route : routes) {
                String routeKey = route.routeKey();
                JsonObject channel = new JsonObject();

                JsonObject publish = new JsonObject();
                publish.addProperty("operationId", routeKey);
                JsonObject message = new JsonObject();
                JsonObject payload = new JsonObject();
                payload.addProperty("type", "object");
                message.add("payload", payload);
                publish.add("message", message);
                channel.add("publish", publish);

                channels.add(routeKey, channel);
            }
        }
        asyncApi.add("channels", channels);

        return new Gson().toJson(asyncApi);
    }

    /**
     * Builds the WebSocket endpoint configuration JSON string.
     *
     * @param apiId  The V2 API identifier.
     * @param region The AWS region.
     * @param stage  The deployment stage name.
     * @return The endpoint configuration JSON string.
     */
    public static String buildWebSocketEndpointConfig(String apiId, String region, String stage) {
        String wsUrl = getWebSocketEndpointUrl(apiId, region, stage);
        JsonObject endpointConfig = new JsonObject();
        endpointConfig.addProperty("endpoint_type", AWSConstants.ENDPOINT_TYPE_WS);

        JsonObject prod = new JsonObject();
        prod.addProperty(URL_PROP, wsUrl);
        JsonObject sand = new JsonObject();
        sand.addProperty(URL_PROP, wsUrl);

        endpointConfig.add(PRODUCTION_ENDPOINTS, prod);
        endpointConfig.add(SANDBOX_ENDPOINTS, sand);
        return endpointConfig.toString();
    }

    /**
     * Creates a reference artifact for a V2 WebSocket API (Api + routes).
     *
     * @param webSocketApi The V2 Api object.
     * @param routes       The list of routes (may be empty).
     * @return JSON string representing the reference artifact.
     */
    public static String createReferenceArtifact(software.amazon.awssdk.services.apigatewayv2.model.Api webSocketApi,
                                                 java.util.List<software.amazon.awssdk.services.apigatewayv2.model.Route> routes) {
        Gson G = new Gson();
        JsonArray arr = new JsonArray();
        arr.add(G.toJsonTree(webSocketApi));
        if (routes != null) {
            arr.add(G.toJsonTree(routes));
        }
        return G.toJson(arr);
    }
}
