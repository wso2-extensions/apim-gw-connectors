<!-- 
--------------------------------------------------------------------
Copyright (c) 2025, WSO2 LLC. (http://wso2.com) All Rights Reserved.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
----------------------------------------------------------------------- -->
# Integration Test Suite

This directory hosts Cucumber (Godog) scenarios that exercise end-to-end resource management flows against a running gateway deployment.

## ✨ What you get

- **Kubernetes automation** – steps to apply or delete a folder of custom resources via `kubectl`.
- **HTTP verification** – steps to invoke secured GET endpoints with HTTP basic authentication.
- **Response introspection** – steps that count JSON elements at the root or a nested object/array.
- **Sample assets** – ready-made manifests under `testdata/manifests` to validate the workflow.

## 🔧 Prerequisites

- `kubectl` installed and pointing to a cluster with the required permissions.
- Network access to the target endpoint(s) you plan to test.
- Go toolchain (matching the repository version) and module dependencies (`go mod tidy` already invoked).

Optional environment variables:

| Variable | Purpose |
|----------|---------|
| `RUN_INTEGRATION_TESTS` | Set to `true` to execute the suite; otherwise the tests are skipped. |
| `GODOG_FORMAT` | Overrides the Godog output format (defaults to `progress`). |
| `GODOG_TAGS` | Filters scenarios by tags, e.g. `@sample`. |
| `KUBECTL_BINARY` | Custom path to the `kubectl` executable. |
| `INTEGRATION_MANIFEST_ROOT` | Base directory prepended to relative manifest paths. |
| `INTEGRATION_INSECURE_SKIP_VERIFY` | Set to `true` to disable TLS verification for HTTP calls (useful for self-signed certs). |

## ▶️ Running the suite

go test ./...
```bash
cd test/integration
export RUN_INTEGRATION_TESTS=true
# Optional: export GODOG_TAGS=@sample
go test ./...
```

### 🔴 Need real-time logs?

The Go testing harness buffers stdout per test, so logs appear only after a scenario completes. Run the standalone runner for live streaming output:

```bash
cd test/integration
go run ./cmd/runner -format pretty -tags @sample -path features
```

Flags mirror the existing environment variables; omit `-path` to default to `./features`.

> ℹ️ The provided `@sample` scenario calls `https://httpbin.org/basic-auth/foo/bar` and applies the manifests under `testdata/manifests`. Adjust or replace these assets to match your environment before running against production systems. The sample files are numbered (`00-namespace.yaml`, `10-configmap.yaml`) so the namespace is created before dependent resources; keep that ordering if you add more manifests.

## 🧱 Step catalogue

| Step | Description |
|------|-------------|
| `I apply custom resources from "<path>"` | Runs `kubectl apply -f <path>`. Works with files or directories. |
| `I delete custom resources from "<path>"` | Runs `kubectl delete -f <path>` for cleanup. |
| `I send a GET request to "<url>" with username "<user>" and password "<pass>"` | Issues a GET request with HTTP basic auth. |
| `the response status code should be <code>` | Asserts the captured HTTP status. |
| `the response should contain <n> elements` | Counts top-level JSON keys/array items. |
| `the response should contain <n> elements at path "<json.path>"` | Traverses a dotted path and counts elements at that location. |
| `the response should contain key "<json.path>" with value "<value>"` | Retrieves a JSON scalar at the given path and compares it to the expected value. |
| `I wait for <n> seconds` | Pauses execution for the requested number of seconds (useful for eventual consistency). |

## ✅ Validation

The module includes a Go unit that wraps the Godog suite. When `RUN_INTEGRATION_TESTS` is not set to `true`, `go test ./...` exits successfully after printing a skip message, keeping CI pipelines fast by default.
