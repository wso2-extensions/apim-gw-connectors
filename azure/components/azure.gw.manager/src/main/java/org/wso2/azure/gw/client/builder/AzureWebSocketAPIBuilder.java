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

package org.wso2.azure.gw.client.builder;

import com.azure.core.http.HttpClient;
import com.azure.resourcemanager.apimanagement.ApiManagementManager;
import com.azure.resourcemanager.apimanagement.models.ApiContract;
import com.azure.resourcemanager.apimanagement.models.ApiType;
import com.azure.resourcemanager.apimanagement.models.Protocol;
import org.wso2.azure.gw.client.AzureConstants;
import org.wso2.azure.gw.client.util.AzureAPIUtil;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.Environment;
import org.wso2.carbon.apimgt.api.model.Tier;

import java.util.HashSet;

/**
 * Builder for WebSocket APIs from Azure API Management.
 * Extends AzureAPIBuilder which provides common Azure logic.
 * Overrides getContext() to exclude version from WebSocket paths.
 */
public class AzureWebSocketAPIBuilder extends AzureAPIBuilder {

    public AzureWebSocketAPIBuilder(ApiManagementManager manager, HttpClient httpClient,
                                   String resourceGroup, String serviceName) {
        super(manager, httpClient, resourceGroup, serviceName);
    }

    @Override
    public boolean canHandle(ApiContract data) {
        return ApiType.WEBSOCKET.equals(data.apiType());
    }
    
    @Override
    protected String getContext(ApiContract sourceApi) {
        String path = sourceApi.path();
        if (path == null || path.isEmpty()) {
            path = AzureConstants.AZURE_NO_CONTEXT;
        }
        return "/" + path;
    }
    
    @Override
    protected String getContextTemplate(ApiContract sourceApi) {
        String path = sourceApi.path();
        String context = "/";
        context += (path == null || path.isEmpty()) ? AzureConstants.AZURE_NO_CONTEXT : path;
        return context;
    }

    @Override
    protected void mapSpecificDetails(API api, ApiContract data,
                                      Environment environment) throws APIManagementException {
        api.setType(AzureConstants.AZURE_API_TYPE_WEBSOCKET);
        api.setTransports(AzureConstants.AZURE_WEBSOCKET_TRANSPORTS);
        
        java.util.List<Protocol> protocols = data.protocols();
        String protocol = (protocols != null && protocols.contains(Protocol.WSS))
                ? AzureConstants.AZURE_PROTOCOL_WSS : AzureConstants.AZURE_PROTOCOL_WS;
        
        String productionUrl = AzureAPIUtil.buildWebSocketUrl(environment, data, protocol);
        String asyncApiDefinition = AzureAPIUtil.buildAsyncApiDefinition(
                data.displayName(), getVersion(data), productionUrl, protocol);
        api.setAsyncApiDefinition(asyncApiDefinition);
        
        if (data.serviceUrl() != null) {
            api.setEndpointConfig(AzureAPIUtil.buildEndpointConfigJson(
                    data.serviceUrl(), data.serviceUrl(), false, protocol));
        }
        api.setAvailableTiers(new HashSet<>(java.util.Collections.singleton(new Tier("Unlimited"))));
    }
}
