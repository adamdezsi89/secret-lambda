# AWS Lambda OIDC authorizer
  - This is a Java based AWS Lambda function
  - It can authorize incoming HTTP requests on an AWS API gateway (2015 REST type)
  - It supports OAuth2 OpenID Connect (OIDC) authentication protocol
  - It validates JWT access tokens issued by an OIDC-compliant issuer

## Authorization outcomes
| Situation                                                                                    | Status  | Mechanism                                |
|----------------------------------------------------------------------------------------------|---------|------------------------------------------|
| Operation NOT in `permissions.yaml`                                                          | 403     | `Deny` policy                            |
| Operation is in `permissions.yaml`, but requires no scopes (public)                          | 200     | `Allow` policy, token ignored if present |
| Operation is in `permissions.yaml`, requires scopes, but no token                            | 401     | `throw "Unauthorized"`                   |
| Operation is in `permissions.yaml`, requires scopes, but invalid token                       | 401     | `throw "Unauthorized"`                   |
| Operation is in `permissions.yaml`, requires scopes, valid token, but scopes don't intersect | 403     | `Deny` policy                            |
| Operation is in `permissions.yaml`, requires scopes, valid token, scopes intersect           | 200     | `Allow` policy                           |

## Authorization sequence
```mermaid
sequenceDiagram
  autonumber
  participant C as Client
  participant AG as API Gateway
  participant LA as Authorizer
  participant PF as PingFederate

  C->>AG: Request + Bearer JWT
  AG->>LA: Invoke (REQUEST event)

  alt Cache miss
    LA->>PF: GET /.well-known/openid-configuration
    PF-->>LA: issuer, jwks_uri
    LA->>PF: GET /jwks
    PF-->>LA: JWKS
  end

  note right of LA: Validate JWT (alg, kid, sig,<br/>iss, aud, exp/nbf) +<br/>check scopes vs permissions.yaml

  LA-->>AG: Policy (Allow/Deny) + context

  alt Allow
    AG-->>C: Backend response
  else Deny
    AG-->>C: 401 / 403
  end
```

# Configuration

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
