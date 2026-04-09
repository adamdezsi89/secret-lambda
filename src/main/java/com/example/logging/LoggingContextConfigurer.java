package com.example.logging;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayCustomAuthorizerEvent;
import com.nimbusds.jwt.JWTClaimsSet;
import org.slf4j.MDC;

import java.util.Map;

/** Populates SLF4J MDC with request context for structured logging. */
public final class LoggingContextConfigurer {

    public static final String LOG_FIELD_IS_COLD_START = "isColdStart";
    public static final String LOG_FIELD_FUNCTION_NAME = "functionName";
    public static final String LOG_FIELD_FUNCTION_VERSION = "functionVersion";
    public static final String LOG_FIELD_REQUEST_ID = "X-Request-ID";
    public static final String LOG_FIELD_CORRELATION_ID = "X-Correlation-ID";
    public static final String LOG_FIELD_JWT_CLAIM_ISSUER = "jwtIss";
    public static final String LOG_FIELD_JWT_CLAIM_SUBJECT = "jwtSub";
    public static final String LOG_FIELD_JWT_CLAIM_CLIENT_ID = "jwtClientId";
    public static final String LOG_FIELD_BUSINESS_DOMAIN = "businessDomain";
    public static final String LOG_FIELD_COMPONENT_TYPE = "componentType";
    public static final String LOG_FIELD_COMPONENT_NAME = "componentName";

    private static final String ENV_BUSINESS_DOMAIN = "RICELAMBDA_LOGCONTEXT_BUSINESSDOMAIN";
    private static final String ENV_COMPONENT_TYPE = "RICELAMBDA_LOGCONTEXT_COMPONENT_TYPE";
    private static final String ENV_COMPONENT_NAME = "RICELAMBDA_LOGCONTEXT_COMPONENT_NAME";

    private LoggingContextConfigurer() {}

    /** Sets request-scoped MDC: cold start flag, Lambda context, RICE headers, static labels. */
    public static void setRequestContext(
            boolean coldStart, APIGatewayCustomAuthorizerEvent event, Context context) {

        MDC.put(LOG_FIELD_IS_COLD_START, String.valueOf(coldStart));

        if (context != null) {
            MDC.put(LOG_FIELD_FUNCTION_NAME, context.getFunctionName());
            MDC.put(LOG_FIELD_FUNCTION_VERSION, context.getFunctionVersion());
        }

        if (event != null) {
            Map<String, String> headers = event.getHeaders();
            if (headers != null) {
                putHeaderCaseInsensitive(headers, LOG_FIELD_REQUEST_ID);
                putHeaderCaseInsensitive(headers, LOG_FIELD_CORRELATION_ID);
            }
        }

        putEnvIfPresent(LOG_FIELD_BUSINESS_DOMAIN, ENV_BUSINESS_DOMAIN);
        putEnvIfPresent(LOG_FIELD_COMPONENT_TYPE, ENV_COMPONENT_TYPE);
        putEnvIfPresent(LOG_FIELD_COMPONENT_NAME, ENV_COMPONENT_NAME);
    }

    /** Enriches MDC with JWT claims after token validation. */
    public static void setJwtContext(JWTClaimsSet claims) {
        if (claims == null) {
            return;
        }
        putIfNotNull(LOG_FIELD_JWT_CLAIM_ISSUER, claims.getIssuer());
        putIfNotNull(LOG_FIELD_JWT_CLAIM_SUBJECT, claims.getSubject());

        Object clientId = claims.getClaim("client_id");
        if (clientId != null) {
            MDC.put(LOG_FIELD_JWT_CLAIM_CLIENT_ID, clientId.toString());
        }
    }

    /** Clears all MDC entries. Call in a finally block at the end of each request. */
    public static void clear() {
        MDC.clear();
    }

    private static void putHeaderCaseInsensitive(Map<String, String> headers, String headerName) {
        String value = headers.get(headerName);
        if (value == null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(headerName)) {
                    value = entry.getValue();
                    break;
                }
            }
        }
        if (value != null) {
            MDC.put(headerName, value);
        }
    }

    private static void putIfNotNull(String key, String value) {
        if (value != null) {
            MDC.put(key, value);
        }
    }

    private static void putEnvIfPresent(String mdcKey, String envVar) {
        String value = System.getenv(envVar);
        if (value != null && !value.isBlank()) {
            MDC.put(mdcKey, value);
        }
    }
}
