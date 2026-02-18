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

import org.wso2.carbon.apimgt.api.FederatedAPIBuilder;

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
public abstract class AWSAPIBuilder extends FederatedAPIBuilder<Object> {

    /**
     * Creates a reference artifact string for the given raw SDK API object.
     * Used for change detection (comparing current vs. previously discovered state).
     *
     * @param rawApi The raw SDK object (RestApi for V1, Api for V2).
     * @return A string representing the reference artifact.
     */
    public abstract String createReferenceArtifact(Object rawApi);
}

