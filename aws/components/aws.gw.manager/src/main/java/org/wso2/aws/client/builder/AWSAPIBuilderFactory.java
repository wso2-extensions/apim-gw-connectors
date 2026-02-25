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

import java.util.ArrayList;
import java.util.List;

import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigatewayv2.ApiGatewayV2Client;

/**
 * AWS-specific implementation
 *
 * <p>Registers builders for all supported AWS API Gateway types:
 * <ul>
 *   <li>{@link AWSRestAPIBuilder} — V1 REST APIs</li>
 *   <li>{@link AWSHTTPAPIBuilder} — V2 HTTP APIs</li>
 *   <li>{@link AWSWebSocketAPIBuilder} — V2 WebSocket APIs</li>
 * </ul>
 *
 * <p>The inherited {@code getBuilder(Object)} selects the appropriate builder
 * via the Strategy pattern ({@code canHandle()} on each registered builder).
 */
public class AWSAPIBuilderFactory {

    private final List<AWSAPIBuilder<?>> builders;

    public AWSAPIBuilderFactory(ApiGatewayClient v1Client, ApiGatewayV2Client v2Client,
                                String region, String stage) {
        this.builders = new ArrayList<>();
        initBuilders(v1Client, v2Client, region, stage);
    }

    private void initBuilders(ApiGatewayClient v1Client, ApiGatewayV2Client v2Client,
                                String region, String stage) {
        registerBuilder(new AWSRestAPIBuilder(v1Client, region, stage));
        registerBuilder(new AWSHTTPAPIBuilder(v2Client, region, stage));
        registerBuilder(new AWSWebSocketAPIBuilder(v2Client, region, stage));
    }

    /**
     * Returns the first registered builder that can handle the given raw API data.
     *
     * @param sourceApi The raw API data from the gateway (e.g., {@code RestApi} or {@code Api})
     * @return The matching {@link AWSAPIBuilder}
     * @throws IllegalStateException if no registered builder can handle the given type
     */
    public AWSAPIBuilder<?> getBuilder(Object sourceApi) {
        for (AWSAPIBuilder<?> builder : builders) {
            if (builder.canHandle(sourceApi)) {
                return builder;
            }
        }
        throw new IllegalStateException("No registered builder can handle the given API data");
    }
    
    /**
     * Registers a builder in the factory. Duplicate types (same class) are ignored.
     *
     * @param builder The builder to register
     */
    protected void registerBuilder(AWSAPIBuilder<?> builder) {
        if (builder == null) {
            return;
        }
        for (AWSAPIBuilder<?> existing : builders) {
            if (existing.getClass().equals(builder.getClass())) {
                return;
            }
        }
        builders.add(builder);
    }
    
    /**
     * Gets all registered builders.
     * Useful for testing and debugging.
     * 
     * @return List of all registered builders
     */
    public List<AWSAPIBuilder<?>> getRegisteredBuilders() {
        return new ArrayList<>(builders);
    }
    
    /**
     * Gets the count of registered builders.
     * 
     * @return Number of registered builders
     */
    public int getBuilderCount() {
        return builders.size();
    }
}
