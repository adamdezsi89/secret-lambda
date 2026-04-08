package com.example;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayCustomAuthorizerEvent;
import com.example.lambda.exception.MalformedEventException;
import com.example.testutils.ApiGatewayEventBuilder;
import com.example.testutils.LambdaContextBuilder;
import com.example.testutils.MockOidcServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OidcAuthorizerHandlerTest {

    // Token related
    private static final String PINGFEDERATE_CLIENT_ID = "ab-0123456789012345678901234";
    private static final String ISSUER = "https://test-issuer.com";

    // Request related
    private static final String RESOURCE = "/example-resource/{example-path-param}/child-resource";
    private static final String HTTP_METHOD = "POST";
    private static final Map<String, String> PATH_PARAMS = Map.of("example-path-param", "path param value");
    private static final Map<String, String> QUERY_PARAMS = Map.of("a", "20", "b", "30");

    private static MockOidcServer mockOidcServer;

    @BeforeAll
    static void startMockServer() {
        mockOidcServer = new MockOidcServer();
    }

    @AfterAll
    static void stopMockServer() {
        if (mockOidcServer != null) {
            mockOidcServer.stop();
        }
    }

    @Test
    void handleRequest_withTokenTypeEvent_shouldThrowMalformedEventException() {
        // Arrange
        String signedJwt = mockOidcServer.createSignedAccessToken(PINGFEDERATE_CLIENT_ID, ISSUER, null);

        APIGatewayCustomAuthorizerEvent event = ApiGatewayEventBuilder.createTokenAuthorizerEvent(signedJwt, HTTP_METHOD, RESOURCE, PATH_PARAMS);

        Context context = LambdaContextBuilder.createContext(ApiGatewayEventBuilder.LAMBDA_NAME, ApiGatewayEventBuilder.LAMBDA_VERSION, UUID.randomUUID().toString());

        OidcAuthorizerHandler handler = new OidcAuthorizerHandler();

        // Act & Assert
        assertThrows(MalformedEventException.class, () -> handler.handleRequest(event, context));
    }

    @Test
    void handleRequest_withRequestTypeEvent_shouldExecuteWithoutException() {
        // Arrange
        String signedJwt = mockOidcServer.createSignedAccessToken(PINGFEDERATE_CLIENT_ID, ISSUER, null);

        APIGatewayCustomAuthorizerEvent event = ApiGatewayEventBuilder.createRequestAuthorizerEvent(signedJwt, HTTP_METHOD, RESOURCE, PATH_PARAMS, QUERY_PARAMS);

        Context context = LambdaContextBuilder.createContext(ApiGatewayEventBuilder.LAMBDA_NAME, ApiGatewayEventBuilder.LAMBDA_VERSION, event.getRequestContext().getRequestId());

        OidcAuthorizerHandler handler = new OidcAuthorizerHandler();

        // Act & Assert
        assertDoesNotThrow(() -> handler.handleRequest(event, context));
    }

    @Test
    void handleRequest_withMissingToken_shouldReturnDenyPolicy() {
        // Arrange
        APIGatewayCustomAuthorizerEvent event = ApiGatewayEventBuilder.createRequestAuthorizerEvent(null, HTTP_METHOD, RESOURCE, PATH_PARAMS, QUERY_PARAMS);

        Context context = LambdaContextBuilder.createContext(ApiGatewayEventBuilder.LAMBDA_NAME, ApiGatewayEventBuilder.LAMBDA_VERSION, UUID.randomUUID().toString());

        OidcAuthorizerHandler handler = new OidcAuthorizerHandler();

        // Act
        com.example.lambda.model.RestApiGwAuthorizerResponse response = handler.handleRequest(event, context);

        // Assert
        assertNotNull(response);
        assertEquals("anonymous", response.principalId());
        assertEquals("Deny", response.policyDocument().Statement().get(0).Effect());
        assertEquals("*", response.policyDocument().Statement().get(0).Resource().get(0));
    }

    @Disabled
    @Test
    void handleRequest_withNullEvent_shouldThrowMalformedEventException() {
        // Arrange
        Context context = LambdaContextBuilder.createContext(ApiGatewayEventBuilder.LAMBDA_NAME, ApiGatewayEventBuilder.LAMBDA_VERSION, UUID.randomUUID().toString());
        OidcAuthorizerHandler handler = new OidcAuthorizerHandler();

        // Act & Assert
        assertThrows(MalformedEventException.class, () -> handler.handleRequest(null, context));
    }
}
