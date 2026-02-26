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

import java.util.UUID;

import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.APIIdentifier;
import org.wso2.carbon.apimgt.api.model.Environment;

/**
 * Abstract base for all AWS API Gateway builders.
 *
 * <p>Extends the carbon-apimgt {@link FederatedAPIBuilder} template method pattern
 * (which handles common API metadata mapping) and adds AWS-specific reference
 * artifact creation for change detection.
 *
 * <p>Concrete subclasses:
 * <ul>
 *   <li>{@link AWSRestAPIBuilder} — V1 REST APIs</li>
 *   <li>{@link AWSHTTPAPIBuilder} — V2 HTTP APIs</li>
 *   <li>{@link AWSWebSocketAPIBuilder} — V2 WebSocket APIs</li>
 * </ul>
 *
 * @see AWSAPIBuilderFactory
 */
public abstract class AWSAPIBuilder<T> {

    /**
     * Builds a WSO2 API object from the raw external data.
     *
     * @param sourceApi The raw data object from the external gateway.
     * @param env     The environment where the API is discovered.
     * @param org     The organization context.
     * @return The constructed API object.
     * @throws APIManagementException If an error occurs during building.
     */
    public API build(T sourceApi, Environment env, String org) throws APIManagementException {
        // 1. Basic Identification
        APIIdentifier apiId = new APIIdentifier(org, getName(sourceApi), getVersion(sourceApi));

        API api = new API(apiId);

        // 2. Map Common Properties
        api.setContext(getContext(sourceApi));
        api.setContextTemplate(getContextTemplate(sourceApi));
        api.setUuid(UUID.randomUUID().toString());
        api.setDescription(getDescription(sourceApi));

        // 3. Set Standard WSO2 Flags
        api.setOrganization(org);
        if (env != null) {
            api.setGatewayType(env.getGatewayType());
        }
        api.setInitiatedFromGateway(true);
        api.setRevision(false);
        api.setGatewayVendor("external");

        // 4. Specific Mapping (Delegated to subclasses)
        mapSpecificDetails(api, sourceApi, env);

        return api;
    }
    
    protected abstract String getName(T sourceApi);
    
    protected abstract String getVersion(T sourceApi);
    
    protected abstract String getDescription(T sourceApi);
    
    protected abstract String getContext(T sourceApi);
    
    protected abstract String getContextTemplate(T sourceApi);

    /**
     * Maps type-specific details (protocol, endpoints, definitions, etc.) to the API object.
     *
     * @param api     The WSO2 API object to populate.
     * @param sourceApi The raw data object.
     * @throws APIManagementException If an error occurs during mapping.
     */
    protected abstract void mapSpecificDetails(API api, T sourceApi, Environment env) throws APIManagementException;

    /**
     * Checks if this builder can handle the given raw data object.
     *
     * <p>Accepts {@code Object} so the factory can call this through {@code AWSAPIBuilder<?>}
     * without a wildcard-capture compile error. Implementations must check {@code instanceof}
     * before casting.
     *
     * @param sourceApi The raw data object from the gateway.
     * @return {@code true} if this builder can handle the object, {@code false} otherwise.
     */
    public abstract boolean canHandle(Object sourceApi);

    /**
     * Creates a reference artifact string for the given raw SDK API object.
     * Used for change detection (comparing current vs. previously discovered state).
     *
     * @param rawApi The raw SDK object (RestApi for V1, Api for V2).
     * @return A string representing the reference artifact.
     */
    public abstract String createReferenceArtifact(T rawApi);
}

