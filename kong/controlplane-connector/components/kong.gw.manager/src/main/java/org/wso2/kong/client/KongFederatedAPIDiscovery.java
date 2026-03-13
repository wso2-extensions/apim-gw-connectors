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

package org.wso2.kong.client;

import com.google.gson.Gson;

import feign.Feign;
import feign.RequestInterceptor;

import feign.gson.GsonDecoder;
import feign.gson.GsonEncoder;
import feign.slf4j.Slf4jLogger;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.FederatedAPIDiscovery;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.DiscoveredAPI;
import org.wso2.carbon.apimgt.api.model.Environment;
import org.wso2.carbon.apimgt.api.FederatedAPIBuilder;
import org.wso2.carbon.apimgt.impl.kmclient.ApacheFeignHttpClient;

import org.wso2.kong.client.builder.KongAPIBuilderFactory;
import org.wso2.kong.client.builder.KongApiBundle;
import org.wso2.kong.client.model.KongAPI;
import org.wso2.kong.client.model.KongAPIImplementation;
import org.wso2.kong.client.model.KongListResponse;
import org.wso2.kong.client.model.KongPlugin;
import org.wso2.kong.client.model.KongRoute;
import org.wso2.kong.client.model.KongService;
import org.wso2.kong.client.model.PagedResponse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * This class implements the FederatedAPIDiscovery interface to discover APIs from Kong Konnect.
 */
public class KongFederatedAPIDiscovery implements FederatedAPIDiscovery {

    private static final Log log = LogFactory.getLog(KongFederatedAPIDiscovery.class);

    private Environment environment;
    private KongKonnectApi apiGatewayClient;
    private String organization;
    private String adminURL;
    private String controlPlaneId;
    private String authToken;
    private String deploymentType;
    private KongAPIBuilderFactory builderFactory;

    @Override
    public void init(Environment environment, String organization) throws APIManagementException {
        log.debug("Initializing Kong Gateway Federation for environment: " + environment.getName());
        try {
            this.environment = environment;
            this.organization = organization;
            this.deploymentType = environment.getAdditionalProperties().get(KongConstants.KONG_DEPLOYMENT_TYPE);
            if (!Objects.equals(deploymentType, KongConstants.KONG_KUBERNETES_DEPLOYMENT)) {
                this.adminURL = environment.getAdditionalProperties().get(KongConstants.KONG_ADMIN_URL);
                this.controlPlaneId = environment.getAdditionalProperties().get(KongConstants.KONG_CONTROL_PLANE_ID);
                this.authToken = environment.getAdditionalProperties().get(KongConstants.KONG_AUTH_TOKEN);

                if (adminURL == null || controlPlaneId == null || authToken == null) {
                    throw new APIManagementException("Missing required Kong environment configurations");
                }
                // Build Apache HttpClient (add timeouts/SSL as needed)
                CloseableHttpClient httpClient = HttpClients.custom().build();

                // Bearer token interceptor
                RequestInterceptor auth = template ->
                        template.header(KongConstants.AUTHORIZATION_HEADER, KongConstants.BEARER_PREFIX + authToken);
                apiGatewayClient = Feign.builder()
                        .client(new ApacheFeignHttpClient(httpClient))
                        .encoder(new GsonEncoder())
                        .decoder(new GsonDecoder())
                        .logger(new Slf4jLogger(KongKonnectApi.class))
                        .requestInterceptor(auth)
                        .target(KongKonnectApi.class, adminURL);
                
                // Initialize the builder factory
                this.builderFactory = new KongAPIBuilderFactory(apiGatewayClient, controlPlaneId, organization);
            }
            log.debug("Initialization completed Kong Gateway Deployer for environment: " + environment.getName());
        } catch (Exception e) {
            throw new APIManagementException("Error occurred while initializing Kong Gateway Deployer", e);
        }
    }

    @Override
    public List<DiscoveredAPI> discoverAPI() {
        if (!Objects.equals(deploymentType, KongConstants.KONG_KUBERNETES_DEPLOYMENT)) {
            try {
                // List APIs (V3)
                KongListResponse<KongAPI> apisResp = apiGatewayClient.listAPIs(KongConstants.DEFAULT_API_LIST_LIMIT);
                List<KongAPI> apis = (apisResp != null && apisResp.getData() != null)
                        ? apisResp.getData() : Collections.<KongAPI>emptyList();

                // List implementations (api_id -> service link)
                KongListResponse<KongAPIImplementation> implResp = apiGatewayClient.listAPIImplementations(
                        KongConstants.DEFAULT_API_LIST_LIMIT);
                List<KongAPIImplementation> implementations = (implResp != null && implResp.getData() != null)
                        ? implResp.getData() : Collections.<KongAPIImplementation>emptyList();

                // Build a map: api_id -> (cpId, serviceId)
                Map<String, KongAPIImplementation.ServiceLink> apiToSvc = new HashMap<>();
                for (KongAPIImplementation impl : implementations) {
                    if (impl.getApiId() != null && impl.getService() != null) {
                        apiToSvc.put(impl.getApiId(), impl.getService());
                    }
                }

                List<DiscoveredAPI> retrievedAPIs = new ArrayList<>();
                Set<String> linkedServices = new HashSet<>();
                Gson gson = new Gson();
                
                // Get vhost for URL construction
                String vhost = environment.getVhosts() != null && !environment.getVhosts().isEmpty() ?
                        environment.getVhosts().get(0).getHost() : KongConstants.DEFAULT_VHOST;

                // Iterate APIs and build using factory pattern
                for (KongAPI kongAPI : apis) {
                    try {
                        String apiId = kongAPI.getId();
                        
                        // Get service link for this API
                        KongAPIImplementation.ServiceLink link = apiToSvc.get(apiId);
                        if (link == null || link.getControlPlaneId() == null || link.getId() == null) {
                            log.warn("No service linked for API: " + kongAPI.getName() + " (ID: " + apiId + ")");
                            continue;
                        }
                        
                        String cpId = link.getControlPlaneId();
                        String serviceId = link.getId();
                        
                        // Fetch service
                        KongService svc = apiGatewayClient.getService(cpId, serviceId);
                        if (svc == null) {
                            log.warn("Service not found for API: " + kongAPI.getName());
                            continue;
                        }
                        
                        // Mark service as linked
                        linkedServices.add(svc.getId());
                        
                        // Fetch routes for this service
                        PagedResponse<KongRoute> routesResp = apiGatewayClient.listRoutesByServiceId(
                            controlPlaneId, svc.getId(), KongConstants.DEFAULT_ROUTE_LIST_LIMIT);
                        List<KongRoute> routes = (routesResp != null && routesResp.getData() != null) ?
                            routesResp.getData() : Collections.emptyList();
                        
                        // Fetch plugins for this service
                        PagedResponse<KongPlugin> pluginsResp = apiGatewayClient.listPluginsByServiceId(
                            controlPlaneId, svc.getId(), KongConstants.DEFAULT_PLUGIN_LIST_LIMIT);
                        List<KongPlugin> plugins = (pluginsResp != null && pluginsResp.getData() != null) ?
                            pluginsResp.getData() : Collections.emptyList();
                        
                        // Create KongApiBundle
                        KongApiBundle kongApiBundle = new KongApiBundle(kongAPI, svc, routes, plugins, vhost);
                        
                        // Use factory to get appropriate builder
                        FederatedAPIBuilder<KongApiBundle> builder = builderFactory.getBuilder(kongApiBundle);
                        if (builder == null) {
                            log.warn("No builder found for API: " + kongAPI.getName());
                            continue;
                        }
                        
                        // Build the WSO2 API using the builder
                        API api = builder.build(kongApiBundle, environment, organization);
                        
                        // Create DiscoveredAPI with reference artifact
                        DiscoveredAPI discoveredAPI = new DiscoveredAPI(api, gson.toJson(api));
                        retrievedAPIs.add(discoveredAPI);
                        
                    } catch (Exception e) {
                        log.error("Error processing Kong API: " + kongAPI.getName(), e);
                    }
                }

                // Process Services without API metadata ("raw services")
                PagedResponse<KongService> servicesResp = apiGatewayClient.listServices(controlPlaneId,
                        KongConstants.DEFAULT_SERVICE_LIST_LIMIT);
                List<KongService> services;
                if (servicesResp != null && servicesResp.getData() != null) {
                    services = servicesResp.getData();
                } else {
                    services = Collections.emptyList();
                }

                for (KongService svc : services) {
                    try {
                        // Skip if this service is already linked to an API
                        if (linkedServices.contains(svc.getId())) {
                            continue;
                        }
                        
                        // Fetch routes for this service
                        PagedResponse<KongRoute> routesResp = apiGatewayClient.listRoutesByServiceId(
                            controlPlaneId, svc.getId(), KongConstants.DEFAULT_ROUTE_LIST_LIMIT);
                        List<KongRoute> routes = (routesResp != null && routesResp.getData() != null) ?
                            routesResp.getData() : Collections.emptyList();

                        // Fetch plugins for this service
                        PagedResponse<KongPlugin> pluginsResp = apiGatewayClient.listPluginsByServiceId(
                            controlPlaneId, svc.getId(), KongConstants.DEFAULT_PLUGIN_LIST_LIMIT);
                        List<KongPlugin> plugins = (pluginsResp != null && pluginsResp.getData() != null) ?
                            pluginsResp.getData() : Collections.emptyList();

                        // Create KongApiBundle without API metadata (null api)
                        KongApiBundle kongApiBundle = new KongApiBundle(null, svc, routes, plugins, vhost);
                        
                        // Use factory to get appropriate builder
                        FederatedAPIBuilder<KongApiBundle> builder = builderFactory.getBuilder(kongApiBundle);
                        if (builder == null) {
                            log.warn("No builder found for service: " + svc.getName());
                            continue;
                        }
                        
                        // Build the WSO2 API using the builder
                        API api = builder.build(kongApiBundle, environment, organization);
                        
                        // Set timestamps if available
                        if (svc.getUpdatedAt() != null) {
                            api.setLastUpdated(Date.from(java.time.Instant.ofEpochSecond(svc.getUpdatedAt())));
                        }
                        if (svc.getCreatedAt() != null) {
                            api.setCreatedTime(Long.toString(svc.getCreatedAt()));
                        }
                        
                        // Create DiscoveredAPI with reference artifact
                        DiscoveredAPI discoveredAPI = new DiscoveredAPI(api, gson.toJson(api));
                        retrievedAPIs.add(discoveredAPI);
                        
                    } catch (Exception e) {
                        log.error("Error processing Kong service: " + svc.getName(), e);
                    }
                }
                return retrievedAPIs;
            } catch (KongGatewayException e) {
                log.error("Kong Konnect discovery failed (status " + e.getStatusCode() + "): " + e.getMessage(), e);
                return Collections.emptyList();
            } catch (feign.FeignException e) {
                log.error("Kong Konnect discovery failed (status " + e.status() + "): " + e.getMessage(), e);
                return Collections.emptyList();
            } catch (Exception ex) {
                log.error("Unexpected error during Kong Konnect discovery: " + ex.getMessage(), ex);
                return Collections.emptyList();
            }
        } else {
            return Collections.emptyList();
        }
    }

    @Override
    public boolean isAPIUpdated(String existingReferenceArtifact, String newReferenceArtifact) {
        return !java.util.Objects.equals(existingReferenceArtifact, newReferenceArtifact);
    }
}
