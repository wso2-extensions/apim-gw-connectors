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
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"

	"github.com/cucumber/godog"
)

func registerKubernetesSteps(sc *godog.ScenarioContext, _ *IntegrationContext) {
	sc.Step(`^I apply custom resources from "([^"]*)"$`, func(path string) error {
		return applyCustomResources(path)
	})

	sc.Step(`^I delete custom resources from "([^"]*)"$`, func(path string) error {
		return deleteCustomResources(path)
	})
}

func applyCustomResources(path string) error {
	resolvedPath, err := resolveManifestPath(path)
	if err != nil {
		return err
	}

	kubectl := kubectlBinary()
	cmd := exec.Command(kubectl, "apply", "-f", resolvedPath)

	output, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("kubectl apply failed for %s: %w\nOutput:\n%s", resolvedPath, err, string(output))
	}

	fmt.Printf("[kubectl] apply output for %s:\n%s\n", resolvedPath, string(output))
	return nil
}

func deleteCustomResources(path string) error {
	resolvedPath, err := resolveManifestPath(path)
	if err != nil {
		return err
	}

	kubectl := kubectlBinary()
	cmd := exec.Command(kubectl, "delete", "-f", resolvedPath)

	output, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("kubectl delete failed for %s: %w\nOutput:\n%s", resolvedPath, err, string(output))
	}

	fmt.Printf("[kubectl] delete output for %s:\n%s\n", resolvedPath, string(output))
	return nil
}

func resolveManifestPath(ref string) (string, error) {
	if strings.TrimSpace(ref) == "" {
		return "", errors.New("manifest path is required")
	}

	if strings.HasPrefix(ref, "~") {
		home, err := os.UserHomeDir()
		if err != nil {
			return "", fmt.Errorf("unable to resolve home directory: %w", err)
		}
		ref = filepath.Join(home, strings.TrimPrefix(ref, "~"))
	}

	if !filepath.IsAbs(ref) {
		base := os.Getenv("INTEGRATION_MANIFEST_ROOT")
		if base == "" {
			base = "."
		}
		ref = filepath.Join(base, ref)
	}

	abs, err := filepath.Abs(ref)
	if err != nil {
		return "", fmt.Errorf("failed to resolve absolute path for %s: %w", ref, err)
	}

	if _, err := os.Stat(abs); err != nil {
		return "", fmt.Errorf("manifest path %s is not accessible: %w", abs, err)
	}

	return abs, nil
}

func kubectlBinary() string {
	if custom := os.Getenv("KUBECTL_BINARY"); strings.TrimSpace(custom) != "" {
		return custom
	}

	return "kubectl"
}
