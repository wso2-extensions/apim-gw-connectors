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

package org.wso2.kong.client.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import org.wso2.carbon.apimgt.api.model.CORSConfiguration;
import org.wso2.kong.client.model.KongPlugin;
import org.wso2.kong.client.model.KongRoute;
import org.wso2.kong.client.model.KongService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * Utility class for building OpenAPI Specification (OAS) from Kong routes and services.
 */
public class KongAPIUtil {
    public static final List<String> DEFAULT_ALLOW_HEADERS =
            Arrays.asList("authorization", "Access-Control-Allow-Origin", "Content-Type", "SOAPAction", "apikey",
                    "Internal-Key");

    public static final Set<String> WSO2_ALLOWED_METHODS =
            new LinkedHashSet<>(Arrays.asList("GET", "PUT", "POST", "DELETE", "PATCH", "OPTIONS"));

    /**
     * Transform a Kong CORS plugin to WSO2 CORSConfiguration.
     */
    public static CORSConfiguration kongCorsToWso2Cors(KongPlugin plugin) {
        boolean enabled = plugin.getEnabled() != null ? plugin.getEnabled() : false;
        JsonObject cfg = plugin.getConfig(); // may be null if malformed

        // Origins
        List<String> origins = getStringList(cfg, "origins");
        if (origins.isEmpty()) {
            // If Kong has no origins configured, WSO2 defaults to * (or set to your org default)
            origins = Collections.singletonList("*");
        } else if (origins.contains("*")) {
            // Normalize: if "*" is present, it implies all; keep only "*"
            origins = Collections.singletonList("*");
        }

        // Credentials
        boolean allowCreds = getBoolean(cfg, "credentials", false);

        // Headers (fallback defaults if empty)
        List<String> headers = getStringList(cfg, "headers");
        if (headers.isEmpty()) {
            headers = DEFAULT_ALLOW_HEADERS;
        }

        // Methods (filter to what WSO2 expects)
        List<String> methodsRaw = getStringList(cfg, "methods");
        List<String> methods = new ArrayList<String>();
        if (methodsRaw.isEmpty()) {
            methods.addAll(WSO2_ALLOWED_METHODS); // fallback
        } else {
            for (String m : methodsRaw) {
                String up = m.toUpperCase(Locale.ROOT);
                if (WSO2_ALLOWED_METHODS.contains(up)) {
                    methods.add(up);
                }
            }
            if (methods.isEmpty()) { // if everything got filtered out, use sane defaults
                methods.addAll(WSO2_ALLOWED_METHODS);
            }
        }

        CORSConfiguration cors = new CORSConfiguration(enabled, origins, allowCreds, headers, methods);
        return cors;
    }

    public static String determineAPIType(KongService service) {
    // 1. Check Service Protocol (The most common indicator)
        if (service != null && service.getProtocol() != null) {
            String proto = service.getProtocol().toLowerCase();
            if ("ws".equals(proto) || "wss".equals(proto)) {
                return "WS"; 
            } else if ("http".equals(proto) || "https".equals(proto)) {
                return "HTTP"; 
            }
        }
        return null;
    }

    public static boolean getBoolean(JsonObject obj, String key, boolean def) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return def;
        }
        JsonElement el = obj.get(key);
        return el.isJsonPrimitive() && el.getAsJsonPrimitive().isBoolean() ? el.getAsBoolean() : def;
    }

    public static List<String> getStringList(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return Collections.emptyList();
        }
        JsonElement el = obj.get(key);
        List<String> out = new ArrayList<String>();
        if (el.isJsonArray()) {
            JsonArray arr = el.getAsJsonArray();
            for (JsonElement e : arr) {
                if (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
                    out.add(e.getAsString());
                }
            }
        } else if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
            out.add(el.getAsString());
        }
        return out;
    }

    /**
     * Transform a Kong Advanced Rate Limiting plugin to WSO2 Rate Limiting Policy.
     */
    public static String kongRateLimitingToWso2Policy(KongPlugin plugin) {
        if (plugin == null || plugin.getConfig() == null) {
            return null;
        }
        if (plugin.getEnabled() != null && !plugin.getEnabled()) {
            return null;
        }

        JsonObject cfg = plugin.getConfig();
        List<Integer> limits = getIntList(cfg, "limit");
        List<Integer> windows = getIntList(cfg, "window_size");
        if (limits.isEmpty() || windows.isEmpty()) {
            return null;
        }

        // Build index → value map for quick lookup
        int n = Math.min(limits.size(), windows.size());
        // Preference order for APIM-friendly windows
        int[] preferred = new int[] {60, 3600, 86400, 604800, 2592000};

        // Try preferred windows first
        for (int pw : preferred) {
            for (int i = 0; i < n; i++) {
                Integer w = windows.get(i);
                if (w != null && w == pw) {
                    Integer limit = limits.get(i) != null ? limits.get(i) : 0;
                    String suffix = windowSuffix(pw); // never null for preferred
                    return limit + suffix;
                }
            }
        }

        // No preferred window found; pick the smallest positive window and build a generic suffix
        int bestIdx = -1;
        int bestWindow = Integer.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            Integer w = windows.get(i);
            if (w != null && w > 0 && w < bestWindow) {
                bestWindow = w;
                bestIdx = i;
            }
        }
        if (bestIdx >= 0) {
            int limit = limits.get(bestIdx) != null ? limits.get(bestIdx) : 0;
            return limit + "Per" + bestWindow + "Sec";
        }

        return null;
    }

    /**
     * Map known window sizes (seconds) to APIM suffix.
     */
    private static String windowSuffix(int seconds) {
        switch (seconds) {
            case 60:
                return "PerMin";
            case 3600:
                return "PerHour";
            case 86400:
                return "PerDay";
            case 604800:
                return "PerWeek";
            case 2592000:
                return "PerMonth"; // ~30 days
            default:
                return null;
        }
    }

    /**
     * Extract a list of integers from a JSON field that may be a single number or an array.
     */
    private static List<Integer> getIntList(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return Collections.emptyList();
        }
        JsonElement el = obj.get(key);
        List<Integer> out = new ArrayList<Integer>();
        if (el.isJsonArray()) {
            JsonArray arr = el.getAsJsonArray();
            for (JsonElement e : arr) {
                Integer v = asInt(e);
                if (v != null) {
                    out.add(v);
                }
            }
        } else {
            Integer v = asInt(el);
            if (v != null) {
                out.add(v);
            }
        }
        return out;
    }

    private static Integer asInt(JsonElement el) {
        if (el == null || !el.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive p = el.getAsJsonPrimitive();
        if (p.isNumber()) {
            try {
                return p.getAsInt();
            } catch (NumberFormatException ex) {
                // fall through
            }
        }
        return null;
    }

    /**
     * Transform a standard Kong "rate-limiting" plugin to a WSO2 APIM API-level policy string.
     * Example:
     * config.minute = 4  -> "4PerMin"
     * <p>
     * Preference order if multiple are set: minute -> hour -> day -> month -> year -> second.
     * Returns null if plugin is disabled or no positive limits are present.
     */
    public static String kongRateLimitingStandardToWso2Policy(KongPlugin plugin) {
        if (plugin == null || plugin.getConfig() == null) {
            return null;
        }
        if (plugin.getEnabled() != null && !plugin.getEnabled()) {
            return null;
        }

        JsonObject cfg = plugin.getConfig();

        Integer minute = getInt(cfg, "minute");
        if (isPositive(minute)) {
            return minute + "PerMin";
        }

        Integer hour = getInt(cfg, "hour");
        if (isPositive(hour)) {
            return hour + "PerHour";
        }

        Integer day = getInt(cfg, "day");
        if (isPositive(day)) {
            return day + "PerDay";
        }

        Integer month = getInt(cfg, "month");
        if (isPositive(month)) {
            return month + "PerMonth";
        }

        Integer year = getInt(cfg, "year");
        if (isPositive(year)) {
            return year + "PerYear";
        }

        Integer second = getInt(cfg, "second");
        if (isPositive(second)) {
            return second + "PerSec";
        }

        return null;
    }

    // ---- helpers ----
    private static Integer getInt(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        JsonElement el = obj.get(key);
        if (el.isJsonPrimitive()) {
            JsonPrimitive p = el.getAsJsonPrimitive();
            if (p.isNumber()) {
                try {
                    return p.getAsInt();
                } catch (NumberFormatException ignore) { /* fall through */ }
            }
        }
        return null;
    }

    private static boolean isPositive(Integer v) {
        return v != null && v > 0;
    }

    public static String buildOasFromRoutes(KongService svc, List<KongRoute> routes, String vhost) {
        JsonObject root = new JsonObject();
        root.addProperty("openapi", "3.0.3");

        // info
        JsonObject info = new JsonObject();
        info.addProperty("title", svc.getName() != null ? svc.getName() : "kong-service");
        info.addProperty("version", "v1");
        root.add("info", info);

        // servers (public base URL on the gateway/vhost)
        JsonArray servers = new JsonArray();
        JsonObject server0 = new JsonObject();
        server0.addProperty("url", "https://" + vhost);
        servers.add(server0);
        root.add("servers", servers);

        // paths
        JsonObject paths = new JsonObject();

        for (KongRoute r : routes) {
            List<String> routePaths = (r.getPaths() != null) ? r.getPaths() : Collections.emptyList();
            List<String> methods = (r.getMethods() != null && !r.getMethods().isEmpty()) ? r.getMethods() :
                    Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");

            for (String kongPath : routePaths) {
                String oasPath = toOasPath(kongPath); // normalize regex → template

                // Ensure we have a path object
                JsonObject pathItem = paths.has(oasPath) ? paths.getAsJsonObject(oasPath) : new JsonObject();

                // For each HTTP method, create a minimal operation
                for (String m : methods) {
                    String http = m.toLowerCase(Locale.ROOT);
                    // Avoid duplicates (if multiple routes map to same path+method, last one wins)
                    JsonObject op = new JsonObject();
                    op.addProperty("operationId", safeOpId(r.getName(), http, oasPath));
                    op.addProperty("summary", r.getName() != null ? r.getName() : "Kong route");

                    // Path params (derive from {param} in the normalized path)
                    JsonArray parameters = buildPathParameters(oasPath);
                    if (parameters.size() > 0) {
                        op.add("parameters", parameters);
                    }

                    // Minimal 200 response
                    JsonObject responses = new JsonObject();
                    JsonObject ok = new JsonObject();
                    ok.addProperty("description", "OK");
                    responses.add("200", ok);
                    op.add("responses", responses);

                    pathItem.add(http, op);
                }

                paths.add(oasPath, pathItem);
            }
        }

        root.add("paths", paths);
        // components left empty for now
        root.add("components", new JsonObject());

        return root.toString();
    }

    public static String buildOasFromRoutesForAsync(KongService svc, List<KongRoute> routes, String vhost) {
        JsonObject root = new JsonObject();
        root.addProperty("asyncapi", "2.0.0");

        JsonObject info = new JsonObject();
        info.addProperty("title", svc.getName() != null ? svc.getName() : "kong-service");
        info.addProperty("version", "v1");
        root.add("info", info);

        JsonObject servers = new JsonObject();
        JsonObject production = new JsonObject();
        production.addProperty("url", "wss://" + vhost);
        production.addProperty("protocol", "wss");
        servers.add("production", production);
        root.add("servers", servers);

        JsonObject channels = new JsonObject();
        for (KongRoute r : routes) {
            List<String> routePaths = (r.getPaths() != null && !r.getPaths().isEmpty()) ?
                    r.getPaths() : Collections.singletonList("/*");

            for (String kongPath : routePaths) {
                String oasPath = toOasPath(kongPath);
                if (channels.has(oasPath)) {
                    continue;
                }
                JsonObject channelItem = new JsonObject();
                JsonObject pub = new JsonObject();
                pub.addProperty("operationId", safeOpId(r.getName(), "pub", oasPath));
                channelItem.add("publish", pub);
                JsonObject sub = new JsonObject();
                sub.addProperty("operationId", safeOpId(r.getName(), "sub", oasPath));
                channelItem.add("subscribe", sub);

                // Parameters
                Matcher m = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_-]*)\\}").matcher(oasPath);
                JsonObject parameters = new JsonObject();
                while (m.find()) {
                    String pName = m.group(1);
                    JsonObject pObj = new JsonObject();
                    JsonObject schema = new JsonObject();
                    schema.addProperty("type", "string");
                    pObj.add("schema", schema);
                    parameters.add(pName, pObj);
                }
                if (parameters.size() > 0) {
                    channelItem.add("parameters", parameters);
                }
                channels.add(oasPath, channelItem);
            }
        }

        if (channels.entrySet().isEmpty()) {
            JsonObject channelItem = new JsonObject();
            JsonObject pub = new JsonObject();
            pub.addProperty("operationId", "pub_default");
            channelItem.add("publish", pub);
            JsonObject sub = new JsonObject();
            sub.addProperty("operationId", "sub_default");
            channelItem.add("subscribe", sub);
            channels.add("/*", channelItem);
        }

        root.add("channels", channels);
        root.add("components", new JsonObject());
        return root.toString();
    }

    /**
     * Convert Kong route path value to an OAS path template. Handles:
     * - plain prefixes like "/get" (returned as-is)
     * - regex form starting with "~" and ending with "$"
     * - named groups like (?<value>[^#?/]+)  →  {value}
     */
    public static String toOasPath(String kongPath) {
        if (kongPath == null || kongPath.isEmpty()) {
            return "/";
        }

        String p = kongPath.trim();

        // Regex-style route (starts with "~")
        if (p.startsWith("~")) {
            // strip leading "~" and trailing "$"
            if (p.endsWith("$")) {
                p = p.substring(1, p.length() - 1);
            } else {
                p = p.substring(1);
            }

            // Remove a leading ^ if present
            if (p.startsWith("^")) {
                p = p.substring(1);
            }

            // Replace named capture groups with {name}
            // (?<name>pattern)  →  {name}
            Pattern named = Pattern.compile("\\(\\?<([A-Za-z_][A-Za-z0-9_-]*)>[^)]+\\)");
            Matcher m = named.matcher(p);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                m.appendReplacement(sb, "{" + m.group(1) + "}");
            }
            m.appendTail(sb);
            p = sb.toString();

            // Remove remaining regex tokens that aren’t valid in OAS paths
            // (non-named groups, anchors)
            p = p.replaceAll("\\((?:\\?:)?[^)]*\\)", ""); // drop other groups
        }

        // Ensure it starts with "/"
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        // Collapse any double slashes
        p = p.replaceAll("/{2,}", "/");

        return p;
    }

    /**
     * Build path parameters from a Kong route path.
     * Extracts {param} style placeholders and returns them as OAS path parameters.
     */
    public static JsonArray buildPathParameters(String oasPath) {
        JsonArray params = new JsonArray();
        Matcher m = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_-]*)\\}").matcher(oasPath);
        while (m.find()) {
            String name = m.group(1);
            JsonObject p = new JsonObject();
            p.addProperty("name", name);
            p.addProperty("in", "path");
            p.addProperty("required", true);

            JsonObject schema = new JsonObject();
            schema.addProperty("type", "string");
            p.add("schema", schema);

            params.add(p);
        }
        return params;
    }

    public static String safeOpId(String routeName, String method, String path) {
        String base = (routeName != null && !routeName.isEmpty()) ? routeName : "op";
        // sanitize path to opId-friendly
        String p = path.replaceAll("[^A-Za-z0-9]+", "_");
        return (base + "_" + method + "_" + p).replaceAll("_+", "_");
    }

    /**
     * Build endpointConfig JSON for APIM/Kong using Gson.
     * Both production and sandbox endpoints are included.
     */
    public static String buildEndpointConfigJson(String productionUrl, String sandboxUrl, boolean failOver) {
        JsonObject endpointConfig = new JsonObject();
        endpointConfig.addProperty("endpoint_type", "http");
        endpointConfig.addProperty("failOver", failOver);

        JsonObject prod = new JsonObject();
        prod.addProperty("template_not_supported", false);
        prod.addProperty("url", productionUrl);

        JsonObject sand = new JsonObject();
        sand.addProperty("template_not_supported", false);
        sand.addProperty("url", sandboxUrl);

        endpointConfig.add("production_endpoints", prod);
        endpointConfig.add("sandbox_endpoints", sand);

        return endpointConfig.toString();
    }

    public static String buildEndpointUrl(String protocol, String host, int port, String path) {
        StringBuilder sb = new StringBuilder();
        sb.append(protocol).append("://").append(host);
        if (port > 0) {
            sb.append(":").append(port);
        }
        if (path != null && !path.isEmpty()) {
            sb.append(path);
        }
        if (sb.charAt(sb.length() - 1) != '/') {
            sb.append('/');
        }
        return sb.toString();
    }

    /**
     * Ensures the given string starts with a leading slash.
     * If the string is null or empty, returns "/".
     *
     * @param v The input string
     * @return The input string with a leading slash, or "/" if null/empty
     */
    public static String ensureLeadingSlash(String v) {
        if (v == null || v.isEmpty()) {
            return "/";
        }
        return v.startsWith("/") ? v : "/" + v;
    }
}
