# Kong Gateway Connector

The **Kong Gateway Connector** enables seamless integration between WSO2 API Manager and Kong Gateway, providing comprehensive API management capabilities including authentication, authorization, and traffic management.

## 📁 What's in this Kong Folder

```
kong/
├── gateway-connector/     # Kong Gateway Connector implementation
│   ├── cmd/              # Command-line interface
│   ├── internal/         # Internal packages (agent, discovery, events, k8sClient, loggers)
│   ├── pkg/              # Public packages (transformer)
│   ├── constants/        # Constants and configurations
│   ├── tests/            # Test files
│   └── main.go          # Main entry point
└── sample/               # Sample Kubernetes configurations
    ├── api_crs/          # Sample API Custom Resources
    └── helm/             # Sample Helm values
```

## 🔗 How to Register Kong Connector in Common Agent

### 1. Import the Kong Agent
In the **Common Agent** codebase, modify `internal/agent/registry.go`:

```go
import (
    // Import other agents
    kongAgent "github.com/wso2-extensions/apim-gw-connectors/kong/gateway-connector"
)
```

### 2. Register the Kong Agent
Update the `init()` function in `registry.go`:

```go
func init() {
    // Register other agents
    agentReg.RegisterAgent("kong", &kongAgent.Agent{})
}
```

### 3. Configure via Helm
Set Kong as the gateway in your Common Agent deployment:

```yaml
agent:
  gateway: kong
```

## 🚀 Features

- **JWT Authentication**: Token-based authentication with configurable claims verification
- **ACL Authorization**: Fine-grained access control with group-based permissions
- **CORS Support**: Cross-origin resource sharing with customizable policies
- **Dynamic Plugin Management**: Real-time Kong plugin updates and configuration
- **Secret Management**: Automatic credential lifecycle management
- **Service Discovery**: Kubernetes-native API and service discovery
- **Health Monitoring**: Built-in health checks and status reporting

## 📋 Prerequisites

- **Kubernetes Cluster** (v1.21+)
- **Kong Gateway** (v3.0+) with Kubernetes ingress controller
- **WSO2 API Manager** (v4.0+)

## 🏗️ Kubernetes Implementation Architecture

### Overview
To enable multi-gateway support for Kong in Kubernetes, we use a dedicated Kong Gateway Agent.

### Key Components

#### Common Library
- **Purpose**: Reusable library implemented in Go to generate Kubernetes spec resources
- **Responsibilities**:
  - Creating standard Kubernetes resources such as Services, HTTPRoutes
  - Ensuring consistency and reducing duplication across gateway agents

### API Discovery Flow

Discovery detects existing API configurations:

#### Reconciliation Loop
The gateway-specific agent periodically runs discovery and reconciliation:

1. **List Deployed Resources**: 
   - Queries Kubernetes API server for all relevant gateway resources
   - Examines Services, HTTPRoutes, KongPlugins, KongConsumers

2. **Identify Managed APIs**:
   - Filters resources containing specific label: `InitiateFrom: CP` (Control Plane)
   - Distinguishes between managed and unmanaged resources

3. **Discover and Sync Unmanaged APIs**:
   - Identifies resources without `InitiateFrom: CP` label as "discovered" APIs
   - Reads configuration from Services, HTTPRoutes, and associated KongPlugin CRs
   - Performs reverse translation: Kong resources → abstract API Project format
   - Sends generated API Project to WSO2 control plane as discovered APIctor

The **Kong Gateway Connector** enables seamless integration between WSO2 API Manager and Kong Gateway, providing comprehensive API management capabilities including authentication, authorization, and traffic management.


## 🧪 Complete Testing Guide

Follow these steps to test Kong Gateway federation on Kubernetes with WSO2 API Manager.

### Step 1: Setup Kubernetes Environment

#### Create Namespace
```bash
kubectl create ns kong
```

#### Install Gateway API CRDs
```bash
kubectl apply -f https://github.com/kubernetes-sigs/gateway-api/releases/latest/download/standard-install.yaml -n kong
```

#### Create GatewayClass
Save as `gateway-class.yaml` and apply:
```yaml
apiVersion: gateway.networking.k8s.io/v1
kind: GatewayClass
metadata:
  name: kong
  annotations:
    konghq.com/gatewayclass-unmanaged: 'true'
spec:
  controllerName: konghq.com/kic-gateway-controller
```

```bash
kubectl apply -f gateway-class.yaml -n kong
```

#### Create Gateway with TLS Secret
Save as `gateway.yaml` and apply:
```yaml
apiVersion: v1
kind: Secret
metadata:
  name: kong-tls-secret
  namespace: kong
type: kubernetes.io/tls
data:
  tls.crt: LS0tLS1CRUdJTiBDRVJUSUZJQ0FURS0tLS0tCk1JSUJ4ekNDQVV5Z0F3SUJBZ0lVWHBrcldjWms1ZnlvOFY5MGREektWbjF6c2lFd0NnWUlLb1pJemowRUF3SXcKR2pFWU1CWUdBMVVFQXd3UGEyOXVaMTlqYkhWemRHVnlhVzVuTUI0WERUSTFNREl5TkRBME1UQTBObG9YRFRJNApNREl5TkRBME1UQTBObG93R2pFWU1CWUdBMVVFQXd3UGEyOXVaMTlqYkhWemRHVnlhVzVuTUhZd0VBWUhLb1pJCnpqMENBUVlGSzRFRUFDSURZZ0FFbkpVM2lWUkNmcmtzbTNDVlB1OGdGbHY0RWRlUUNFZnRJekhGdWpQUVY0UmMKL1FVMlRkWjY2cERSSTFaMEVOcGZaNGx3NFZROFlrcS9Ra0pYU2o3Z01ncDBnWm5odXRhRHpZWHpEOVZOZC8yNgpzMG5ORHNTaUlCRmI2TlA5TTRlZG8xTXdVVEFkQmdOVkhRNEVGZ1FVYmNieE04SXhEYTJBVVlodUhFOU1PVTIxCmhkWXdId1lEVlIwakJCZ3dGb0FVYmNieE04SXhEYTJBVVlodUhFOU1PVTIxaGRZd0R3WURWUjBUQVFIL0JBVXcKQXdFQi96QUtCZ2dxaGtqT1BRUURBZ05wQURCbUFqRUFtUWs2ZkV3WEk3Vm9FbHFjdUMxLzRRTU5hNTJhK3RvVgorRGdBN3VxUmRQZlIxRzNtbDZTS3Z6cWZ3eDgrVU5NWUFqRUE1ekR1MUhET2RVbUJWcEpRSjNGdkM2NnN0amVTCndsZ3o4bTI1b21VcXBBNGVzWVoraGtjdnMvSTZielpNczNDZAotLS0tLUVORCBDRVJUSUZJQ0FURS0tLS0t
  tls.key: LS0tLS1CRUdJTiBQUklWQVRFIEtFWS0tLS0tCk1JRzJBZ0VBTUJBR0J5cUdTTTQ5QWdFR0JTdUJCQUFpQklHZU1JR2JBZ0VCQkRDREp2RVRMN0pQY2F4ZmZZeGIKbmR1a0x0cmJUVUJqYkFWSy84RTZKcmgweEhxN2JsQkg5dXdrOHROZ0ZCdk9sZnloWkFOaUFBU2NsVGVKVkVKKwp1U3liY0pVKzd5QVdXL2dSMTVBSVIrMGpNY1c2TTlCWGhGejlCVFpOMW5ycWtORWpWblFRMmw5bmlYRGhWRHhpClNyOUNRbGRLUHVBeUNuU0JtZUc2MW9QTmhmTVAxVTEzL2JxelNjME94S0lnRVZ2bzAvMHpoNTA9Ci0tLS0tRU5EIFBSSVZBVEUgS0VZLS0tLS0=
---
apiVersion: gateway.networking.k8s.io/v1
kind: Gateway
metadata:
  name: kong
  namespace: kong
spec:
  gatewayClassName: kong
  listeners:
  - name: proxy
    port: 8000
    protocol: HTTP
    allowedRoutes:
      namespaces:
        from: Same
  - name: proxy-https
    port: 8443
    protocol: HTTPS
    tls:
      mode: Terminate
      certificateRefs:
      - kind: Secret
        name: kong-tls-secret
        namespace: kong
    allowedRoutes:
      namespaces:
        from: Same
```

```bash
kubectl apply -f gateway.yaml -n kong
```

### Step 2: Install Kong Gateway

#### Add Kong Helm Repository
```bash
helm repo add kong https://charts.konghq.com
helm repo update
```

#### Install Nginx Ingress (if needed)
```bash
helm upgrade --install ingress-nginx ingress-nginx \
  --repo https://kubernetes.github.io/ingress-nginx \
  --namespace ingress-nginx --create-namespace
```

#### Install Kong with Values
Save as `kong-values.yaml`:
```yaml
deployment:
  test:
    enabled: false

controller:
  proxy:
    nameOverride: "kong-gateway-proxy"
    http:
      servicePort: 8000
    tls:
      servicePort: 8443
  enabled: true
  deployment:
    kong:
      enabled: false
  ingressController:
    enabled: true
    env:
      log_level: debug
    gatewayDiscovery:
      enabled: true
      generateAdminApiService: true
  podAnnotations:
    kuma.io/gateway: enabled
    traffic.kuma.io/exclude-outbound-ports: "8444"
    traffic.sidecar.istio.io/excludeOutboundPorts: "8444"

gateway:
  enabled: true
  deployment:
    kong:
      enabled: true
  admin:
    enabled: true
    type: ClusterIP
    clusterIP: None
  ingressController:
    enabled: false
  env:
    role: traditional
    database: "off"
    log_level: debug
  proxy:
    http:
      servicePort: 8000
    tls:
      servicePort: 8443
```

```bash
helm install kong kong/ingress -n kong -f kong-values.yaml
```

### Step 3: Setup WSO2 API Manager

#### Start API Manager with Port Offset
```bash
sh api-manager.sh -DportOffset=1  # APIM will be on port 9444
```

#### Configure Gateway in Admin Portal
1. **Login**: Navigate to `https://localhost:9444/admin` (admin/admin)
2. **Create Gateway**: Go to Gateways section and create new gateway:
   - **Name**: Kong_GW_K8s
   - **Display Name**: Kong Gateway Kubernetes
   - **Type**: Kong Gateway
   - **Mode**: Read-Only
   - **API Discovery Interval**: 0
   - **Deployment Type**: Kubernetes
   - **Host**: kong.wso2.com
   - **HTTP Port**: 8000
   - **HTTPS Port**: 8443

#### Add Host Entry
```bash
echo "127.0.0.1 kong.wso2.com" >> /etc/hosts
```

#### Upload Key Manager Certificate
1. **Generate Certificate**:
   ```bash
   cd <APIM-Home>/repository/resources/security
   keytool -exportcert -alias wso2carbon -keystore ./wso2carbon.jks -file km-cert.crt
   openssl x509 -inform DER -in km-cert.crt -out km-cert.pem
   ```

2. **Upload**: Navigate to Key Managers → Resident Key Manager → Certificates → Upload `km-cert.pem`

### Step 4: Install Kong Agent

#### Get Sample Values Configuration
1. **Copy Sample Values**: Get the `values.yaml` from `kong/sample/helm` directory
2. **Replace Common Agent Values**: Copy it and replace `common-agent/helm/values.yaml`
3. **Install Kong Agent**:
   ```bash
   # Navigate to common-agent/helm directory
   cd common-agent/helm
   
   # Install Kong Agent using the values.yaml
   helm install kong-agent ./. -n kong -f ./values.yaml
   ```

#### Verify Installation
```bash
# Check KongPlugins for rate limiting policies
kubectl get KongPlugins -n kong

# Check secrets for Key Managers
kubectl get secrets -n kong
```

### Step 9: Discover API

For testing with pre-built Custom Resources:

#### Using Sample Configurations
1. **Apply Sample CRs**:
   ```bash
   kubectl apply -f ../sample/api_crs/
   ```
   
   **Sample CRs Include**:
   - **Service CR**: Defines backend service for API requests
   - **HTTPRoute CRs**: Map API paths (`/comments/*`) to backend service  
   - **OPTIONS HTTPRoute CRs**: Handle CORS preflight requests
   - **ACL KongPlugin**: Restricts access to specific consumers/applications
   - **JWT KongPlugin**: Enforces token validation against WSO2 APIM Key Manager
   - **CORS KongPlugin**: Manages cross-origin resource sharing policies

2. **Publisher Portal Workflow**:
   - **Sign in** to Publisher Portal
   - **View discovered API** (auto-discovered from applied CRs)
   - **Navigate to Deployments** → View API deployed to Kong Gateway
   - **Publish API** to Developer Portal

3. **Testing**:
   - **Subscribe** via Dev Portal
   - **Generate Keys** and obtain access token
   - **Invoke API** using curl or API Console

#### Sample CR Reference
Use the sample configurations at: `https://github.com/wso2-extensions/apim-gw-connectors/blob/main/kong/sample/api_crs`

#### Debug Commands
```bash
# Comprehensive logs
kubectl logs -n kong deployment/kong-gateway --tail=100
kubectl logs -n kong deployment/kong-agent-wso2-common-agent-deployment --tail=100

# Resource status check
kubectl get all -n kong
kubectl get kongplugins,kongconsumers,httproutes,services -n kong

# Event monitoring
kubectl get events -n kong --sort-by='.lastTimestamp'
```
### Step 10: Test API Invocation

1. **Generate Token**: Get OAuth2 token from application
2. **Test API**:
   ```bash
   curl -X GET https://kong.wso2.com/commentsApiContext/1/api/v1/comments \
     -H "Authorization: Bearer YOUR_ACCESS_TOKEN"
   ```

## 🔧 Development

### Building from Source

```bash
# Clone repository
git clone https://github.com/wso2-extensions/apim-gw-connectors.git
cd apim-gw-connectors/kong/gateway-connector

# Install dependencies
go mod tidy

# Build
go build ./...

# Run tests
go test ./...
```

## 🔗 Related Links

- [WSO2 API Manager Documentation](https://apim.docs.wso2.com/)
- [Kong Gateway Documentation](https://docs.konghq.com/gateway/)
- [Kubernetes Gateway API](https://gateway-api.sigs.k8s.io/)
- [Sample Configurations](../sample/README.md)