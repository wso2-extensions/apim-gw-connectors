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
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.RestApi;

/**
 * Builds WSO2 API objects from AWS API Gateway V1 REST APIs.
 *
 * <p>Extends {@link AWSAPIBuilder} (which extends {@link org.wso2.carbon.apimgt.api.FederatedAPIBuilder}).
 * The base class {@code build()} template method handles common metadata mapping;
 * this class implements the abstract extraction methods and {@code mapSpecificDetails}
 * for REST-specific concerns (Swagger/OAS definition, HTTP endpoint configuration).
 *
 * <p>If the Swagger definition cannot be fetched (e.g., API not deployed to the configured stage),
 * an {@link APIManagementException} is thrown from {@code mapSpecificDetails()}, which the
 * discovery service catches and logs at debug level.
 */
public class AWSRestAPIBuilder extends AWSAPIBuilder<RestApi> {

    private static final Log log = LogFactory.getLog(AWSRestAPIBuilder.class);

    private final ApiGatewayClient client;
    private final String region;
    private final String stage;

    public AWSRestAPIBuilder(ApiGatewayClient client, String region, String stage) {
        this.client = client;
        this.region = region;
        this.stage = stage;
    }

    @Override
    public boolean canHandle(Object sourceApi) {
        return sourceApi instanceof RestApi;
    }

    @Override
    protected String getName(RestApi sourceApi) {
        RestApi r = sourceApi;
        return r.name() != null ? r.name() : r.id();
    }

    @Override
    protected String getVersion(RestApi sourceApi) {
        return stage != null ? stage : AWSConstants.DEFAULT_VERSION;
    }

    @Override
    protected String getContext(RestApi sourceApi) {
        return "/" + getName(sourceApi).toLowerCase().replace(" ", "-");
    }

    @Override
    protected String getContextTemplate(RestApi sourceApi) {
        return getContext(sourceApi) + "/{version}";
    }

    @Override
    protected String getDescription(RestApi sourceApi) {
        return sourceApi.description() != null ? sourceApi.description() : "";
    }

    @Override
    protected void mapSpecificDetails(API api, RestApi sourceApi, Environment env) throws APIManagementException {
        api.setType(AWSConstants.API_TYPE_HTTP);
        api.setTransports(AWSConstants.HTTP_TRANSPORTS);

        String swagger;
        try {
            swagger = AWSAPIUtil.getRestApiDefinition(client, sourceApi.id(), stage);
        } catch (Exception e) {
            throw new APIManagementException("Cannot fetch Swagger for REST API '"
                    + getName(sourceApi) + "' (not deployed to stage '" + stage + "'?)", e);
        }
        if (swagger == null || swagger.isEmpty()) {
            throw new APIManagementException("Empty Swagger definition for REST API '"
                    + getName(sourceApi) + "' — skipping");
        }
        api.setSwaggerDefinition(swagger);
        AWSAPIUtil.setEndpointConfig(api, sourceApi, region, stage);
    }

    @Override
    public String createReferenceArtifact(RestApi rawApi) {
        String swagger = "{}";
        try {
            swagger = AWSAPIUtil.getRestApiDefinition(client, rawApi.id(), stage);
        } catch (Exception e) {
            // fallback to empty definition
            log.debug("Failed to retrieve Swagger definition for REST API '" + getName(rawApi) + "': " + e.getMessage());
        }
        return AWSAPIUtil.createReferenceArtifact(rawApi, swagger);
    }
}
