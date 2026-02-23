package com.example;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayCustomAuthorizerEvent;
import com.example.testutils.ApiGatewayEventBuilder;
import com.example.testutils.LambdaContextBuilder;
import com.example.testutils.MockOidcServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class OidcAuthorizerHandlerTest {

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
    void handleRequest_shouldExecuteWithoutException() {
        // Arrange
        String pingfederateClientId = "ab-0123456789012345678901234";
        String issuer = "https://login.rbinternational.com";
        String signedJwt = mockOidcServer.createSignedAccessToken(pingfederateClientId, issuer, null);

        String methodArn = "arn:aws:execute-api:us-east-1:123456789012:test-api-id/test-stage/GET/test-resource";
        APIGatewayCustomAuthorizerEvent event = ApiGatewayEventBuilder.createTokenAuthorizerEvent(signedJwt, methodArn);

        Context context = LambdaContextBuilder.createContext("test-function", "1", "aws-request-id");

        OidcAuthorizerHandler handler = new OidcAuthorizerHandler();

        // Act & Assert
        assertDoesNotThrow(() -> handler.handleRequest(event, context));
    }
}
