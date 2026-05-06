# API-Scoped API Key Lambda Authorizer (AWS REST API Gateway)

This Lambda authorizer is intended as a plugin for Gateway Federation on AWS.
It is not the component that enforces usage-plan quota or throttling. AWS API
Gateway does that natively through `x-api-key` and usage plans. This Lambda only
adds API-level scoping on top of that native flow.

The intended model is:

1. WSO2 creates the AWS API key and attaches it to the mapped AWS usage plan.
2. The client sends the key in `x-api-key`.
3. API Gateway applies native API key validation, quota, and throttling.
4. This Lambda authorizer validates that the same key is scoped to the current
   AWS API using the `wso2:api-id` tag.

This implementation uses AWS SDK v3 from the Lambda runtime. No `node_modules`
or extra dependency packaging is required for normal setup.

## Remote API Requirements

Discovery is read-only. It does not create or update authorizers, methods,
stages, usage-plan links, or API keys in AWS. Discovery only reads the remote
API and builds the local WSO2 API model.

For this integration to work on a remote AWS API, the API must be wired
manually in AWS API Gateway to match the requirements in this document.

The connector deploy/reimport flow can set only the parts it can infer safely:

- REST API `apiKeySource=HEADER`
- `API Key Required=true` for API-key-secured non-`OPTIONS` methods

It does not create, update, or remove authorizers because the required Lambda
ARN and invoke-role details are not treated as deploy-time connector-owned
configuration.

The remote AWS API must satisfy all of the following:

- The API stage used by WSO2 exists.
- The REST API `apiKeySource` is `HEADER`.
- Every protected non-`OPTIONS` method uses:
  - `Authorization = CUSTOM`
  - the API-scoped Lambda authorizer
  - `API Key Required = true`
- The target stage is attached to the relevant usage plan.
- Each generated AWS API key is:
  - enabled
  - attached to the mapped usage plan
  - tagged with `wso2:api-id=<AWS_API_ID>`

If these remote prerequisites are missing, the local API can still be
discovered, but runtime API-key invocation will not work correctly.

## What It Validates

1. `x-api-key` header is present.
2. Header value matches an enabled API key in API Gateway.
3. API key has a `wso2:api-id` tag matching the target API ID in `methodArn`.

Usage-plan attachment is not checked here. Native AWS usage-plan enforcement is
expected to run because the method has `API Key Required: true`.

## Required API Key Tag

- `wso2:api-id`
  - Must contain exactly one AWS API ID value.
  - Example: `a1b2c3d4e5`

### Behavior

- If `wso2:api-id` is missing or empty: deny.
- If `wso2:api-id` is different from request API ID: deny.
- If `wso2:api-id` matches request API ID: allow.

## Required Lambda IAM Permissions

Grant this Lambda execution role read access to API Gateway API keys and tags:

- `apigateway:GET` on `/apikeys`
- `apigateway:GET` on `/apikeys/*`
- `apigateway:GET` on `/tags/*`

CloudWatch logs permissions are also required.

Minimum policy example:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "ApiGatewayReadApiKeysAndTags",
      "Effect": "Allow",
      "Action": ["apigateway:GET"],
      "Resource": [
        "arn:aws:apigateway:*::/apikeys",
        "arn:aws:apigateway:*::/apikeys/*",
        "arn:aws:apigateway:*::/apikeys/*/tags",
        "arn:aws:apigateway:*::/tags/*"
      ]
    },
    {
      "Sid": "CloudWatchLogs",
      "Effect": "Allow",
      "Action": [
        "logs:CreateLogGroup",
        "logs:CreateLogStream",
        "logs:PutLogEvents"
      ],
      "Resource": "*"
    }
  ]
}
```

If you already have a managed policy attached to the Lambda role, create a new
default policy version with the updated resources:

```bash
aws iam create-policy-version \
  --policy-arn <POLICY_ARN> \
  --policy-document file://updated-policy.json \
  --set-as-default
```

## Runtime Config (Environment Variables)

- `CACHE_TTL_SECONDS` (default: `60`)
- `LOG_LEVEL` (`debug`, `info`, `warn`, `error`; default: `info`)

## API Gateway Wiring

Recommended configuration for this handler:

- Authorizer type: `REQUEST`
- Identity source: `method.request.header.x-api-key`
- Result TTL: `0` for strict real-time checks
- Method authorization: `CUSTOM`
- Method `API Key Required`: `true`
- REST API `apiKeySource`: `HEADER`

This is important:

- `x-api-key` is the native AWS API key header.
- `API Key Required` must be `true` or usage plans will not meter requests.
- The Lambda authorizer does API-bound validation only. It does not replace
  AWS usage-plan enforcement.

## Step-by-Step Setup

### 1. Package the Lambda

From this folder, create a zip with only `index.js`.

```bash
zip authorizer.zip index.js
```

No dependency install step is needed.

### 2. Create or Update the Lambda Execution Role

Create an IAM role trusted by Lambda, then attach:

- API Gateway read permissions for API keys and tags
- CloudWatch logs permissions

If the role already exists, update the attached managed policy to the minimum
policy shown above.

### 3. Create or Update the Lambda Function

Create the function with runtime Node.js 20 and upload `authorizer.zip`.
Set handler to:

```text
index.handler
```

Set optional environment variables:

- `CACHE_TTL_SECONDS=60`
- `LOG_LEVEL=info`

To update an existing function:

```bash
aws lambda update-function-code \
  --function-name <LAMBDA_NAME_OR_ARN> \
  --zip-file fileb://authorizer.zip
```

### 4. Allow API Gateway to Invoke Lambda

At initial setup time, API IDs are usually not known yet. Use wildcard API ID
permission first:

```bash
aws lambda add-permission \
  --function-name <LAMBDA_NAME_OR_ARN> \
  --statement-id apigw-authorizer-invoke \
  --action lambda:InvokeFunction \
  --principal apigateway.amazonaws.com \
  --source-arn "arn:aws:execute-api:<REGION>:<ACCOUNT_ID>:*/authorizers/*"
```

If that statement already exists, skip this step.

### 5. Create the API Gateway Authorizer

Create a `REQUEST` authorizer and point it to the Lambda.

```bash
aws apigateway create-authorizer \
  --rest-api-id <API_ID> \
  --name APIKeyAuthorizer \
  --type REQUEST \
  --authorizer-uri "arn:aws:apigateway:<REGION>:lambda:path/2015-03-31/functions/<LAMBDA_ARN>/invocations" \
  --authorizer-credentials <INVOKE_ROLE_ARN> \
  --identity-source "method.request.header.x-api-key" \
  --authorizer-result-ttl-in-seconds 0 \
  --region <REGION>
```

If the authorizer already exists, update the identity source:

```bash
aws apigateway update-authorizer \
  --rest-api-id <API_ID> \
  --authorizer-id <AUTHORIZER_ID> \
  --patch-operations op=replace,path=/identitySource,value='method.request.header.x-api-key' \
  --region <REGION>
```

### 6. Attach the Authorizer and Enable Native API Key Enforcement

For each protected method:

- Authorization: `CUSTOM`
- Custom authorizer: the authorizer created above
- `API Key Required`: `true`

CLI example:

```bash
aws apigateway update-method \
  --rest-api-id <API_ID> \
  --resource-id <RESOURCE_ID> \
  --http-method GET \
  --patch-operations \
    op=replace,path=/authorizationType,value=CUSTOM \
    op=replace,path=/authorizerId,value=<AUTHORIZER_ID> \
    op=replace,path=/apiKeyRequired,value=true \
  --region <REGION>
```

If needed, explicitly keep the REST API in native header mode:

```bash
aws apigateway update-rest-api \
  --rest-api-id <API_ID> \
  --patch-operations op=replace,path=/apiKeySource,value=HEADER \
  --region <REGION>
```

Redeploy the API stage after method changes:

```bash
aws apigateway create-deployment \
  --rest-api-id <API_ID> \
  --stage-name <STAGE> \
  --region <REGION>
```

### 7. Tag Each API Key With the Target API ID

Each key must have one tag:

- Key: `wso2:api-id`
- Value: target AWS API ID

Example:

```bash
aws apigateway tag-resource \
  --resource-arn "arn:aws:apigateway:<REGION>::/apikeys/<API_KEY_ID>" \
  --tags "wso2:api-id=<API_ID>"
```

### 8. Attach the Key to the Correct Usage Plan

This is handled by the federation flow. If you are testing manually:

```bash
aws apigateway create-usage-plan-key \
  --usage-plan-id <USAGE_PLAN_ID> \
  --key-id <API_KEY_ID> \
  --key-type API_KEY \
  --region <REGION>
```

### 9. Test

Invoke the API with the native AWS header:

```bash
curl -H "x-api-key: <API_KEY_VALUE>" \
  "https://<API_ID>.execute-api.<REGION>.amazonaws.com/<STAGE>/<RESOURCE_PATH>"
```

Expected:

- `200` when the key is valid, enabled, attached to a usage plan, and tagged to
  the target API.
- `401` or `403` when native AWS API key validation or the Lambda scope check
  fails.

To confirm usage-plan metering is active, invoke once and inspect usage:

```bash
aws apigateway get-usage \
  --usage-plan-id <USAGE_PLAN_ID> \
  --key-id <API_KEY_ID> \
  --start-date 2026-03-01 \
  --end-date 2026-03-31 \
  --region <REGION>
```

The key should appear in the usage result after a successful request.

## Quick Troubleshooting

- **Requests are still not counted against the usage plan**
  - Check the method has `API Key Required: true`.
  - Check the client sends `x-api-key`, not a custom header.
- **Authorizer is not invoked**
  - Check method `Authorization` is `CUSTOM` and the correct authorizer is selected.
- **Authorizer denies valid requests**
  - Check the API key has `wso2:api-id=<AWS_API_ID>`.
- **401 before Lambda runs**
  - Check the API key is valid, enabled, and attached to a usage plan.
- **Lambda permission error from API Gateway**
  - Check the `lambda:add-permission` statement exists for `*/authorizers/*` or
    for the specific API.
