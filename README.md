# AWS Lambda OIDC authorizer
  - This is a Java based AWS Lambda function
  - It can authorize incoming HTTP requests on an AWS API gateway (2015 REST type)
  - It supports OAuth2 OpenID Connect (OIDC) authentication protocol
  - It validates JWT access tokens issued by an OIDC-compliant issuer
  - It enforces scope-based access control per endpoint

## Authorization outcomes
The lambda returns an IAM policy that is **deny-all by default** — only explicitly allowed method ARNs are permitted. If no allows are added, an explicit `Deny "*"` is emitted as a safety net.

| Situation                                                                                    | Status | Error code                    | Mechanism                                |
|----------------------------------------------------------------------------------------------|--------|-------------------------------|------------------------------------------|
| Operation NOT in `permissions.yaml`                                                          | 403    | `COMMON_ACCESS_DENIED`        | `Deny` policy                            |
| Operation is in `permissions.yaml`, but requires no scopes (public)                          | 200    |                               | `Allow` policy, token ignored if present |
| Operation is in `permissions.yaml`, requires scopes, but no token                            | 401    | `COMMON_MISSING_CREDENTIALS`  | `throw "Unauthorized"`                   |
| Operation is in `permissions.yaml`, requires scopes, but malformed token                     | 401    | `COMMON_INVALID_CREDENTIALS`  | `throw "Unauthorized"`                   |
| Operation is in `permissions.yaml`, requires scopes, but unknown issuer                      | 401    | `COMMON_INVALID_ISSUER`       | `throw "Unauthorized"`                   |
| Operation is in `permissions.yaml`, requires scopes, but expired token                       | 401    | `COMMON_EXPIRED_ACCESS_TOKEN` | `throw "Unauthorized"`                   |
| Operation is in `permissions.yaml`, requires scopes, but invalid token (signature, claims)   | 401    | `COMMON_INVALID_CREDENTIALS`  | `throw "Unauthorized"`                   |
| Operation is in `permissions.yaml`, requires scopes, valid token, but scopes don't intersect | 403    | `COMMON_ACCESS_DENIED`        | `Deny` policy                            |
| Operation is in `permissions.yaml`, requires scopes, valid token, scopes intersect           | 200    |                               | `Allow` policy                           |
| Internal error (S3 unreachable, config parse failure, unexpected bug)                        | 500    |                               | Exception propagates to Lambda runtime   |

## Authorization flow
  1. Extract path and HTTP method from the incoming REQUEST event
  2. Look up the operation in `permissions.yaml`
     - **Not found** &rarr; Deny (403)
     - **Public** (no scopes required) &rarr; Allow (200), skip token validation entirely
     - **Scopes required** &rarr; continue
  3. Extract Bearer token from the `Authorization` header
     - **Missing** &rarr; throw `"Unauthorized"` (401)
  4. Validate the JWT (signature, algorithm, issuer, expiry, required claims)
     - **Invalid** &rarr; throw `"Unauthorized"` (401)
  5. Extract scopes from the validated token claims
  6. Check if the token scopes intersect with the required scopes (OR logic)
     - **No intersection** &rarr; Deny (403)
     - **Intersection** &rarr; Allow (200)

## Token validation
The JWT is validated per-issuer using Nimbus JOSE + JWT processors built at cold start. Validation checks:
  - **Type header** — accepts `at+jwt` (RFC 9068), `JWT`, and absent `typ` (PingFederate compatibility)
  - **Algorithm** — only algorithms declared in `issuers.json` are accepted (prevents algorithm substitution)
  - **Signature** — verified against the issuer's JWKS public keys
  - **Claims** — exact `iss` match, required `sub` and `iat`, `exp` always enforced

Scopes are extracted from the `scope` claim as a space-delimited string (OAuth2 standard).

### JWKS retrieval resilience
Each issuer's JWKS source is configured with:
  - **Rate limiting** — at most one JWKS fetch per 30 seconds per issuer
  - **Cache + refresh-ahead** — 1-hour TTL, background refresh 30 seconds before expiry
  - **Retry** — one automatic retry on transient network errors
  - **Outage tolerance** — stale JWKS served for up to 4 hours during IdP outages
  - **Health reporting** — degraded/recovered transitions logged via SLF4J

## Authorization sequence
```mermaid
sequenceDiagram
  autonumber
  participant C as Client
  participant AG as API Gateway
  participant LA as Authorizer
  participant IdP as OIDC IdP

  C->>AG: Request + Bearer JWT
  AG->>LA: Invoke (REQUEST event)

  note right of LA: Look up operation in permissions.yaml

  alt Not configured
    LA-->>AG: Deny policy
    AG-->>C: 403
  else Public (no scopes required)
    LA-->>AG: Allow policy
    AG->>C: Backend response
  else Scopes required
    note right of LA: Extract Bearer token

    alt No token
      LA--xAG: throw "Unauthorized"
      AG-->>C: 401
    end

    alt JWKS cache miss
      LA->>IdP: GET /jwks
      IdP-->>LA: JWKS
    end

    note right of LA: Validate JWT<br/>(alg, sig, iss, sub, iat, exp)

    alt Invalid token
      LA--xAG: throw "Unauthorized"
      AG-->>C: 401
    end

    note right of LA: Check token scopes ∩ required scopes

    alt No intersection
      LA-->>AG: Deny policy
      AG-->>C: 403
    else Intersection
      LA-->>AG: Allow policy
      AG->>C: Backend response
    end
  end
```

# Configuration

## Environment variables

### Required

| Variable              | Type   | Description                                                |
|-----------------------|--------|------------------------------------------------------------|
| `APP_CONF_S3_REGION`  | String | AWS region of the S3 bucket (e.g. `eu-central-1`)          |
| `APP_CONF_S3_BUCKET`  | String | S3 bucket containing `issuers.json` and `permissions.yaml` |
| `APP_CONF_S3_TTL_SEC` | Long   | Cache TTL in seconds (must be > 0)                         |

### Optional (logging context)

Static labels added to every log line via SLF4J MDC. Useful for filtering and routing in centralized logging.

| Variable                                  | MDC key          | Description              |
|-------------------------------------------|------------------|--------------------------|
| `APP_CONF_LOGGINGCONTEXT_BUSINESS_DOMAIN` | `businessDomain` | Business domain label    |
| `APP_CONF_LOGGINGCONTEXT_COMPONENT_TYPE`  | `componentType`  | Component type label     |
| `APP_CONF_LOGGINGCONTEXT_COMPONENT_NAME`  | `componentName`  | Component name label     |

### Optional (test mode)

Setting any of these activates test mode, which uses static credentials and a custom S3 endpoint (e.g. MinIO) instead of the AWS default credentials chain.

| Variable                      | Description                                                  |
|-------------------------------|--------------------------------------------------------------|
| `APP_TEST_CONF_S3_ENDPOINT`   | Custom S3-compatible endpoint (e.g. `http://127.0.0.1:9000`) |
| `APP_TEST_CONF_S3_ACCESS_KEY` | Access key for the custom endpoint                           |
| `APP_TEST_CONF_S3_SECRET_KEY` | Secret key for the custom endpoint                           |

## S3 configuration files

Two files must be present in the configured S3 bucket:

### `issuers.json`
Defines the accepted OIDC token issuers.
```json
{
  "acceptedIssuers": [
    {
      "iss": "https://idp.example.com",
      "jwksUrl": "https://idp.example.com/.well-known/jwks.json",
      "acceptedAlgorithms": ["RS512"]
    }
  ]
}
```
- `iss` — expected value of the JWT `iss` claim (exact match)
- `jwksUrl` — JWKS endpoint for signature verification
- `acceptedAlgorithms` — allowed signing algorithms (prevents algorithm substitution)

### `permissions.yaml`
Defines endpoint-to-scope mappings using an OpenAPI 3.0.1 subset.
```yaml
openapi: 3.0.1
info:
  title: Security scopes
  version: 1.0.0
paths:
  /protected-resource:
    get:
      x-oidcScopes:
        - RESOURCE_READ
    post:
      x-oidcScopes:
        - RESOURCE_WRITE
  /public-resource:
    get:
      # No x-oidcScopes = public, no token required
```
- **`x-oidcScopes` present and non-empty** — token required, scopes checked (OR intersection)
- **`x-oidcScopes` absent or empty** — public endpoint, token ignored
- **Path/method not listed** — denied (403)

## S3 caching and refresh
  - Configuration files are cached in-memory with a TTL set by `APP_CONF_S3_TTL_SEC`
  - On TTL expiry, the cache issues a conditional GET (`If-None-Match` with the stored ETag)
    - **304 Not Modified** — TTL is reset, no data transfer
    - **200 OK** — content and ETag are updated
  - Per-key locking prevents redundant S3 requests under concurrent Lambda invocations
  - Stale content is never served after an S3 error (security-critical for JWKS key rotations)

## Cold start
The Lambda constructor runs once per cold start, before any request is handled. It performs the following steps in order:
  1. Load and validate environment variables
  2. Create the S3 client (production or test mode based on env vars)
  3. Initialize the in-memory file cache
  4. Fetch `issuers.json` from S3 and parse it
  5. Fetch `permissions.yaml` from S3 and parse it
  6. Build JWKS sources and JWT processors for each configured issuer

If any step fails, the Lambda does not start — it will not accept requests in a degraded state. AWS Lambda automatically retries the init on transient failures.

## Request time
  - `permissions.yaml` is re-read from cache per request (picks up TTL refreshes)
  - JWT processors built at cold start are reused — changes to `issuers.json` require a new cold start to take effect

## Structured logging (SLF4J MDC)

Every log line emitted during a request carries the following MDC fields, populated by `LoggingContextConfigurer`. Fields are set in two phases: request context (before any business logic) and JWT context (after token validation). MDC is cleared in a `finally` block at the end of each invocation.

| MDC key            | Source                                             | Description                                   |
|--------------------|----------------------------------------------------|-----------------------------------------------|
| `isColdStart`      | Handler cold start flag                            | `true` on first invocation, `false` after     |
| `functionName`     | `Context.getFunctionName()`                        | Lambda function name                          |
| `functionVersion`  | `Context.getFunctionVersion()`                     | Lambda function version                       |
| `X-Request-ID`     | Request header (case-insensitive)                  | RICE request tracing ID                       |
| `X-Correlation-ID` | Request header (case-insensitive)                  | RICE correlation ID for cross-service tracing |
| `jwtIss`           | JWT `iss` claim                                    | Token issuer                                  |
| `jwtSub`           | JWT `sub` claim                                    | Token subject (user/service identity)         |
| `jwtClientId`      | JWT `client_id` claim                              | OAuth client ID                               |
| `businessDomain`   | Env var `APP_CONF_LOGGINGCONTEXT_BUSINESS_DOMAIN`  | Static business domain label                  |
| `componentType`    | Env var `APP_CONF_LOGGINGCONTEXT_COMPONENT_TYPE`   | Static component type label                   |
| `componentName`    | Env var `APP_CONF_LOGGINGCONTEXT_COMPONENT_NAME`   | Static component name label                   |

JWT fields (`jwtIss`, `jwtSub`, `jwtClientId`) are only present when the request reaches token validation — they are absent for public endpoints or when the request fails before token parsing.

## Integration with the API gateway
  - configure the API gateway to make every request hit the lambda
    - no auth caching
      - set `authorizerResultTtlInSeconds` to 0
    - no API gateway decisions (otherwise endpoints without an `Authorization` header would be rejected by API gateway before the lambda is invoked)
      - set the event `type` to `request`
      - leave `identitySource` unset
  - the event type passed by the API gateway must be `REQUEST`
  - `TOKEN` event type is not supported because it lacks essential information
  - the lambda returns an IamPolicyResponse JSON structure to the API gateway
  - **Limitation:** Gateway Response templates only support `$context.error.*` variables, not `$context.authorizer.*`. This means the lambda cannot control 401/403 response bodies — clients receive generic API Gateway error messages regardless of the specific cause:
    - 401: `{"message": "Unauthorized"}`
    - 403: `{"message": "User is not authorized to access this resource with an explicit deny"}`
    - The exact reason for a 401 or 403 must be found in the authorizer's CloudWatch logs

Example of an API gateway configuration file:
```
components:
  securitySchemes:
    lambdaAuthorizer:
      type: apiKey
      name: Authorization
      in: header
      x-amazon-apigateway-authtype: custom
      x-amazon-apigateway-authorizer:
        type: request
        authorizerResultTtlInSeconds: 0
        # When "type: request" and "authorizerResultTtlInSeconds: 0", "identitySource" can be entirely omitted to make the API-GW call the authorizer lambda each time, even when Authorization HTTP header is not present
        # identitySource: method.request.header.Authorization
        authorizerUri: "arn:aws:apigateway:eu-central-1:lambda:path/2015-03-31/functions/arn:aws:lambda:eu-central-1:${ACCOUNT_ID}:function:${AUTHORIZER_LAMBDA_FUNCTION_NAME}:${AUTHORIZER_LAMBDA_FUNCTION_ALIAS_NAME}/invocations"
        authorizerCredentials: "${AUTHORIZER_LAMBDA_INVOCATION_ROLE_ARN}"
```

# Testing and/or running locally

## Standalone MinIO server

### Start
```
docker compose -f src/test/resources/mock-s3/docker-compose.yaml up
```

### Stop
```
docker compose -f src/test/resources/mock-s3/docker-compose.yaml down
```

### WebUI
http://127.0.0.1:9001/browser
minioadmin / minioadmin

## Build image with Docker
```
docker build -t com.example:lambda-oidc-authorizer .
```

## Run image locally with Docker, passing required environment variables
This setup also requires a Minio running locally with Docker!
```
docker run --rm -p 9020:8080 \
  -e APP_CONF_S3_REGION=eu-central-1 \
  -e APP_CONF_S3_BUCKET=test-bucket \
  -e APP_CONF_S3_TTL_SEC=5 \
  -e APP_TEST_CONF_S3_ENDPOINT=http://host.docker.internal:9000 \
  -e APP_TEST_CONF_S3_ACCESS_KEY=minioadmin \
  -e APP_TEST_CONF_S3_SECRET_KEY=minioadmin \
  com.example:lambda-oidc-authorizer
```

## Call locally running container over HTTP
The Lambda Java runtime container exposes the Runtime Interface Emulator on port 8080 at a fixed path

### TOKEN event type (not supported by the Lambda)
```
curl -XPOST "http://localhost:9020/2015-03-31/functions/function/invocations" \
  -d '{
    "type": "TOKEN",
    "authorizationToken": "Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig",
    "methodArn": "arn:aws:execute-api:eu-central-1:123456123456:12345678/example_stage/POST/example-resource/path param value/child-resource"
  }'
```

### REQUEST event type
```
curl -XPOST "http://localhost:9020/2015-03-31/functions/function/invocations" \
  -d '{
    "type": "REQUEST",
    "methodArn": "arn:aws:execute-api:eu-central-1:123456123456:12345678/example_stage/POST/example-resource/path%20param%20value/child-resource",
    "resource": "/example-resource/{example-path-param}/child-resource",
    "path": "/example-resource/path%20param%20value/child-resource",
    "httpMethod": "POST",
    "headers": {"Authorization": "Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig"},
    "queryStringParameters": {"a": "20", "b": "30"},
    "pathParameters": {"example-path-param": "path param value"},
    "stageVariables": {"AUTHORIZER_LAMBDA_FUNCTION_NAME": "example_lambda_name", "AUTHORIZER_LAMBDA_FUNCTION_ALIAS_NAME": "example_lambda_alias"},
    "requestContext": {"accountId": "123456123456", "apiId": "12345678", "stage": "example_stage"}
  }'
```
