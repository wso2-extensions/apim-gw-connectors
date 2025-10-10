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
	"fmt"
	"time"

	"github.com/cucumber/godog"
)

func registerTimeSteps(sc *godog.ScenarioContext) {
	sc.Step(`^I wait for (\d+) seconds$`, func(seconds int) error {
		if seconds < 0 {
			return fmt.Errorf("wait duration must be non-negative, received %d", seconds)
		}

		if seconds == 0 {
			fmt.Printf("[%s] ⏱️ WAIT skipped (0 seconds)\n", time.Now().Format(time.RFC3339Nano))
			return nil
		}

		fmt.Printf("[%s] ⏱️ WAIT start: %d seconds\n", time.Now().Format(time.RFC3339Nano), seconds)
		time.Sleep(time.Duration(seconds) * time.Second)
		fmt.Printf("[%s] ⏱️ WAIT complete: %d seconds\n", time.Now().Format(time.RFC3339Nano), seconds)
		return nil
	})
}
