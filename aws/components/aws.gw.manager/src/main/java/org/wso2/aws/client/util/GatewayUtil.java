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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.lang3.StringUtils;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.wso2.aws.client.AWSConstants;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.URITemplate;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.AuthorizerType;
import software.amazon.awssdk.services.apigateway.model.CreateAuthorizerRequest;
import software.amazon.awssdk.services.apigateway.model.CreateAuthorizerResponse;
import software.amazon.awssdk.services.apigateway.model.DeleteAuthorizerRequest;
import software.amazon.awssdk.services.apigateway.model.DeleteRestApiRequest;
import software.amazon.awssdk.services.apigateway.model.GetMethodRequest;
import software.amazon.awssdk.services.apigateway.model.GetMethodResponse;
import software.amazon.awssdk.services.apigateway.model.IntegrationType;
import software.amazon.awssdk.services.apigateway.model.MethodResponse;
import software.amazon.awssdk.services.apigateway.model.Op;
import software.amazon.awssdk.services.apigateway.model.PatchOperation;
import software.amazon.awssdk.services.apigateway.model.PutIntegrationRequest;
import software.amazon.awssdk.services.apigateway.model.PutIntegrationResponseRequest;
import software.amazon.awssdk.services.apigateway.model.PutMethodRequest;
import software.amazon.awssdk.services.apigateway.model.PutMethodResponseRequest;
import software.amazon.awssdk.services.apigateway.model.Resource;
import software.amazon.awssdk.services.apigateway.model.UpdateGatewayResponseRequest;
import software.amazon.awssdk.services.apigateway.model.UpdateIntegrationResponseRequest;
import software.amazon.awssdk.services.apigateway.model.UpdateMethodResponseRequest;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This class contains utility methods for the AWS API Gateway
 */
public class GatewayUtil {

    private static final Pattern VALID_PATH_PATTERN = Pattern.compile("^[a-zA-Z0-9-._~%!$&'()*+,;=:@/]*$");

    /**
     * Extracts AWS API ID from the reference artifact.
     * Handles two formats:
     * 1. JSON array format (from discovery): [ { restApi object with "id" field }, { openapi spec } ]
     * 2. String format (from deploy): GetRestApiResponse(Id=xxx, Name=yyy, ...)
     */
    public static String getAWSApiIdFromReferenceArtifact(String referenceArtifact) throws APIManagementException {
        if (referenceArtifact == null || referenceArtifact.trim().isEmpty()) {
            throw new APIManagementException("Reference artifact is null or empty");
        }

        try {
            // Try JSON array format first (discovery path)
            if (referenceArtifact.trim().startsWith("[")) {
                JsonArray jsonArray = JsonParser.parseString(referenceArtifact).getAsJsonArray();
                if (!jsonArray.isEmpty()) {
                    JsonObject restApiObject = jsonArray.get(0).getAsJsonObject();
                    if (restApiObject.has("id")) {
                        return restApiObject.get("id").getAsString();
                    }
                }
            }

            // Try string format (deploy path): GetRestApiResponse(Id=xxx, ...)
            Pattern pattern = Pattern.compile(AWSConstants.AWS_ID_PATTERN);
            Matcher matcher = pattern.matcher(referenceArtifact);
            if (matcher.find()) {
                return matcher.group(1);
            }

            throw new APIManagementException("Error while extracting AWS API ID from reference artifact: " +
                    "unable to parse reference artifact in either JSON or string format");
        } catch (APIManagementException e) {
            throw e;
        } catch (Exception e) {
            throw new APIManagementException("Error while parsing reference artifact", e);
        }
    }

    public static void rollbackDeployment(ApiGatewayClient apiGatewayClient, String awsApiId)
            throws APIManagementException {
        if (apiGatewayClient != null && awsApiId != null) {
            //delete the API if an error occurred
            DeleteRestApiRequest deleteRestApiRequest = DeleteRestApiRequest.builder().restApiId(awsApiId).build();
            apiGatewayClient.deleteRestApi(deleteRestApiRequest);
        }
    }

    public static String getEndpointURL(API api) throws APIManagementException {

        try {
            String endpointConfig = api.getEndpointConfig();
            if (StringUtils.isEmpty(endpointConfig)) {
                return endpointConfig;
            }
            JSONParser parser = new JSONParser();
            JSONObject endpointConfigJson = null;

            endpointConfigJson = (JSONObject) parser.parse(endpointConfig);

            JSONObject prodEndpoints = (JSONObject)endpointConfigJson.get("production_endpoints");
            String productionEndpoint = (String) prodEndpoints.get("url");

            return productionEndpoint.charAt(productionEndpoint.length() - 1) == '/' ?
                    productionEndpoint.substring(0, productionEndpoint.length() - 1) : productionEndpoint;
        } catch (ParseException e) {
            throw new APIManagementException("Error while parsing endpoint configuration", e);
        }
    }

    public static String validateAWSAPIEndpoint(String urlString) {
        try {
            if (StringUtils.isEmpty(urlString)) {
                return null;
            }
            URL url = new URL(urlString);

            // Validate scheme (only http and https are allowed)
            String protocol = url.getProtocol();
            if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) {
                return "Invalid Endpoint URL";
            }

            // Validate host
            if (url.getHost() == null || url.getHost().isEmpty()
                    || url.getHost().equalsIgnoreCase("localhost")) {
                return "Invalid Endpoint URL";
            }

            // Validate path (no illegal characters)
            if (!VALID_PATH_PATTERN.matcher(url.getPath()).matches()) {
                return "Invalid Endpoint URL";
            }
            return null;
        } catch (MalformedURLException e) {
            return "Invalid Endpoint URL";
        }
    }

    public static String validateResourceContexts(API api) {
        Set<URITemplate> uriTemplates = api.getUriTemplates();

        if (!uriTemplates.isEmpty()) {
            for (URITemplate uriTemplate : uriTemplates) {
                if (uriTemplate.getUriTemplate().contains("*")) {
                    return "Some resource contexts contain '*' wildcard";
                }
            }
        }
        return null;
    }

    public static void configureOptionsCallForCORS(String apiId, Resource resource, ApiGatewayClient apiGatewayClient) {
        //configure CORS
        PutMethodRequest putMethodRequest = PutMethodRequest.builder().restApiId(apiId)
                .resourceId(resource.id()).httpMethod("OPTIONS").authorizationType("NONE")
                .apiKeyRequired(false).build();
        apiGatewayClient.putMethod(putMethodRequest);

        PutMethodResponseRequest putMethodResponseRequest = PutMethodResponseRequest.builder()
                .restApiId(apiId).resourceId(resource.id()).httpMethod("OPTIONS").statusCode("200")
                .responseModels(new HashMap<>())
                .build();
        apiGatewayClient.putMethodResponse(putMethodResponseRequest);

        PutIntegrationRequest putMethodIntegrationRequest = PutIntegrationRequest.builder()
                .restApiId(apiId).resourceId(resource.id()).httpMethod("OPTIONS")
                .integrationHttpMethod("OPTIONS").type(IntegrationType.MOCK)
                .requestTemplates(Map.of("application/json", "{\"statusCode\": 200}"))
                .build();
        apiGatewayClient.putIntegration(putMethodIntegrationRequest);

        PutIntegrationResponseRequest putIntegrationResponseRequest = PutIntegrationResponseRequest.builder()
                .restApiId(apiId).resourceId(resource.id()).httpMethod("OPTIONS").statusCode("200")
                .responseTemplates(Map.of("application/json", ""))
                .build();
        apiGatewayClient.putIntegrationResponse(putIntegrationResponseRequest);

        UpdateMethodResponseRequest updateMethodResponseRequest = UpdateMethodResponseRequest.builder()
                .restApiId(apiId).resourceId(resource.id()).httpMethod("OPTIONS").statusCode("200")
                .patchOperations(PatchOperation.builder().op(Op.ADD).path("/responseParameters/method" +
                                ".response.header.Access-Control-Allow-Origin").build(),
                        PatchOperation.builder().op(Op.ADD).path("/responseParameters/method.response" +
                                ".header.Access-Control-Allow-Methods").build(),
                        PatchOperation.builder().op(Op.ADD).path("/responseParameters/method.response" +
                                ".header.Access-Control-Allow-Headers").build()).build();
        apiGatewayClient.updateMethodResponse(updateMethodResponseRequest);

        UpdateIntegrationResponseRequest updateIntegrationResponseRequest = UpdateIntegrationResponseRequest.builder()
                .restApiId(apiId).resourceId(resource.id()).httpMethod("OPTIONS").statusCode("200")
                .patchOperations(PatchOperation.builder().op(Op.ADD).path("/responseParameters/method" +
                                ".response.header.Access-Control-Allow-Origin").value("'*'").build(),
                        PatchOperation.builder().op(Op.ADD).path("/responseParameters/method.response" +
                                ".header.Access-Control-Allow-Methods").value("'GET,OPTIONS'").build(),
                        PatchOperation.builder().op(Op.ADD).path("/responseParameters/method.response" +
                                        ".header.Access-Control-Allow-Headers")
                                .value("'Content-Type,X-Amz-Date,Authorization,X-Api-Key,X-Amz-Security-Token'").build())
                .build();
        apiGatewayClient.updateIntegrationResponse(updateIntegrationResponseRequest);

        UpdateGatewayResponseRequest updateGatewayResponseRequest = UpdateGatewayResponseRequest.builder()
                .restApiId(apiId).responseType("DEFAULT_4XX")
                .patchOperations(PatchOperation.builder().op(Op.ADD).path("/responseParameters/" +
                                "gatewayresponse.header.Access-Control-Allow-Origin").value("'*'").build(),
                        PatchOperation.builder().op(Op.ADD).path("/responseParameters/gatewayresponse." +
                                "header.Access-Control-Allow-Methods").value("'GET,OPTIONS'").build(),
                        PatchOperation.builder().op(Op.ADD).path("/responseParameters/gatewayresponse." +
                                        "header.Access-Control-Allow-Headers")
                                .value("'Content-Type,X-Amz-Date,Authorization,X-Api-Key,X-Amz-Security-Token'").build())
                .build();
        apiGatewayClient.updateGatewayResponse(updateGatewayResponseRequest);
    }

    public static void configureCORSHeadersAtMethodLevel(String apiId, Resource resource, String httpMethod,
                                                          ApiGatewayClient apiGatewayClient) {
        GetMethodRequest getMethodRequest = GetMethodRequest.builder().restApiId(apiId).resourceId(resource.id())
                .httpMethod(httpMethod).build();
        GetMethodResponse getMethodResponse = apiGatewayClient.getMethod(getMethodRequest);

        if (getMethodResponse.hasMethodResponses()) {
            Map<String, MethodResponse> responses = getMethodResponse.methodResponses();
            for (Map.Entry<String, MethodResponse> entry : responses.entrySet()) {
                UpdateMethodResponseRequest updateMethodResponseRequest = UpdateMethodResponseRequest.builder()
                        .restApiId(apiId).resourceId(resource.id()).httpMethod(httpMethod).statusCode(entry.getKey())
                        .patchOperations(PatchOperation.builder().op(Op.ADD).path("/responseParameters/method" +
                                ".response.header.Access-Control-Allow-Origin").build()).build();
                apiGatewayClient.updateMethodResponse(updateMethodResponseRequest);
            }

            UpdateIntegrationResponseRequest updateIntegrationResponseRequest =
                    UpdateIntegrationResponseRequest.builder()
                            .restApiId(apiId).resourceId(resource.id()).httpMethod(httpMethod).statusCode("200")
                            .patchOperations(PatchOperation.builder().op(Op.ADD).path("/responseParameters/method" +
                                    ".response.header.Access-Control-Allow-Origin").value("'*'").build()).build();
            apiGatewayClient.updateIntegrationResponse(updateIntegrationResponseRequest);
        }
    }

    public static CreateAuthorizerResponse getAuthorizer(String awsApiId, String name, String lambdaArn, String roleArn,
                                                         String region, ApiGatewayClient apiGatewayClient) {

        CreateAuthorizerRequest createAuthorizerRequest = CreateAuthorizerRequest.builder()
                .restApiId(awsApiId)
                .name(name + "-authorizer")
                .type(AuthorizerType.TOKEN)
                .identitySource("method.request.header.Authorization")
                .authorizerUri("arn:aws:apigateway:" + region + ":lambda:path/2015-03-31/functions/" + lambdaArn +
                        "/invocations")
                .authorizerCredentials(roleArn)
                .authorizerResultTtlInSeconds(0)
                .build();
        return apiGatewayClient.createAuthorizer(createAuthorizerRequest);
    }

    public static void deleteAuthorizer(String awsApiId, String authorizerId, ApiGatewayClient apiGatewayClient) {
        DeleteAuthorizerRequest deleteAuthorizerRequest = DeleteAuthorizerRequest.builder()
                .restApiId(awsApiId)
                .authorizerId(authorizerId)
                .build();

        apiGatewayClient.deleteAuthorizer(deleteAuthorizerRequest);
    }

    public static List<String> extractPathParams(String path) {
        List<String> pathParams = new ArrayList<>();
        int start = path.indexOf("{");

        while(start < path.length()) {
            int end = path.indexOf("}", start);
            if (end == -1) {
                break;
            }

            pathParams.add(path.substring(start + 1, end));
            start = path.indexOf("{", end);
            if (start == -1) {
                break;
            }
        }
        return pathParams;
    }
}
