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
	"crypto/tls"
	"fmt"
	"net/http"
	"os"
	"strconv"
	"sync"
	"text/tabwriter"
	"time"
)

type scenarioSummary struct {
	name       string
	status     string
	failedStep string
	duration   time.Duration
}

// IntegrationContext stores reusable state across step definitions.
type IntegrationContext struct {
	mu               sync.Mutex
	httpClient       *http.Client
	lastResponse     *http.Response
	lastResponseBody []byte
	defaultHeaders   map[string]string

	scenarioMu                sync.Mutex
	currentScenario           string
	currentScenarioStart      time.Time
	currentScenarioFailedStep string
	scenarioSummaries         []scenarioSummary
}

// NewIntegrationContext returns a context with lazy HTTP client instantiation.
func NewIntegrationContext() *IntegrationContext {
	return &IntegrationContext{
		defaultHeaders: make(map[string]string),
	}
}

// client returns an HTTP client, creating it on first use.
func (ctx *IntegrationContext) client() *http.Client {
	ctx.mu.Lock()
	defer ctx.mu.Unlock()

	if ctx.httpClient == nil {
		transport := &http.Transport{}

		skipVerify := true
		if raw := os.Getenv("INTEGRATION_INSECURE_SKIP_VERIFY"); raw != "" {
			if parsed, err := strconv.ParseBool(raw); err == nil {
				skipVerify = parsed
			}
		}

		if skipVerify {
			transport.TLSClientConfig = &tls.Config{InsecureSkipVerify: true}
		}

		ctx.httpClient = &http.Client{
			Timeout:   60 * time.Second,
			Transport: transport,
		}
	}

	return ctx.httpClient
}

// SetLastResponse stores the most recent HTTP response and its body.
func (ctx *IntegrationContext) SetLastResponse(resp *http.Response, body []byte) {
	ctx.mu.Lock()
	defer ctx.mu.Unlock()

	ctx.lastResponse = resp
	ctx.lastResponseBody = body
}

// LastResponse returns the previously stored HTTP response and body.
func (ctx *IntegrationContext) LastResponse() (*http.Response, []byte) {
	ctx.mu.Lock()
	defer ctx.mu.Unlock()

	return ctx.lastResponse, ctx.lastResponseBody
}

// AddDefaultHeader registers a header sent with subsequent HTTP requests.
func (ctx *IntegrationContext) AddDefaultHeader(key, value string) {
	ctx.mu.Lock()
	defer ctx.mu.Unlock()

	ctx.defaultHeaders[key] = value
}

// RemoveDefaultHeader removes a default header.
func (ctx *IntegrationContext) RemoveDefaultHeader(key string) {
	ctx.mu.Lock()
	defer ctx.mu.Unlock()

	delete(ctx.defaultHeaders, key)
}

// DefaultHeaders returns a shallow copy of default headers.
func (ctx *IntegrationContext) DefaultHeaders() map[string]string {
	ctx.mu.Lock()
	defer ctx.mu.Unlock()

	copy := make(map[string]string, len(ctx.defaultHeaders))
	for k, v := range ctx.defaultHeaders {
		copy[k] = v
	}

	return copy
}

// Reset clears the stored response body while keeping the HTTP client and headers.
func (ctx *IntegrationContext) Reset() {
	ctx.mu.Lock()
	defer ctx.mu.Unlock()

	ctx.lastResponse = nil
	ctx.lastResponseBody = nil
}

// HTTPClient exposes the reusable http.Client instance.
func (ctx *IntegrationContext) HTTPClient() *http.Client {
	return ctx.client()
}

// StartScenario initializes tracking for the provided scenario name.
func (ctx *IntegrationContext) StartScenario(name string) {
	ctx.scenarioMu.Lock()
	defer ctx.scenarioMu.Unlock()

	ctx.currentScenario = name
	ctx.currentScenarioStart = time.Now()
	ctx.currentScenarioFailedStep = ""
}

// MarkScenarioFailureStep records the first failing step for the active scenario.
func (ctx *IntegrationContext) MarkScenarioFailureStep(step string) {
	ctx.scenarioMu.Lock()
	defer ctx.scenarioMu.Unlock()

	if ctx.currentScenario != "" && ctx.currentScenarioFailedStep == "" {
		ctx.currentScenarioFailedStep = step
	}
}

// CompleteScenario finalizes the current scenario tracking and stores a summary.
func (ctx *IntegrationContext) CompleteScenario(status string) {
	ctx.scenarioMu.Lock()
	defer ctx.scenarioMu.Unlock()

	if ctx.currentScenario == "" {
		return
	}

	summary := scenarioSummary{
		name:       ctx.currentScenario,
		status:     status,
		failedStep: ctx.currentScenarioFailedStep,
		duration:   time.Since(ctx.currentScenarioStart),
	}

	ctx.scenarioSummaries = append(ctx.scenarioSummaries, summary)
	ctx.currentScenario = ""
	ctx.currentScenarioFailedStep = ""
}

// PrintScenarioSummary renders an ASCII table describing scenario outcomes.
func (ctx *IntegrationContext) PrintScenarioSummary() {
	summaries := ctx.copyScenarioSummaries()

	fmt.Println("\n=== Integration Scenario Summary ===")
	if len(summaries) == 0 {
		fmt.Println("No scenarios executed.")
		return
	}

	w := tabwriter.NewWriter(os.Stdout, 0, 4, 2, ' ', 0)
	fmt.Fprintln(w, " #\tScenario\tStatus\tFailed Step\tDuration")

	for i, s := range summaries {
		failed := s.failedStep
		if failed == "" {
			failed = "-"
		}
		dur := s.duration.Truncate(time.Millisecond)
		fmt.Fprintf(w, " %d\t%s\t%s\t%s\t%s\n", i+1, s.name, s.status, failed, dur)
	}

	_ = w.Flush()
}

func (ctx *IntegrationContext) copyScenarioSummaries() []scenarioSummary {
	ctx.scenarioMu.Lock()
	defer ctx.scenarioMu.Unlock()

	result := make([]scenarioSummary, len(ctx.scenarioSummaries))
	copy(result, ctx.scenarioSummaries)
	return result
}
