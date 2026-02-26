/*
 * Copyright (c) 2026 WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
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

package org.wso2.aws.client.builder;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.aws.client.AWSConstants;
import org.wso2.aws.client.util.AWSAPIUtil;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.Environment;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;

import software.amazon.awssdk.services.apigatewayv2.ApiGatewayV2Client;
import software.amazon.awssdk.services.apigatewayv2.model.Api;
import software.amazon.awssdk.services.apigatewayv2.model.ExportApiRequest;
import software.amazon.awssdk.services.apigatewayv2.model.ExportApiResponse;
import software.amazon.awssdk.services.apigatewayv2.model.ProtocolType;

/**
 * Builds WSO2 API objects from AWS API Gateway V2 HTTP APIs.
 *
 * <p>Extends {@link AWSAPIBuilder} (which extends {@link org.wso2.carbon.apimgt.api.FederatedAPIBuilder}).
 * The base class {@code build()} template method handles common metadata mapping;
 * this class implements the abstract extraction methods and {@code mapSpecificDetails}
 * for HTTP-specific concerns (OAS3 export, HTTP endpoint configuration).
 *
 * <p>If the OAS3 definition cannot be fetched (e.g., API not deployed to the configured stage),
 * an {@link APIManagementException} is thrown from {@code mapSpecificDetails()}, which the
 * discovery service catches and logs at debug level.
 */
public class AWSHTTPAPIBuilder extends AWSAPIBuilder<Api> {

    private static final Log log = LogFactory.getLog(AWSHTTPAPIBuilder.class);

    private final ApiGatewayV2Client client;
    private final String region;
    private final String stage;

    public AWSHTTPAPIBuilder(ApiGatewayV2Client client, String region, String stage) {
        this.client = client;
        this.region = region;
        this.stage = stage;
    }

    @Override
    public boolean canHandle(Object sourceApi) {
        return sourceApi instanceof Api && ((Api) sourceApi).protocolType() == ProtocolType.HTTP;
    }

    @Override
    protected String getName(Api sourceApi) {
        Api a = sourceApi;
        return a.name() != null ? a.name() : a.apiId();
    }

    @Override
    protected String getVersion(Api sourceApi) {
        Api a = sourceApi;
        return a.version() != null ? a.version() : AWSConstants.DEFAULT_VERSION;
    }

    @Override
    protected String getContext(Api sourceApi) {
        return "/" + getName(sourceApi).toLowerCase().replace(" ", "-");
    }

    @Override
    protected String getContextTemplate(Api sourceApi) {
        return getContext(sourceApi) + "/{version}";
    }

    @Override
    protected String getDescription(Api sourceApi) {
        return sourceApi.description() != null ? sourceApi.description() : "";
    }

    @Override
    protected void mapSpecificDetails(API api, Api sourceApi, Environment env) throws APIManagementException {
        Api v2Api = sourceApi;
        api.setType(AWSConstants.API_TYPE_HTTP);
        api.setTransports(AWSConstants.HTTP_TRANSPORTS);

        String oasDefinition = getOASDefinition(v2Api.apiId(), getName(sourceApi));
    
        if (oasDefinition == null || oasDefinition.isEmpty()) {
            throw new APIManagementException("Empty OAS3 definition for HTTP API '" 
                    + getName(sourceApi) + "' — skipping");
        }
    
        api.setSwaggerDefinition(oasDefinition);

        String invokeUrl = String.format("https://%s.execute-api.%s.amazonaws.com/%s",
                v2Api.apiId(), region, stage);
        AWSAPIUtil.setEndpointConfigFromUrl(api, invokeUrl);
    }

    @Override
    public String createReferenceArtifact(Api rawApi) {
        Api v2Api = rawApi;
        String swagger = "{}";
        try {
            swagger = getOASDefinition(v2Api.apiId(), getName(rawApi));
        } catch (Exception e) {
            // fallback to empty definition
            log.debug("Failed to retrieve Swagger definition for REST API '" + getName(rawApi) + "': " + e.getMessage());
        }
        return createReferenceArtifact(v2Api, swagger);
    }

    /**
     * Helper method to fetch the OAS3 definition from AWS.
     * Returns the definition as a string, or throws an exception if the export fails.
     */
    private String getOASDefinition(String apiId, String apiName) throws APIManagementException {
        try {
            ExportApiRequest exportRequest = ExportApiRequest.builder()
                    .apiId(apiId)
                    .outputType("JSON")
                    .specification("OAS30")
                    .stageName(stage) // Ensure 'stage' is accessible here
                    .build();

            ExportApiResponse exportResponse = client.exportApi(exportRequest);
            
            if (exportResponse.body() == null) {
                return null;
            }
            
            return exportResponse.body().asUtf8String();

        } catch (Exception e) {
            // Contextualize the error for easier debugging
            throw new APIManagementException("Cannot fetch OAS3 for HTTP API '" 
                    + apiName + "' (not deployed to stage '" + stage + "'?)", e);
        }
    }

    /**
     * Creates a reference artifact by combining a given RestApi object and its Swagger definition in JSON format.
     *
     * @param restApi               The RestApi object containing API details.
     * @param swaggerDefinitionJson The Swagger/OpenAPI definition of the API in JSON format.
     * @return A JSON string that represents the combined reference artifact.
     */
    public static String createReferenceArtifact(Api v2Api, String swaggerDefinitionJson) {
        Gson G = new Gson();
        JsonArray arr = new JsonArray();
        arr.add(G.toJsonTree(v2Api));
        arr.add(JsonParser.parseString(swaggerDefinitionJson));
        return G.toJson(arr);
    }
}    
