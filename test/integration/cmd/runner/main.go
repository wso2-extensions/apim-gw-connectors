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
 package main

import (
	"flag"
	"fmt"
	"os"
	"strings"

	"github.com/cucumber/godog"

	"github.com/wso2-extensions/apim-gw-connectors/test/integration/steps"
)

func main() {
	if err := execute(); err != nil {
		fmt.Fprintf(os.Stderr, "integration suite failed: %v\n", err)
		os.Exit(1)
	}
}

func execute() error {
	ctx := steps.NewIntegrationContext()

	pattern := newStringList()
	format := flag.String("format", envOrDefault("GODOG_FORMAT", "pretty"), "godog formatter to use (pretty, progress, cucumber)")
	tags := flag.String("tags", envOrDefault("GODOG_TAGS", ""), "filter scenarios by tag expression")
	flag.Var(pattern, "path", "feature path to execute (repeatable, defaults to ./features)")

	flag.Parse()

	paths := pattern.Values()
	if len(paths) == 0 {
		paths = []string{"features"}
	}

	suite := godog.TestSuite{
		Name: "integration",
		ScenarioInitializer: func(sc *godog.ScenarioContext) {
			steps.Register(sc, ctx)
		},
		Options: &godog.Options{
			Format: *format,
			Tags:   *tags,
			Paths:  paths,
		},
	}

	status := suite.Run()
	ctx.PrintScenarioSummary()

	if status != 0 {
		return fmt.Errorf("godog suite exited with status %d", status)
	}

	return nil
}

type stringList struct {
	values []string
}

func newStringList() *stringList {
	return &stringList{}
}

func (s *stringList) String() string {
	return strings.Join(s.values, ",")
}

func (s *stringList) Set(value string) error {
	s.values = append(s.values, value)
	return nil
}

func (s *stringList) Values() []string {
	return append([]string(nil), s.values...)
}

func envOrDefault(key, fallback string) string {
	if val := os.Getenv(key); val != "" {
		return val
	}
	return fallback
}
