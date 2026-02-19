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

import org.wso2.azure.gw.client.AzureConstants;
import org.wso2.azure.gw.client.util.AzureAPIUtil;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.Environment;

/**
 * Builder for REST/HTTP/SOAP APIs from Azure API Management.
 * Extends AzureAPIBuilder which provides common Azure logic.
 * Only implements API-type-specific logic for REST APIs.
 */
public class AzureRestAPIBuilder extends AzureAPIBuilder {

    public AzureRestAPIBuilder(ApiManagementManager manager, HttpClient httpClient,
                              String resourceGroup, String serviceName) {
        super(manager, httpClient, resourceGroup, serviceName);
    }

    @Override
    public boolean canHandle(ApiContract sourceApi) {
        return sourceApi.apiType() == null || ApiType.HTTP.equals(sourceApi.apiType());
    }

    @Override
    protected void mapSpecificDetails(API api, ApiContract sourceApi,
                                      Environment environment) throws APIManagementException {
        api.setType(AzureConstants.AZURE_API_TYPE_HTTP);
        api.setTransports(AzureConstants.AZURE_HTTP_TRANSPORTS);
        
        String definition = AzureAPIUtil.getRestApiDefinition(manager, httpClient, sourceApi);
        if (definition != null && !definition.isEmpty()) {
            api.setSwaggerDefinition(definition);
        }
        
        if (sourceApi.serviceUrl() != null) {
            api.setEndpointConfig(AzureAPIUtil.buildEndpointConfigJson(
                    sourceApi.serviceUrl(), sourceApi.serviceUrl(), false, AzureConstants.AZURE_PROTOCOL_HTTP));
        }

    }
}
