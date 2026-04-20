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

package org.wso2.kong.client.builder;

import java.util.List;

import org.wso2.kong.client.model.KongAPI;
import org.wso2.kong.client.model.KongPlugin;
import org.wso2.kong.client.model.KongRoute;
import org.wso2.kong.client.model.KongService;

/**
 * Data Transfer Object that combines Kong API metadata with its associated service and routes.
 * This allows builders to access all necessary Kong data in one object.
 * 
 * Kong Gateway structure:
 * - KongAPI: High-level API metadata (name, version, description, spec)
 * - KongService: Backend service configuration (host, port, protocol, path)
 * - KongRoutes: Path/routing configuration
 * - KongPlugins: Policies and configurations
 */
public class KongApiBundle {
    private KongAPI api;
    private KongService service;
    private List<KongRoute> routes;
    private List<KongPlugin> plugins;
    private String vhost;

    public KongApiBundle() {
    }
    
    public KongApiBundle(KongAPI api, KongService service, List<KongRoute> routes, 
                         List<KongPlugin> plugins, String vhost) {
        this.api = api;
        this.service = service;
        this.routes = routes;
        this.plugins = plugins;
        this.vhost = vhost;
    }
    
    public KongAPI getApi() {
        return api;
    }
    
    public void setApi(KongAPI api) {
        this.api = api;
    }
    
    public KongService getService() {
        return service;
    }
    
    public void setService(KongService service) {
        this.service = service;
    }
    
    public List<KongRoute> getRoutes() {
        return routes;
    }
    
    public void setRoutes(List<KongRoute> routes) {
        this.routes = routes;
    }
    
    public List<KongPlugin> getPlugins() {
        return plugins;
    }
    
    public void setPlugins(List<KongPlugin> plugins) {
        this.plugins = plugins;
    }
    
    public String getVhost() {
        return vhost;
    }
    
    public void setVhost(String vhost) {
        this.vhost = vhost;
    }
    
    /**
     * Checks if this DTO has API metadata (vs being a "raw service").
     */
    public boolean hasApiMetadata() {
        return api != null;
    }

}
