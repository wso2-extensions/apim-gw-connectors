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
    private KongAPI api;               // API metadata (may be null for "raw services")
    private KongService service;       // Backend service (required)
    private List<KongRoute> routes;    // Routes/paths (required)
    private List<KongPlugin> plugins;  // Plugins/policies (optional)
    private String vhost;              // Virtual host for URL construction

    // Constructors
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
    
    // Getters and Setters
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