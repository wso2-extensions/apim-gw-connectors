/*
 *  Copyright (c) 2025, WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
 package steps

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/cucumber/godog"
)

func registerHTTPSteps(sc *godog.ScenarioContext, ctx *IntegrationContext) {
	sc.Step(`^I send a GET request to "([^"]*)" with username "([^"]*)" and password "([^"]*)"$`, func(url, username, password string) error {
		return sendGETRequestWithBasicAuth(ctx, url, username, password)
	})

	sc.Step(`^the response status code should be (\d+)$`, func(expected int) error {
		return assertStatusCode(ctx, expected)
	})

	sc.Step(`^the response should contain (\d+) elements$`, func(expected int) error {
		return assertElementCount(ctx, "", expected)
	})

	sc.Step(`^the response should contain (\d+) elements at path "([^"]*)"$`, func(expected int, path string) error {
		return assertElementCount(ctx, path, expected)
	})

	sc.Step(`^the response should contain key "([^"]*)" with value "([^"]*)"$`, func(path, value string) error {
		return assertKeyValue(ctx, path, value)
	})
}

func sendGETRequestWithBasicAuth(ctx *IntegrationContext, url, username, password string) error {
	start := time.Now()
	fmt.Printf("[%s] 🌐 GET %s (username=%s)\n", start.Format(time.RFC3339Nano), url, username)

	req, err := http.NewRequest(http.MethodGet, url, nil)
	if err != nil {
		return fmt.Errorf("failed to create request: %w", err)
	}

	req.SetBasicAuth(username, password)

	for key, value := range ctx.DefaultHeaders() {
		req.Header.Set(key, value)
	}

	resp, err := ctx.HTTPClient().Do(req)
	if err != nil {
		return fmt.Errorf("failed to execute GET request: %w", err)
	}

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		_ = resp.Body.Close()
		return fmt.Errorf("failed to read response body: %w", err)
	}

	if err := resp.Body.Close(); err != nil {
		return fmt.Errorf("failed to close response body: %w", err)
	}

	resp.Body = io.NopCloser(bytes.NewReader(body))
	ctx.SetLastResponse(resp, body)

	preview := string(body)
	if len(body) > 512 {
		preview = fmt.Sprintf("%s... (truncated, total %d bytes)", string(body[:512]), len(body))
	}

	fmt.Printf("[%s] 🌐 RESPONSE status=%d bytes=%d\n", time.Now().Format(time.RFC3339Nano), resp.StatusCode, len(body))
	if len(body) > 0 {
		fmt.Printf("[%s] 🌐 RESPONSE BODY PREVIEW: %s\n", time.Now().Format(time.RFC3339Nano), preview)
	}

	return nil
}

func assertStatusCode(ctx *IntegrationContext, expected int) error {
	resp, _ := ctx.LastResponse()
	if resp == nil {
		return fmt.Errorf("no HTTP response captured to assert status code")
	}

	if resp.StatusCode != expected {
		return fmt.Errorf("expected status code %d but received %d", expected, resp.StatusCode)
	}

	fmt.Printf("[%s] ✅ STATUS MATCH expected=%d actual=%d\n", time.Now().Format(time.RFC3339Nano), expected, resp.StatusCode)

	return nil
}

func assertElementCount(ctx *IntegrationContext, path string, expected int) error {
	_, body := ctx.LastResponse()
	if len(body) == 0 {
		return fmt.Errorf("no response body captured for validation")
	}

	var payload interface{}
	if err := json.Unmarshal(body, &payload); err != nil {
		return fmt.Errorf("failed to parse response as JSON: %w", err)
	}

	count, err := resolveElementCount(payload, path)
	if err != nil {
		return err
	}

	if count != expected {
		return fmt.Errorf("expected %d elements but found %d", expected, count)
	}

	target := path
	if target == "" {
		target = "<root>"
	}
	fmt.Printf("[%s] ✅ ELEMENT COUNT path=%s expected=%d actual=%d\n", time.Now().Format(time.RFC3339Nano), target, expected, count)

	return nil
}

func assertKeyValue(ctx *IntegrationContext, path string, expected string) error {
	_, body := ctx.LastResponse()
	if len(body) == 0 {
		return fmt.Errorf("no response body captured for validation")
	}

	var payload interface{}
	if err := json.Unmarshal(body, &payload); err != nil {
		return fmt.Errorf("failed to parse response as JSON: %w", err)
	}

	value, err := resolveJSONValue(payload, path)
	if err != nil {
		return err
	}

	if equal, actual := compareJSONValue(value, expected); !equal {
		return fmt.Errorf("value at path %q expected %q but found %s", path, expected, actual)
	}

	fmt.Printf("[%s] ✅ KEY MATCH path=%s expected=%s\n", time.Now().Format(time.RFC3339Nano), path, expected)
	return nil
}

func resolveJSONValue(payload interface{}, path string) (interface{}, error) {
	if path == "" {
		return payload, nil
	}

	segments := strings.Split(path, ".")
	current := payload

	for _, segment := range segments {
		switch typed := current.(type) {
		case map[string]interface{}:
			next, ok := typed[segment]
			if !ok {
				return nil, fmt.Errorf("key %q not present in JSON response", segment)
			}
			current = next
		case []interface{}:
			index, err := strconv.Atoi(segment)
			if err != nil {
				return nil, fmt.Errorf("segment %q expects array index but is not numeric", segment)
			}
			if index < 0 || index >= len(typed) {
				return nil, fmt.Errorf("index %d out of range for array at segment %q", index, segment)
			}
			current = typed[index]
		default:
			return nil, fmt.Errorf("segment %q cannot be resolved on %T", segment, current)
		}
	}

	return current, nil
}

func compareJSONValue(actual interface{}, expected string) (bool, string) {
	switch v := actual.(type) {
	case string:
		return v == expected, fmt.Sprintf("%q", v)
	case float64:
		if parsed, err := strconv.ParseFloat(expected, 64); err == nil {
			return v == parsed, fmt.Sprintf("%g", v)
		}
		return false, fmt.Sprintf("%g", v)
	case bool:
		if parsed, err := strconv.ParseBool(expected); err == nil {
			return v == parsed, fmt.Sprintf("%t", v)
		}
		return false, fmt.Sprintf("%t", v)
	case nil:
		return strings.EqualFold(expected, "null"), "null"
	case []interface{}, map[string]interface{}:
		jsonBytes, _ := json.Marshal(v)
		return string(jsonBytes) == expected, string(jsonBytes)
	default:
		return fmt.Sprintf("%v", v) == expected, fmt.Sprintf("%v", v)
	}
}

func resolveElementCount(payload interface{}, path string) (int, error) {
	value := payload
	if path != "" {
		segments := strings.Split(path, ".")
		value = payload
		for _, segment := range segments {
			obj, ok := value.(map[string]interface{})
			if !ok {
				return 0, fmt.Errorf("segment %q expects a JSON object but found %T", segment, value)
			}

			next, ok := obj[segment]
			if !ok {
				return 0, fmt.Errorf("key %q not present in JSON response", segment)
			}
			value = next
		}
	}

	switch typed := value.(type) {
	case []interface{}:
		return len(typed), nil
	case map[string]interface{}:
		return len(typed), nil
	default:
		return 0, fmt.Errorf("value at path %q is %T; expected JSON array or object", path, value)
	}
}
