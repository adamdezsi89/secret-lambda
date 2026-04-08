package com.example.lambda;

import com.amazonaws.services.lambda.runtime.events.APIGatewayCustomAuthorizerEvent;
import com.example.lambda.exception.MalformedEventException;
import lombok.extern.slf4j.Slf4j;
import java.util.Map;
import java.util.Optional;

/**
 * Utility class for extracting the authorization token from API Gateway Custom Authorizer events.
 */
@Slf4j
public final class AuthorizerEventTokenExtractor {

    private static final String BEARER_PREFIX = "Bearer ";

    private AuthorizerEventTokenExtractor() {}

    /**
     * Extracts the token from the provided API Gateway Custom Authorizer event.
     * Supports both TOKEN and REQUEST event types.
     * Strips the "Bearer " prefix if present.
     *
     * @param event the authorizer event
     * @return an Optional containing the extracted token, or empty if valid event but no token found
     * @throws MalformedEventException if the event is null or has no type
     */
    public static Optional<String> extractToken(APIGatewayCustomAuthorizerEvent event) {
        if (event == null) {
            LOG.error("Authorizer event is null");
            throw new MalformedEventException("APIGatewayCustomAuthorizerEvent must not be null");
        }

        String type = event.getType();
        if (type == null) {
            LOG.error("Authorizer event has no 'type' field: {}", event);
            throw new MalformedEventException("Authorizer event must have a 'type' field (TOKEN or REQUEST)");
        }

        if ("TOKEN".equalsIgnoreCase(type)) {
            LOG.error("TOKEN type event received, but only REQUEST type is supported.");
            throw new MalformedEventException("Only 'REQUEST' type authorizer events are supported. " +
                "Please reconfigure the API Gateway Authorizer to use 'REQUEST' type instead of 'TOKEN' type.");
        }

        if (!"REQUEST".equalsIgnoreCase(type)) {
            LOG.error("Unsupported authorizer event type: {}", type);
            throw new MalformedEventException("Unsupported authorizer event type: '" + type + "'. Only 'REQUEST' is supported.");
        }

        LOG.debug("Extracting token from REQUEST event.");
        String token = null;

        Map<String, String> headers = event.getHeaders();
        if (headers != null) {
            // Try case-insensitive Authorization header
            token = headers.get("Authorization");
            if (token == null) {
                token = headers.get("authorization");
            }
        }

        if (token != null) {
            if (token.startsWith(BEARER_PREFIX)) {
                LOG.trace("Found 'Bearer ' prefix, stripping it.");
                token = token.substring(BEARER_PREFIX.length());
            }
            LOG.debug("Successfully extracted token from {} event", type);
        } else {
            LOG.info("No authorization token found in {} event", type);
        }

        return Optional.ofNullable(token);
    }
}
