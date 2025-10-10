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
 package integration_test

import (
	"os"
	"testing"

	"github.com/cucumber/godog"

	"github.com/wso2-extensions/apim-gw-connectors/test/integration/steps"
)

func TestIntegrationFeatures(t *testing.T) {
	if os.Getenv("RUN_INTEGRATION_TESTS") != "true" {
		t.Skip("integration suite disabled: set RUN_INTEGRATION_TESTS=true to enable")
	}

	ctx := steps.NewIntegrationContext()

	format := os.Getenv("GODOG_FORMAT")
	if format == "" {
		format = "progress"
	}

	tags := os.Getenv("GODOG_TAGS")

	suite := godog.TestSuite{
		Name: "integration",
		ScenarioInitializer: func(sc *godog.ScenarioContext) {
			steps.Register(sc, ctx)
		},
		Options: &godog.Options{
			Format: format,
			Paths:  []string{"features"},
			Tags:   tags,
		},
	}

	status := suite.Run()

	ctx.PrintScenarioSummary()

	if status != 0 {
		t.Fatalf("godog suite failed with status %d", status)
	}
}
