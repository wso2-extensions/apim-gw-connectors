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
        log.debug("[LOGGING] Kong Connector: discoverAPI() called. Fetching all Kong APIs with full spec.");
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

                List<DiscoveredAPI> retrievedAPIs = java.util.Collections.synchronizedList(new ArrayList<>());
                Set<String> linkedServices = java.util.Collections.synchronizedSet(new HashSet<>());

                // Iterate all APIs in parallel, fetching full OAS spec
                apis.parallelStream().forEach(kongAPI -> {
                    DiscoveredAPI dApi = processKongAPI(kongAPI, apiToSvc, linkedServices, true);
                    if (dApi != null) {
                        retrievedAPIs.add(dApi);
                    }
                });

                // Process remaining services (not linked to any API Product) in parallel
                PagedResponse<KongService> servicesResp = apiGatewayClient.listServices(controlPlaneId,
                        KongConstants.DEFAULT_SERVICE_LIST_LIMIT);
                List<KongService> services = (servicesResp != null && servicesResp.getData() != null)
                        ? servicesResp.getData() : Collections.emptyList();

                services.parallelStream().forEach(svc -> {
                    if (!linkedServices.contains(svc.getId())) {
                        DiscoveredAPI dApi = processKongService(svc);
                        if (dApi != null) {
                            retrievedAPIs.add(dApi);
                        }
                    }
                });

                return new ArrayList<>(retrievedAPIs);
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
    public List<DiscoveredAPI> discoverMetadata() {
        log.debug("[LOGGING] Kong Connector: discoverMetadata() called. Fetching metadata for Kong APIs.");
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

                List<DiscoveredAPI> retrievedAPIs = java.util.Collections.synchronizedList(new ArrayList<>());
                Set<String> linkedServices = java.util.Collections.synchronizedSet(new HashSet<>());

                // Iterate APIs in parallel (without fetching specs for metadata)
                apis.parallelStream().forEach(kongAPI -> {
                    DiscoveredAPI dApi = processKongAPI(kongAPI, apiToSvc, linkedServices, false);
                    if (dApi != null) {
                        retrievedAPIs.add(dApi);
                    }
                });

                // Process remaining services in parallel
                PagedResponse<KongService> servicesResp = apiGatewayClient.listServices(controlPlaneId,
                        KongConstants.DEFAULT_SERVICE_LIST_LIMIT);
                List<KongService> services = (servicesResp != null && servicesResp.getData() != null)
                        ? servicesResp.getData() : Collections.emptyList();

                services.parallelStream().forEach(svc -> {
                    if (!linkedServices.contains(svc.getId())) {
                        DiscoveredAPI dApi = processKongService(svc);
                        if (dApi != null) {
                            retrievedAPIs.add(dApi);
                        }
                    }
                });

                return new ArrayList<>(retrievedAPIs);
            } catch (Exception ex) {
                log.error("Unexpected error during Kong Konnect metadata discovery: " + ex.getMessage(), ex);
                return Collections.emptyList();
            }
        } else {
            return Collections.emptyList();
        }
    }

    @Override
    public List<DiscoveredAPI> discoverAPI(List<String> apiIds) {
        log.debug("[LOGGING] Kong Connector: discoverAPI(List<String> apiIds) called with IDs: " + apiIds);
        if (apiIds == null) {
            return Collections.emptyList();
        }
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

                List<DiscoveredAPI> retrievedAPIs = java.util.Collections.synchronizedList(new ArrayList<>());
                Set<String> linkedServices = java.util.Collections.synchronizedSet(new HashSet<>());

                // Pre-populate linkedServices to prevent service double-discovery
                for (KongAPIImplementation.ServiceLink link : apiToSvc.values()) {
                    if (link.getId() != null) {
                        linkedServices.add(link.getId());
                    }
                }

                // Iterate APIs in parallel, only fetching spec for matched API IDs
                apis.parallelStream().forEach(kongAPI -> {
                    String compositeKey = kongAPI.getName() + ":" + kongAPI.getVersion();
                    if (apiIds.contains(kongAPI.getId()) || apiIds.contains(compositeKey)) {
                        log.debug("[LOGGING] Kong Connector: MATCH FOUND. Fetching spec for Kong API ID: "
                                + kongAPI.getId());
                        DiscoveredAPI dApi = processKongAPI(kongAPI, apiToSvc, linkedServices, true);
                        if (dApi != null) {
                            retrievedAPIs.add(dApi);
                        }
                    }
                });

                // Process remaining services in parallel
                PagedResponse<KongService> servicesResp = apiGatewayClient.listServices(controlPlaneId,
                        KongConstants.DEFAULT_SERVICE_LIST_LIMIT);
                List<KongService> services = (servicesResp != null && servicesResp.getData() != null)
                        ? servicesResp.getData() : Collections.emptyList();

                services.parallelStream().forEach(svc -> {
                    if (!linkedServices.contains(svc.getId())) {
                        String compositeKey = svc.getName() + ":" + KongConstants.DEFAULT_API_VERSION;
                        if (apiIds.contains(svc.getId()) || apiIds.contains(compositeKey)) {
                            DiscoveredAPI dApi = processKongService(svc);
                            if (dApi != null) {
                                retrievedAPIs.add(dApi);
                            }
                        }
                    }
                });

                return new ArrayList<>(retrievedAPIs);
            } catch (Exception ex) {
                log.error("Unexpected error during Kong Konnect discovery: " + ex.getMessage(), ex);
                return Collections.emptyList();
            }
        } else {
            return Collections.emptyList();
        }
    }

    private DiscoveredAPI processKongAPI(KongAPI kongAPI, Map<String, KongAPIImplementation.ServiceLink> apiToSvc,
                                         Set<String> linkedServices, boolean fetchSpec) {
        try {
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

            if (kongAPI.getUpdatedAt() != null) {
                try {
                    api.setLastUpdated(Date.from(java.time.Instant.parse(kongAPI.getUpdatedAt())));
                } catch (Exception e) {
                    log.warn("Error parsing updatedAt timestamp: " + kongAPI.getUpdatedAt());
                }
            }

            // Fetch and set OAS definition
            String oas = null;
            if (fetchSpec) {
                if (kongAPI.getApiSpecIds() != null && !kongAPI.getApiSpecIds().isEmpty()) {
                    String specId = kongAPI.getApiSpecIds().get(0);
                    try {
                        KongAPISpec spec = apiGatewayClient.getAPISpec(apiId, specId);
                        if (spec != null && spec.getContent() != null) {
                            oas = spec.getContent(); // raw OAS (JSON/YAML string)
                        }
                    } catch (Exception e) {
                        log.warn("Error fetching API spec for API ID: " + apiId, e);
                    }
                }
                if (oas == null || oas.trim().isEmpty()) {
                    com.google.gson.JsonObject oasObj = new com.google.gson.JsonObject();
                    oasObj.addProperty("openapi", "3.0.1");

                    com.google.gson.JsonObject infoObj = new com.google.gson.JsonObject();
                    infoObj.addProperty("title", apiName);
                    infoObj.addProperty("version", apiVersion);
                    oasObj.add("info", infoObj);

                    com.google.gson.JsonObject pathsObj = new com.google.gson.JsonObject();
                    com.google.gson.JsonObject slashObj = new com.google.gson.JsonObject();
                    com.google.gson.JsonObject getObj = new com.google.gson.JsonObject();
                    com.google.gson.JsonObject responsesObj = new com.google.gson.JsonObject();
                    com.google.gson.JsonObject r200Obj = new com.google.gson.JsonObject();
                    r200Obj.addProperty("description", "Default Response");
                    responsesObj.add("200", r200Obj);
                    getObj.add("responses", responsesObj);
                    slashObj.add("get", getObj);
                    pathsObj.add("/*", slashObj);
                    oasObj.add("paths", pathsObj);

                    oas = new com.google.gson.Gson().toJson(oasObj);
                }
            } else {
                oas = "";
            }
            api.setSwaggerDefinition(oas);

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

            api.setAvailableTiers(new HashSet<>(Collections.singleton(
                    new Tier(KongConstants.DEFAULT_TIER))));

            String selectedAPILevelRateLimitPolicy = null;

            if (svc == null) {
                log.warn("No service found for API: " + apiName + " (ID: " + apiId + ")");
                return null;
            }

            // add to linked services to avoid duplicates
            linkedServices.add(svc.getId());

            // Fetch plugin related to services
            PagedResponse<KongPlugin> pluginsResp = apiGatewayClient.listPluginsByServiceId(controlPlaneId,
                    svc.getId(), KongConstants.DEFAULT_PLUGIN_LIST_LIMIT);
            List<KongPlugin> plugins = (pluginsResp != null && pluginsResp.getData() != null)
                    ? pluginsResp.getData() : Collections.<KongPlugin>emptyList();

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
            if (selectedAPILevelRateLimitPolicy != null) {
                api.setApiLevelPolicy(selectedAPILevelRateLimitPolicy);
            }

            // Fetch routes to include in the composite hash
            PagedResponse<KongRoute> routesResp = apiGatewayClient.listRoutesByServiceId(controlPlaneId,
                    svc.getId(), KongConstants.DEFAULT_ROUTE_LIST_LIMIT);
            List<KongRoute> routes = (routesResp != null && routesResp.getData() != null)
                    ? routesResp.getData() : Collections.emptyList();

            // Calculate composite configuration hash
            StringBuilder hashInput = new StringBuilder();
            String apiUpdated = kongAPI.getUpdatedAt() != null ? kongAPI.getUpdatedAt() : "0";
            hashInput.append("api:").append(kongAPI.getId()).append(":").append(apiUpdated).append("|");
            String svcUpdated = svc.getUpdatedAt() != null ? String.valueOf(svc.getUpdatedAt()) : "0";
            hashInput.append("svc:").append(svc.getId()).append(":").append(svcUpdated).append("|");

            routes.stream().filter(r -> r != null && r.getId() != null)
                    .sorted(java.util.Comparator.comparing(KongRoute::getId)).forEach(r -> {
                String rtUpdated = r.getUpdatedAt() != null ? String.valueOf(r.getUpdatedAt()) : "0";
                hashInput.append("rt:").append(r.getId()).append(":").append(rtUpdated).append("|");
            });

            plugins.stream().filter(p -> p != null && p.getId() != null)
                    .sorted(java.util.Comparator.comparing(KongPlugin::getId)).forEach(p -> {
                String plUpdated = p.getUpdatedAt() != null ? String.valueOf(p.getUpdatedAt()) : "0";
                hashInput.append("pl:").append(p.getId()).append(":").append(plUpdated).append("|");
            });

            String compositeHash = KongAPIUtil.sha256Hex(hashInput.toString());
            return new DiscoveredAPI(api, KongAPIUtil.createReferenceArtifact(svc.getId(), compositeHash));
        } catch (Exception e) {
            log.error("Error processing API: " + kongAPI.getName(), e);
            return null;
        }
    }

    private DiscoveredAPI processKongService(KongService svc) {
        String svcName = svc.getName() != null ? svc.getName() : svc.getId();
        try {
            PagedResponse<KongRoute> resp = apiGatewayClient.listRoutesByServiceId(
                    controlPlaneId, svc.getId(), KongConstants.DEFAULT_ROUTE_LIST_LIMIT);
            List<KongRoute> routes = (resp != null && resp.getData() != null) ?
                    resp.getData() : java.util.Collections.emptyList();

            PagedResponse<KongPlugin> pluginsResp = apiGatewayClient.listPluginsByServiceId(controlPlaneId,
                    svc.getId(), KongConstants.DEFAULT_PLUGIN_LIST_LIMIT);
            List<KongPlugin> plugins = (pluginsResp != null && pluginsResp.getData() != null)
                    ? pluginsResp.getData() : java.util.Collections.<KongPlugin>emptyList();

            APIIdentifier apiId = new APIIdentifier(KongConstants.DEFAULT_API_PROVIDER, svcName,
                    KongConstants.DEFAULT_API_VERSION);
            API api = new API(apiId);
            api.setDisplayName(svcName);
            api.setContext(svcName);
            api.setContextTemplate(svcName.toLowerCase().replace(" ", "-"));
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
            if (selectedAPILevelRateLimitPolicy != null) {
                api.setApiLevelPolicy(selectedAPILevelRateLimitPolicy);
            }

            // Calculate composite configuration hash
            StringBuilder hashInput = new StringBuilder();
            String svcUpdated = svc.getUpdatedAt() != null ? String.valueOf(svc.getUpdatedAt()) : "0";
            hashInput.append("svc:").append(svc.getId()).append(":").append(svcUpdated).append("|");

            routes.stream().filter(r -> r != null && r.getId() != null)
                    .sorted(java.util.Comparator.comparing(KongRoute::getId)).forEach(r -> {
                String rtUpdated = r.getUpdatedAt() != null ? String.valueOf(r.getUpdatedAt()) : "0";
                hashInput.append("rt:").append(r.getId()).append(":").append(rtUpdated).append("|");
            });

            plugins.stream().filter(p -> p != null && p.getId() != null)
                    .sorted(java.util.Comparator.comparing(KongPlugin::getId)).forEach(p -> {
                String plUpdated = p.getUpdatedAt() != null ? String.valueOf(p.getUpdatedAt()) : "0";
                hashInput.append("pl:").append(p.getId()).append(":").append(plUpdated).append("|");
            });

            String compositeHash = KongAPIUtil.sha256Hex(hashInput.toString());
            return new DiscoveredAPI(api, KongAPIUtil.createReferenceArtifact(svc.getId(), compositeHash));
        } catch (Exception e) {
            log.error("Error processing service: " + svcName, e);
            return null;
        }
    }

    @Override
    public boolean isAPIUpdated(String existingReferenceArtifact, String newReferenceArtifact) {
        if (existingReferenceArtifact == null || newReferenceArtifact == null) {
            return true;
        }
        try {
            com.google.gson.JsonObject existingJson = com.google.gson.JsonParser
                    .parseString(existingReferenceArtifact).getAsJsonObject();
            com.google.gson.JsonObject newJson = com.google.gson.JsonParser
                    .parseString(newReferenceArtifact).getAsJsonObject();

            if (existingJson.has("configHash") && !existingJson.get("configHash").isJsonNull() &&
                    newJson.has("configHash") && !newJson.get("configHash").isJsonNull()) {
                String existingHash = existingJson.get("configHash").getAsString();
                String newHash = newJson.get("configHash").getAsString();
                return !existingHash.equals(newHash);
            }
        } catch (Exception e) {
            log.error("Error parsing Kong reference artifact", e);
        }
        return !java.util.Objects.equals(existingReferenceArtifact, newReferenceArtifact);
    }
}
