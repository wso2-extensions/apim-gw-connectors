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

package org.wso2.aws.client.builder;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.aws.client.AWSConstants;
import org.wso2.aws.client.util.AWSAPIUtil;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.Environment;
import org.wso2.carbon.apimgt.api.model.Tier;
import software.amazon.awssdk.services.apigatewayv2.ApiGatewayV2Client;
import software.amazon.awssdk.services.apigatewayv2.model.Api;
import software.amazon.awssdk.services.apigatewayv2.model.ProtocolType;
import software.amazon.awssdk.services.apigatewayv2.model.Route;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/**
 * Builds WSO2 API objects from AWS API Gateway V2 WebSocket APIs.
 *
 * <p>Extends {@link AWSAPIBuilder} (which extends {@link org.wso2.carbon.apimgt.api.FederatedAPIBuilder}).
 * The base class {@code build()} template method handles common metadata mapping;
 * this class implements the abstract extraction methods and {@code mapSpecificDetails}
 * for WebSocket-specific concerns (AsyncAPI definition, route fetching, WS endpoint configuration).
 *
 * <p>WebSocket APIs do not support standard OAS3 export. Instead, an AsyncAPI 2.6.0 definition
 * is generated from the API's routes. The build never returns {@code null} — WebSocket APIs
 * are always created even if route fetching fails.
 */
public class AWSWebSocketAPIBuilder extends AWSAPIBuilder {

    private static final Log log = LogFactory.getLog(AWSWebSocketAPIBuilder.class);

    private final ApiGatewayV2Client client;
    private final String region;
    private final String stage;

    public AWSWebSocketAPIBuilder(ApiGatewayV2Client client, String region, String stage) {
        this.client = client;
        this.region = region;
        this.stage = stage;
    }

    @Override
    public boolean canHandle(Object sourceApi) {
        return sourceApi instanceof Api && ((Api) sourceApi).protocolType() == ProtocolType.WEBSOCKET;
    }

    @Override
    protected String getName(Object sourceApi) {
        Api a = (Api) sourceApi;
        return a.name() != null ? a.name() : a.apiId();
    }

    @Override
    protected String getVersion(Object sourceApi) {
        return stage != null ? stage : AWSConstants.DEFAULT_VERSION;
    }

    @Override
    protected String getContext(Object sourceApi) {
        return "/" + getName(sourceApi).toLowerCase().replace(" ", "-");
    }

    @Override
    protected String getContextTemplate(Object sourceApi) {
        // WebSocket APIs don't include version in context
        return getContext(sourceApi);
    }

    @Override
    protected String getGatewayId(Object sourceApi) {
        return ((Api) sourceApi).apiId();
    }

    @Override
    protected String getDescription(Object sourceApi) {
        Api a = (Api) sourceApi;
        return a.description() != null ? a.description() : "";
    }

    @Override
    protected void mapSpecificDetails(API api, Object sourceApi, Environment env) throws APIManagementException {
        Api v2Api = (Api) sourceApi;
        api.setType(AWSConstants.API_TYPE_WS);
        api.setTransports(AWSConstants.WS_TRANSPORTS);

        // Fetch routes and build AsyncAPI definition
        try {
            List<Route> routes = AWSAPIUtil.getWebSocketRoutes(client, v2Api.apiId());
            String wsEndpointUrl = AWSAPIUtil.getWebSocketEndpointUrl(v2Api.apiId(), region, stage);
            String asyncApiDef = AWSAPIUtil.buildAsyncApiDefinition(v2Api.name(),
                    getVersion(sourceApi), wsEndpointUrl, routes);
            api.setAsyncApiDefinition(asyncApiDef);
        } catch (Exception e) {
            log.warn("Failed to fetch routes for WebSocket API '" + getName(sourceApi) + "': " + e.getMessage());
            // API is still created without async definition
        }

        api.setEndpointConfig(AWSAPIUtil.buildWebSocketEndpointConfig(v2Api.apiId(), region, stage));
        api.setAvailableTiers(new HashSet<>(Collections.singleton(new Tier(AWSConstants.DEFAULT_TIER))));
    }

    @Override
    public String createReferenceArtifact(Object rawApi) {
        Api v2Api = (Api) rawApi;
        List<Route> routes = Collections.emptyList();
        try {
            routes = AWSAPIUtil.getWebSocketRoutes(client, v2Api.apiId());
        } catch (Exception e) {
            // fallback to empty routes
            log.debug("Failed to retrieve routes for WebSocket API '" + getName(rawApi) + "': " + e.getMessage());
        }
        return AWSAPIUtil.createReferenceArtifact(v2Api, routes);
    }
}
