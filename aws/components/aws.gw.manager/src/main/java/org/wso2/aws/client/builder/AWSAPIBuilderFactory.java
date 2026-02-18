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

import org.wso2.carbon.apimgt.api.FederatedBuilderFactory;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigatewayv2.ApiGatewayV2Client;

/**
 * AWS-specific implementation of {@link FederatedBuilderFactory}.
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
public class AWSAPIBuilderFactory extends FederatedBuilderFactory<Object> {

    public AWSAPIBuilderFactory(ApiGatewayClient v1Client, ApiGatewayV2Client v2Client,
                                String region, String stage) {
        super();
        registerBuilder(new AWSRestAPIBuilder(v1Client, region, stage));
        registerBuilder(new AWSHTTPAPIBuilder(v2Client, region, stage));
        registerBuilder(new AWSWebSocketAPIBuilder(v2Client, region, stage));
    }

    /**
     * Returns the builder as {@link AWSAPIBuilder} to provide access to
     * AWS-specific methods like {@code createReferenceArtifact()}.
     */
    @Override
    public AWSAPIBuilder getBuilder(Object sourceApi) {
        return (AWSAPIBuilder) super.getBuilder(sourceApi);
    }
}
