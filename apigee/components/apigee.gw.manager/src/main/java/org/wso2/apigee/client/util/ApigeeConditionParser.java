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

package org.wso2.apigee.client.util;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ApigeeConditionParser {

    private static final Log log = LogFactory.getLog(ApigeeConditionParser.class);

    // -----------------------------------------------------------------------
    //  Compiled Patterns for Apigee Expression Language
    // -----------------------------------------------------------------------

    /**
     * Matches: proxy.pathsuffix MatchesPath "/path/here" or 'path/here'
     * Captures group 1: the path string (e.g., "/todos", "/users/*")
     * 
     * Note: Apigee allows both double and single quotes in conditions
     */
    private static final Pattern MATCHES_PATH_PATTERN =
            Pattern.compile("MatchesPath\\s+[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    /**
     * Matches: request.verb = "GET" or 'GET', request.verb equals "POST", request.verb == "DELETE"
     * Captures group 1: the HTTP verb
     * 
     * Note: Apigee allows both double and single quotes, and multiple comparison operators
     */
    private static final Pattern REQUEST_VERB_PATTERN =
            Pattern.compile("request\\.verb\\s*(?:=|equals|==)\\s+[\"']([^\"']+)[\"']",
                    Pattern.CASE_INSENSITIVE);

    /**
     * Matches: proxy.pathsuffix ~ "/pattern.*"  (regex match)
     * Captures group 1: the regex pattern
     */
    private static final Pattern PATH_REGEX_PATTERN =
            Pattern.compile("proxy\\.pathsuffix\\s*~\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    /**
     * Matches: request.header.X-Custom-Header = "value"
     * Captures group 1: header name, group 2: expected value
     */
    private static final Pattern HEADER_CONDITION_PATTERN =
            Pattern.compile("request\\.header\\.([\\w-]+)\\s*(?:=|equals|==)\\s*\"([^\"]+)\"",
                    Pattern.CASE_INSENSITIVE);

    /**
     * Matches: request.queryparam.name = "value"
     * Captures group 1: param name, group 2: expected value
     */
    private static final Pattern QUERY_PARAM_CONDITION_PATTERN =
            Pattern.compile("request\\.queryparam\\.([\\w-]+)\\s*(?:=|equals|==)\\s*\"([^\"]+)\"",
                    Pattern.CASE_INSENSITIVE);

    // -----------------------------------------------------------------------
    //  Result Container
    // -----------------------------------------------------------------------

    /**
     * Holds parsed results from an Apigee condition expression.
     */
    public static class ParsedCondition {
        private String path;
        private String httpVerb;
        private String pathRegex;
        private boolean valid;

        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        
        public String getHttpVerb() { return httpVerb; }
        public void setHttpVerb(String httpVerb) { this.httpVerb = httpVerb; }
        
        public String getPathRegex() { return pathRegex; }
        public void setPathRegex(String pathRegex) { this.pathRegex = pathRegex; }
        
        public boolean isValid() { return valid; }
        public void setValid(boolean valid) { this.valid = valid; }

        @Override
        public String toString() {
            return "ParsedCondition{path='" + path + "', verb='" + httpVerb + 
                   "', regex='" + pathRegex + "', valid=" + valid + "}";
        }
    }

    // -----------------------------------------------------------------------
    //  Public API
    // -----------------------------------------------------------------------

    /**
     * Parses an Apigee condition expression and extracts path and HTTP verb.
     * 
     * @param condition the condition string from {@code <Condition>} element
     * @return parsed result containing path and verb (may have null fields if not found)
     */
    public static ParsedCondition parse(String condition) {
        ParsedCondition result = new ParsedCondition();
        
        if (condition == null || condition.trim().isEmpty()) {
            result.setValid(false);
            return result;
        }
        
        String trimmed = condition.trim();
        
        // Extract path (MatchesPath takes precedence over regex match)
        result.setPath(extractMatchesPath(trimmed));
        if (result.getPath() == null) {
            // Try regex pattern match
            result.setPathRegex(extractPathRegex(trimmed));
        }
        
        // Extract HTTP verb
        result.setHttpVerb(extractRequestVerb(trimmed));
        
        // Valid if we have at least a path
        result.setValid(result.getPath() != null || result.getPathRegex() != null);
        
        return result;
    }

    /**
     * Extracts the path from a MatchesPath expression.
     * 
     * @param condition the condition string
     * @return the path (e.g., "/todos", "/users/*") or null if not found
     */
    public static String extractMatchesPath(String condition) {
        if (condition == null) return null;
        
        Matcher m = MATCHES_PATH_PATTERN.matcher(condition);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    /**
     * Extracts the HTTP verb from a request.verb expression.
     * 
     * @param condition the condition string
     * @return the uppercase verb (e.g., "GET", "POST") or null if not found
     */
    public static String extractRequestVerb(String condition) {
        if (condition == null) return null;
        
        Matcher m = REQUEST_VERB_PATTERN.matcher(condition);
        if (m.find()) {
            return m.group(1).toUpperCase();
        }
        return null;
    }

    /**
     * Extracts a regex pattern from a path suffix regex match (~).
     * 
     * @param condition the condition string
     * @return the regex pattern or null if not found
     */
    public static String extractPathRegex(String condition) {
        if (condition == null) return null;
        
        Matcher m = PATH_REGEX_PATTERN.matcher(condition);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    /**
     * Extracts header conditions from the expression.
     * 
     * @param condition the condition string
     * @return array of [headerName, expectedValue] or null if not found
     */
    public static String[] extractHeaderCondition(String condition) {
        if (condition == null) return null;
        
        Matcher m = HEADER_CONDITION_PATTERN.matcher(condition);
        if (m.find()) {
            return new String[] { m.group(1), m.group(2) };
        }
        return null;
    }

    /**
     * Extracts query parameter conditions from the expression.
     * 
     * @param condition the condition string
     * @return array of [paramName, expectedValue] or null if not found
     */
    public static String[] extractQueryParamCondition(String condition) {
        if (condition == null) return null;
        
        Matcher m = QUERY_PARAM_CONDITION_PATTERN.matcher(condition);
        if (m.find()) {
            return new String[] { m.group(1), m.group(2) };
        }
        return null;
    }

    // -----------------------------------------------------------------------
    //  Utility: Convert Apigee path to OpenAPI path
    // -----------------------------------------------------------------------
    
    public static String toOpenAPIPath(String apigeePath) {
        if (apigeePath == null || apigeePath.isEmpty()) {
            return "/";
        }

        String[] segments = apigeePath.split("/", -1);
        StringBuilder result = new StringBuilder();
        int wildcardIndex = 0;

        for (int i = 0; i < segments.length; i++) {
            String seg = segments[i];

            if (seg.isEmpty()) {
                if (i == 0) result.append("/");
                continue;
            }

            if (seg.equals("*") || seg.equals("**")) {
                // Derive meaningful parameter name from previous segment
                String paramName = deriveParameterName(segments, i, wildcardIndex++);
                result.append("{").append(paramName).append("}");
            } else {
                result.append(seg);
            }

            // Add separator if more segments follow
            if (i < segments.length - 1 && hasMoreSegments(segments, i + 1)) {
                result.append("/");
            }
        }

        String path = result.toString();
        return path.startsWith("/") ? path : "/" + path;
    }

    private static String deriveParameterName(String[] segments, int wildcardIndex, int count) {
        // Look backward for a meaningful collection name
        for (int i = wildcardIndex - 1; i >= 0; i--) {
            String prev = segments[i];
            if (!prev.isEmpty() && !prev.equals("*") && !prev.equals("**")) {
                // Simple singularization: "todos" → "todo", "users" → "user"
                String singular = (prev.length() > 3 && prev.endsWith("s"))
                        ? prev.substring(0, prev.length() - 1)
                        : prev;
                String paramName = singular + "Id";
                return count == 0 ? paramName : paramName + count;
            }
        }
        return count == 0 ? "id" : "id" + count;
    }

    private static boolean hasMoreSegments(String[] segments, int startIndex) {
        for (int i = startIndex; i < segments.length; i++) {
            if (!segments[i].isEmpty()) return true;
        }
        return false;
    }
}
