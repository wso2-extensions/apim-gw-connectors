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
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/cucumber/godog"
)

// Register wires all step definitions for the integration test suite.
func Register(sc *godog.ScenarioContext, integrationCtx *IntegrationContext) {
	var stepTimings sync.Map

	sc.Before(func(scenarioCtx context.Context, scenario *godog.Scenario) (context.Context, error) {
		integrationCtx.Reset()
		integrationCtx.StartScenario(scenario.Name)
		fmt.Printf("[%s] 🟢 START SCENARIO: %s\n", time.Now().Format(time.RFC3339Nano), scenario.Name)
		return scenarioCtx, nil
	})

	sc.After(func(scenarioCtx context.Context, scenario *godog.Scenario, err error) (context.Context, error) {
		status := "PASSED"
		if err != nil {
			status = "FAILED"
		}
		integrationCtx.CompleteScenario(status)
		fmt.Printf("[%s] 🔴 END SCENARIO: %s (%s)\n", time.Now().Format(time.RFC3339Nano), scenario.Name, status)
		return scenarioCtx, nil
	})

	sc.BeforeStep(func(step *godog.Step) {
		start := time.Now()
		stepTimings.Store(step, start)
		fmt.Printf("[%s] ▶ START STEP: %s\n", start.Format(time.RFC3339Nano), step.Text)
	})

	sc.AfterStep(func(step *godog.Step, err error) {
		end := time.Now()
		var duration time.Duration
		if value, ok := stepTimings.LoadAndDelete(step); ok {
			if started, ok := value.(time.Time); ok {
				duration = end.Sub(started)
			}
		}

		status := "PASS"
		if err != nil {
			status = "FAIL"
			integrationCtx.MarkScenarioFailureStep(step.Text)
		}

		fmt.Printf("[%s] ◀ %-4s STEP: %s (duration: %s)\n", end.Format(time.RFC3339Nano), status, step.Text, duration)
	})

	registerHTTPSteps(sc, integrationCtx)
	registerKubernetesSteps(sc, integrationCtx)
	registerTimeSteps(sc)
}
