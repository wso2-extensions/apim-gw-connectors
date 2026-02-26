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

package org.wso2.aws.client.discovery;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.aws.client.builder.AWSAPIBuilder;
import org.wso2.aws.client.builder.AWSAPIBuilderFactory;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.DiscoveredAPI;
import org.wso2.carbon.apimgt.api.model.Environment;

import java.util.function.Consumer;

/**
 * Generic discovery loop for AWS API Gateway APIs.
 *
 * <p>Iterates over raw SDK items (from paginator {@code .items()}), uses the
 * {@link AWSAPIBuilderFactory} to locate the correct builder via {@code canHandle()},
 * builds WSO2 API objects using the template-method {@code build()}, and pushes
 * {@link DiscoveredAPI} results to a consumer.
 */
public class AWSDiscoveryService {

    private static final Log log = LogFactory.getLog(AWSDiscoveryService.class);

    /**
     * Discovers APIs from a stream of raw SDK objects.
     *
     * @param <T>      The type of raw SDK object (RestApi for V1, Api for V2).
     * @param items    Iterable of raw SDK API objects (from paginator.items()).
     * @param factory  The builder factory to select the right builder for each item.
     * @param env      The WSO2 environment context.
     * @param org      The organization identifier.
     * @param consumer Consumer that receives each successfully built DiscoveredAPI.
     */
    public <T> void discover(
            Iterable<T> items,
            AWSAPIBuilderFactory factory,
            Environment env,
            String org,
            Consumer<DiscoveredAPI> consumer) {

        for (T raw : items) {
            try {
                @SuppressWarnings("unchecked")
                AWSAPIBuilder<T> builder = (AWSAPIBuilder<T>) factory.getBuilder(raw);
                API api = builder.build(raw, env, org);
                if (api != null) {
                    String reference = builder.createReferenceArtifact(raw);
                    consumer.accept(new DiscoveredAPI(api, reference));
                }
            } catch (APIManagementException e) {
                log.debug("Skipping API: " + e.getMessage());
            } catch (IllegalStateException e) {
                log.warn("No builder registered for API type: " + raw.getClass().getSimpleName(), e);
            } catch (Exception e) {
                log.error("Error building API from raw object: " + raw, e);
            }
        }
    }
}
