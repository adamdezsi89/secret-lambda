package com.example.lambda;

import com.amazonaws.services.lambda.runtime.events.APIGatewayCustomAuthorizerEvent;
import com.example.lambda.exception.MalformedEventException;
import lombok.extern.slf4j.Slf4j;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Validates the API Gateway authorizer event and extracts the Bearer token. */
@Slf4j
public final class AuthorizerEventValidator {

    private static final String EVENT_TYPE_REQUEST = "REQUEST";
    private static final String EVENT_TYPE_TOKEN = "TOKEN";
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final Pattern PATH_PARAM_PATTERN = Pattern.compile("\\{[^}]+}");

    private AuthorizerEventValidator() {}

    /** Ensures the event is non-null and has type REQUEST. */
    public static void validateEvent(APIGatewayCustomAuthorizerEvent event) {
        if (event == null) {
            throw new MalformedEventException("Event is null");
        }

        String type = event.getType();
        if (type == null) {
            throw new MalformedEventException("Event has no 'type' field");
        }

        if (EVENT_TYPE_TOKEN.equalsIgnoreCase(type)) {
            throw new MalformedEventException("TOKEN type is not supported, reconfigure API Gateway to use REQUEST type");
        }

        if (!EVENT_TYPE_REQUEST.equalsIgnoreCase(type)) {
            throw new MalformedEventException("Unsupported event type: " + type);
        }
    }

    /**
     * Replaces path parameter segments ({id}, {name}, etc.) in the method ARN with wildcards.
     * Returns the ARN unchanged if the resource has no path parameters.
     */
    public static String wildcardMethodArn(String methodArn, String resource) {
        if (!resource.contains("{")) {
            return methodArn;
        }
        // ARN: arn:aws:execute-api:region:account:api-id/stage/METHOD/path
        int lastColon = methodArn.lastIndexOf(':');
        String apiPart = methodArn.substring(lastColon + 1);
        String[] parts = apiPart.split("/", 4);
        String wildcardedPath = resource.startsWith("/") ? resource.substring(1) : resource;
        wildcardedPath = PATH_PARAM_PATTERN.matcher(wildcardedPath).replaceAll("*");
        return methodArn.substring(0, lastColon + 1)
                + parts[0] + "/" + parts[1] + "/" + parts[2] + "/" + wildcardedPath;
    }

    /** Extracts the Bearer token from the Authorization header. Strips the "Bearer " prefix. */
    public static Optional<String> extractBearerToken(APIGatewayCustomAuthorizerEvent event) {
        String token = null;

        Map<String, String> headers = event.getHeaders();
        if (headers != null) {
            token = headers.get(AUTHORIZATION_HEADER);
            if (token == null) {
                token = headers.get(AUTHORIZATION_HEADER.toLowerCase());
            }
        }

        if (token != null && token.startsWith(BEARER_PREFIX)) {
            token = token.substring(BEARER_PREFIX.length());
        }

        LOG.debug("Bearer token {}", token != null ? "found" : "absent");

        return Optional.ofNullable(token);
    }
}
