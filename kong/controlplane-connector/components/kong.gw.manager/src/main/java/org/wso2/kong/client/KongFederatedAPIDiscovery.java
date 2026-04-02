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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

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
import org.wso2.carbon.apimgt.api.model.APIIdentifier;
import org.wso2.carbon.apimgt.api.model.DiscoveredAPI;
import org.wso2.carbon.apimgt.api.model.Environment;
import org.wso2.carbon.apimgt.api.model.Tier;
import org.wso2.carbon.apimgt.impl.kmclient.ApacheFeignHttpClient;

import org.wso2.kong.client.model.KongAPI;
import org.wso2.kong.client.model.KongAPIImplementation;
import org.wso2.kong.client.model.KongAPISpec;
import org.wso2.kong.client.model.KongListResponse;
import org.wso2.kong.client.model.KongPlugin;
import org.wso2.kong.client.model.KongRoute;
import org.wso2.kong.client.model.KongService;
import org.wso2.kong.client.model.PagedResponse;
import org.wso2.kong.client.util.KongAPIUtil;

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

                // Iterate APIs
                for (KongAPI kongAPI : apis) {
                    String apiName = kongAPI.getName();
                    String apiVersion = kongAPI.getVersion();
                    String apiContext = kongAPI.getSlug();
                    String apiId = kongAPI.getId();

                    // WSO2 API object
                    APIIdentifier apiIdentifier = new APIIdentifier(KongConstants.DEFAULT_API_PROVIDER, apiName,
                            apiVersion);
                    API api = new API(apiIdentifier);
                    api.setDisplayName(apiName);
                    api.setContext(KongAPIUtil.ensureLeadingSlash(apiContext));
                    api.setContextTemplate(apiContext != null ? apiContext.toLowerCase() : null);
                    api.setUuid(apiId);
                    api.setDescription(kongAPI.getDescription() != null ? kongAPI.getDescription() : "");
                    api.setOrganization(organization);
                    api.setRevision(false);
                    api.setInitiatedFromGateway(true);
                    api.setGatewayVendor(KongConstants.DEFAULT_GATEWAY_VENDOR);
                    api.setGatewayType(environment.getGatewayType());

                    // Fetch and set OAS definition (first spec id if present)
                    String oas = null;
                    if (kongAPI.getApiSpecIds() != null && !kongAPI.getApiSpecIds().isEmpty()) {
                        String specId = kongAPI.getApiSpecIds().get(0);
                        KongAPISpec spec = apiGatewayClient.getAPISpec(apiId, specId);
                        if (spec != null && spec.getContent() != null) {
                            oas = spec.getContent(); // raw OAS (JSON/YAML string)
                        }
                    }
                    if (oas != null) {
                        api.setSwaggerDefinition(oas);
                    }

                    // Map API -> Service via implementations, then fetch Service (V2) and set endpoints
                    KongService svc = null;
                    KongAPIImplementation.ServiceLink link = apiToSvc.get(apiId);
                    if (link != null && link.getControlPlaneId() != null && link.getId() != null) {
                        String cpId = link.getControlPlaneId();
                        String serviceId = link.getId();
                        svc = apiGatewayClient.getService(cpId, serviceId);
                        if (svc != null && svc.getHost() != null && svc.getProtocol() != null &&
                                svc.getPort() != null) {
                            String endpoint = KongAPIUtil.buildEndpointUrl(
                                    svc.getProtocol(),
                                    svc.getHost(),
                                    svc.getPort(),
                                    svc.getPath()
                            );
                            api.setEndpointConfig(KongAPIUtil.buildEndpointConfigJson(endpoint, endpoint, false));
                        }
                    }

                    api.setAvailableTiers(new HashSet<>(Collections.singleton(new Tier(KongConstants.DEFAULT_TIER))));

                    String selectedAPILevelRateLimitPolicy = null;

                    if (svc == null) {
                        log.warn("No service found for API: " + apiName + " (ID: " + apiId + ")");
                        continue; // Skip this API if no service is linked
                    }

                    // add to linked services to avoid duplicates
                    linkedServices.add(svc.getId());

                    PagedResponse<KongRoute> routesResp = apiGatewayClient.listRoutesByServiceId(controlPlaneId,
                            svc.getId(), KongConstants.DEFAULT_ROUTE_LIST_LIMIT);
                    List<KongRoute> routes = (routesResp != null && routesResp.getData() != null)
                            ? routesResp.getData() : Collections.<KongRoute>emptyList();
                    List<KongPlugin> plugins = collectPluginsForServiceAndRoutes(svc.getId(), routes);

                    for (KongPlugin plugin : plugins) {
                        String pluginType = plugin.getName();

                        if (KongConstants.KONG_CORS_PLUGIN_TYPE.equals(pluginType)) {
                            api.setCorsConfiguration(KongAPIUtil.kongCorsToWso2Cors(plugin));
                            continue;
                        }

                        if (KongConstants.KONG_RATELIMIT_ADVANCED_PLUGIN_TYPE.equals(
                                pluginType) && selectedAPILevelRateLimitPolicy == null) {
                            String p = KongAPIUtil.kongRateLimitingToWso2Policy(plugin);
                            if (p != null) {
                                selectedAPILevelRateLimitPolicy = p;
                            }
                            continue;
                        }

                        if (KongConstants.KONG_RATELIMIT_PLUGIN_TYPE.equals(
                                pluginType) && selectedAPILevelRateLimitPolicy == null) {
                            String p = KongAPIUtil.kongRateLimitingStandardToWso2Policy(plugin);
                            if (p != null) {
                                selectedAPILevelRateLimitPolicy = p;
                            }
                        }
                    }

                    String apiKeyHeader = resolveKeyAuthHeader(plugins);
                    boolean apiKeyEnabled = apiKeyHeader != null;
                    if (apiKeyEnabled) {
                        api.setApiSecurity(KongConstants.KONG_API_SECURITY_API_KEY);
                        api.setApiKeyHeader(apiKeyHeader);
                        if (api.getSwaggerDefinition() != null) {
                            api.setSwaggerDefinition(KongAPIUtil.applyApiKeySecurityToOas(
                                    api.getSwaggerDefinition(), apiKeyHeader));
                        }
                    }
                    if (selectedAPILevelRateLimitPolicy != null) {
                        api.setApiLevelPolicy(selectedAPILevelRateLimitPolicy);
                    }
                    String referenceArtifact = generateReferenceArtifact(gson, api.getUuid(), apiKeyEnabled,
                            apiKeyHeader, kongAPI);
                    DiscoveredAPI discoveredAPI = new DiscoveredAPI(api, referenceArtifact);
                    retrievedAPIs.add(discoveredAPI);
                }

                // If there are Services without APIs, we can still retrieve them as APIs
                PagedResponse<KongService> servicesResp = apiGatewayClient.listServices(controlPlaneId,
                        KongConstants.DEFAULT_SERVICE_LIST_LIMIT);
                List<KongService> services;
                if (servicesResp != null && servicesResp.getData() != null) {
                    services = servicesResp.getData();
                } else {
                    services = java.util.Collections.emptyList();
                }

                for (KongService svc : services) {
                    // Skip if this service is already linked to an API
                    if (linkedServices.contains(svc.getId())) {
                        continue;
                    }
                    PagedResponse<KongRoute> resp = apiGatewayClient.listRoutesByServiceId(controlPlaneId, svc.getId(),
                            KongConstants.DEFAULT_ROUTE_LIST_LIMIT);
                    List<KongRoute> routes = (resp != null && resp.getData() != null) ?
                            resp.getData() : java.util.Collections.emptyList();

                    List<KongPlugin> plugins = collectPluginsForServiceAndRoutes(svc.getId(), routes);

                    APIIdentifier apiId = new APIIdentifier(KongConstants.DEFAULT_API_PROVIDER, svc.getName(),
                            KongConstants.DEFAULT_API_VERSION);
                    API api = new API(apiId);
                    api.setDisplayName(svc.getName());
                    api.setContext(svc.getName());
                    api.setContextTemplate(svc.getName().toLowerCase().replace(" ", "-"));
                    api.setUuid(svc.getId());
                    api.setDescription("");
                    api.setOrganization(organization);
                    api.setRevision(false);

                    if (svc.getUpdatedAt() != null) {
                        api.setLastUpdated(Date.from(java.time.Instant.ofEpochSecond(svc.getUpdatedAt())));
                    }
                    if (svc.getCreatedAt() != null) {
                        api.setCreatedTime(Long.toString(svc.getCreatedAt()));
                    }

                    api.setInitiatedFromGateway(true);
                    api.setGatewayVendor(KongConstants.DEFAULT_GATEWAY_VENDOR);
                    api.setGatewayType(environment.getGatewayType());

                    String vhost = environment.getVhosts() != null && !environment.getVhosts().isEmpty() ?
                            environment.getVhosts().get(0).getHost() :
                            KongConstants.DEFAULT_VHOST;

                    String apiDefinition = KongAPIUtil.buildOasFromRoutes(svc, routes, vhost);
                    api.setSwaggerDefinition(apiDefinition);
                    String endpoint = KongAPIUtil.buildEndpointUrl(svc.getProtocol(), svc.getHost(), svc.getPort(),
                            svc.getPath());
                    api.setEndpointConfig(KongAPIUtil.buildEndpointConfigJson(endpoint, endpoint, false));
                    api.setAvailableTiers(
                            new HashSet<>(java.util.Collections.singleton(new Tier(KongConstants.DEFAULT_TIER))));

                    String selectedAPILevelRateLimitPolicy = null;

                    for (KongPlugin plugin : plugins) {
                        String pluginType = plugin.getName();

                        if (KongConstants.KONG_CORS_PLUGIN_TYPE.equals(pluginType)) {
                            api.setCorsConfiguration(KongAPIUtil.kongCorsToWso2Cors(plugin));
                            continue;
                        }

                        if (KongConstants.KONG_RATELIMIT_ADVANCED_PLUGIN_TYPE.equals(
                                pluginType) && selectedAPILevelRateLimitPolicy == null) {
                            String p = KongAPIUtil.kongRateLimitingToWso2Policy(plugin);
                            if (p != null) {
                                selectedAPILevelRateLimitPolicy = p;
                            }
                            continue;
                        }

                        if (KongConstants.KONG_RATELIMIT_PLUGIN_TYPE.equals(
                                pluginType) && selectedAPILevelRateLimitPolicy == null) {
                            String p = KongAPIUtil.kongRateLimitingStandardToWso2Policy(plugin);
                            if (p != null) {
                                selectedAPILevelRateLimitPolicy = p;
                            }
                        }
                    }

                    String apiKeyHeader = resolveKeyAuthHeader(plugins);
                    boolean apiKeyEnabled = apiKeyHeader != null;
                    if (apiKeyEnabled) {
                        api.setApiSecurity(KongConstants.KONG_API_SECURITY_API_KEY);
                        api.setApiKeyHeader(apiKeyHeader);
                        api.setSwaggerDefinition(KongAPIUtil.applyApiKeySecurityToOas(api.getSwaggerDefinition(),
                                apiKeyHeader));
                    }
                    if (selectedAPILevelRateLimitPolicy != null) {
                        api.setApiLevelPolicy(selectedAPILevelRateLimitPolicy);
                    }
                    String referenceArtifact = generateReferenceArtifact(gson, api.getUuid(), apiKeyEnabled,
                            apiKeyHeader, svc);
                    DiscoveredAPI discoveredAPI = new DiscoveredAPI(api, referenceArtifact);
                    retrievedAPIs.add(discoveredAPI);
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

    private String resolveKeyAuthHeader(List<KongPlugin> plugins) {
        if (plugins == null) {
            return null;
        }
        for (KongPlugin plugin : plugins) {
            if (plugin == null || plugin.getName() == null) {
                continue;
            }
            if (KongConstants.KONG_KEY_AUTH_PLUGIN_TYPE.equals(plugin.getName())
                    && !Boolean.FALSE.equals(plugin.getEnabled())) {
                return KongAPIUtil.resolveApiKeyHeader(plugin);
            }
        }
        return null;
    }

    private String generateReferenceArtifact(Gson gson, String apiId, boolean apiKeyEnabled, String apiKeyHeader,
                                             Object sourceArtifact) {
        JsonObject reference = new JsonObject();
        reference.addProperty(KongConstants.KONG_REFERENCE_ID, apiId);
        reference.addProperty(KongConstants.KONG_REFERENCE_API_KEY_ENABLED, apiKeyEnabled);
        if (apiKeyHeader != null && !apiKeyHeader.trim().isEmpty()) {
            reference.addProperty(KongConstants.KONG_REFERENCE_API_KEY_HEADER, apiKeyHeader.trim());
        }
        if (sourceArtifact != null) {
            JsonElement source = gson.toJsonTree(sourceArtifact);
            reference.add("source", source);
        }
        return gson.toJson(reference);
    }

    private List<KongPlugin> collectPluginsForServiceAndRoutes(String serviceId, List<KongRoute> routes)
            throws KongGatewayException {
        List<KongPlugin> collected = new ArrayList<>(listServicePlugins(serviceId));
        if (routes == null || routes.isEmpty()) {
            return collected;
        }
        for (KongRoute route : routes) {
            if (route == null || route.getId() == null) {
                continue;
            }
            collected.addAll(listRoutePlugins(route.getId()));
        }
        return collected;
    }

    private List<KongPlugin> listServicePlugins(String serviceId) throws KongGatewayException {
        PagedResponse<KongPlugin> pluginsResp = apiGatewayClient.listPluginsByServiceId(controlPlaneId,
                serviceId, KongConstants.DEFAULT_PLUGIN_LIST_LIMIT);
        if (pluginsResp == null || pluginsResp.getData() == null) {
            return Collections.emptyList();
        }
        return pluginsResp.getData();
    }

    private List<KongPlugin> listRoutePlugins(String routeId) throws KongGatewayException {
        PagedResponse<KongPlugin> pluginsResp = apiGatewayClient.listPluginsByRouteId(controlPlaneId,
                routeId, KongConstants.DEFAULT_PLUGIN_LIST_LIMIT);
        if (pluginsResp == null || pluginsResp.getData() == null) {
            return Collections.emptyList();
        }
        return pluginsResp.getData();
    }
}
