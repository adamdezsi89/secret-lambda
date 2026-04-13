package com.example;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayCustomAuthorizerEvent;
import com.example.lambda.exception.MalformedEventException;
import com.example.lambda.model.RestApiGwAuthorizerResponse;
import com.example.testutils.ApiGatewayEventBuilder;
import com.example.testutils.LambdaContextBuilder;
import com.example.testutils.MockOidcServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OidcAuthorizerHandlerTest {

    // Token related
    private static final String SUBJECT = "ab-0123456789012345678901234";
    private static final String ISSUER = "https://test-issuer.com";

    // Scoped endpoint: /test-endpoint-01 GET requires TEST-SCOPE-01
    private static final String SCOPED_RESOURCE = "/test-endpoint-01";
    private static final String SCOPED_METHOD = "GET";

    // Public endpoint: /test-endpoint-03 GET has no scopes
    private static final String PUBLIC_RESOURCE = "/test-endpoint-03";
    private static final String PUBLIC_METHOD = "GET";

    // Unconfigured endpoint
    private static final String UNCONFIGURED_RESOURCE = "/nonexistent";
    private static final String UNCONFIGURED_METHOD = "GET";

    private static MockOidcServer mockOidcServer;
    private static OidcAuthorizerHandler handler;

    @BeforeAll
    static void setUp() {
        mockOidcServer = new MockOidcServer();
        handler = new OidcAuthorizerHandler();
    }

    @AfterAll
    static void tearDown() {
        if (mockOidcServer != null) {
            mockOidcServer.stop();
        }
    }

    // ── Event validation ────────────────────────────────────────────────────

    @Test
    void handleRequest_withTokenTypeEvent_shouldThrowMalformedEventException() {
        String signedJwt = mockOidcServer.createSignedAccessToken(SUBJECT, ISSUER, null);
        APIGatewayCustomAuthorizerEvent event = ApiGatewayEventBuilder.createTokenAuthorizerEvent(
            signedJwt, SCOPED_METHOD, SCOPED_RESOURCE, Collections.emptyMap());
        Context context = createContext();

        assertThrows(MalformedEventException.class, () -> handler.handleRequest(event, context));
    }

    @Disabled
    @Test
    void handleRequest_withNullEvent_shouldThrowMalformedEventException() {
        Context context = createContext();
        assertThrows(MalformedEventException.class, () -> handler.handleRequest(null, context));
    }

    // ── Public endpoint ─────────────────────────────────────────────────────

    @Test
    void handleRequest_publicEndpoint_withoutToken_shouldAllow() {
        APIGatewayCustomAuthorizerEvent event = createRequestEvent(null, PUBLIC_METHOD, PUBLIC_RESOURCE);
        Context context = createContext();

        RestApiGwAuthorizerResponse response = handler.handleRequest(event, context);

        assertNotNull(response);
        assertEquals("anonymous", response.principalId());
        assertAllow(response);
    }

    @Test
    void handleRequest_publicEndpoint_withValidToken_shouldAllowWithSubjectPrincipal() {
        String signedJwt = mockOidcServer.createSignedAccessToken(SUBJECT, ISSUER, null);
        APIGatewayCustomAuthorizerEvent event = createRequestEvent(signedJwt, PUBLIC_METHOD, PUBLIC_RESOURCE);
        Context context = createContext();

        RestApiGwAuthorizerResponse response = handler.handleRequest(event, context);

        assertNotNull(response);
        assertEquals(SUBJECT, response.principalId());
        assertAllow(response);
    }

    @Test
    void handleRequest_publicEndpoint_withInvalidToken_shouldThrowUnauthorized() {
        APIGatewayCustomAuthorizerEvent event = createRequestEvent("invalid.jwt.token", PUBLIC_METHOD, PUBLIC_RESOURCE);
        Context context = createContext();

        RuntimeException ex = assertThrows(RuntimeException.class, () -> handler.handleRequest(event, context));
        assertEquals("Unauthorized", ex.getMessage());
    }

    // ── Unconfigured endpoint ───────────────────────────────────────────────

    @Test
    void handleRequest_unconfiguredEndpoint_shouldDeny() {
        String signedJwt = mockOidcServer.createSignedAccessToken(SUBJECT, ISSUER, null, "TEST-SCOPE-01");
        APIGatewayCustomAuthorizerEvent event = createRequestEvent(signedJwt, UNCONFIGURED_METHOD, UNCONFIGURED_RESOURCE);
        Context context = createContext();

        RestApiGwAuthorizerResponse response = handler.handleRequest(event, context);

        assertNotNull(response);
        assertDeny(response);
    }

    // ── Scoped endpoint: missing token ──────────────────────────────────────

    @Test
    void handleRequest_scopedEndpoint_withoutToken_shouldThrowUnauthorized() {
        APIGatewayCustomAuthorizerEvent event = createRequestEvent(null, SCOPED_METHOD, SCOPED_RESOURCE);
        Context context = createContext();

        RuntimeException ex = assertThrows(RuntimeException.class, () -> handler.handleRequest(event, context));
        assertEquals("Unauthorized", ex.getMessage());
    }

    // ── Scoped endpoint: valid token with matching scopes ───────────────────

    @Test
    void handleRequest_scopedEndpoint_withMatchingScopes_shouldAllow() {
        String signedJwt = mockOidcServer.createSignedAccessToken(SUBJECT, ISSUER, null, "TEST-SCOPE-01");
        APIGatewayCustomAuthorizerEvent event = createRequestEvent(signedJwt, SCOPED_METHOD, SCOPED_RESOURCE);
        Context context = createContext();

        RestApiGwAuthorizerResponse response = handler.handleRequest(event, context);

        assertNotNull(response);
        assertEquals(SUBJECT, response.principalId());
        assertAllow(response);
    }

    @Test
    void handleRequest_scopedEndpoint_withSupersetScopes_shouldAllow() {
        String signedJwt = mockOidcServer.createSignedAccessToken(SUBJECT, ISSUER, null, "OTHER TEST-SCOPE-01 ANOTHER");
        APIGatewayCustomAuthorizerEvent event = createRequestEvent(signedJwt, SCOPED_METHOD, SCOPED_RESOURCE);
        Context context = createContext();

        RestApiGwAuthorizerResponse response = handler.handleRequest(event, context);

        assertNotNull(response);
        assertEquals(SUBJECT, response.principalId());
        assertAllow(response);
    }

    // ── Scoped endpoint: valid token with non-matching scopes ───────────────

    @Test
    void handleRequest_scopedEndpoint_withNonMatchingScopes_shouldDeny() {
        String signedJwt = mockOidcServer.createSignedAccessToken(SUBJECT, ISSUER, null, "UNRELATED-SCOPE");
        APIGatewayCustomAuthorizerEvent event = createRequestEvent(signedJwt, SCOPED_METHOD, SCOPED_RESOURCE);
        Context context = createContext();

        RestApiGwAuthorizerResponse response = handler.handleRequest(event, context);

        assertNotNull(response);
        assertDeny(response);
    }

    // ── Scoped endpoint: expired token ──────────────────────────────────────

    @Test
    void handleRequest_scopedEndpoint_withExpiredToken_shouldThrowUnauthorized() {
        String expiredJwt = mockOidcServer.createExpiredAccessToken(SUBJECT, ISSUER, null);
        APIGatewayCustomAuthorizerEvent event = createRequestEvent(expiredJwt, SCOPED_METHOD, SCOPED_RESOURCE);
        Context context = createContext();

        RuntimeException ex = assertThrows(RuntimeException.class, () -> handler.handleRequest(event, context));
        assertEquals("Unauthorized", ex.getMessage());
    }

    // ── Scoped endpoint: unknown issuer ─────────────────────────────────────

    @Test
    void handleRequest_scopedEndpoint_withUnknownIssuer_shouldThrowUnauthorized() {
        String signedJwt = mockOidcServer.createSignedAccessToken(SUBJECT, "https://unknown-issuer.com", null, "TEST-SCOPE-01");
        APIGatewayCustomAuthorizerEvent event = createRequestEvent(signedJwt, SCOPED_METHOD, SCOPED_RESOURCE);
        Context context = createContext();

        RuntimeException ex = assertThrows(RuntimeException.class, () -> handler.handleRequest(event, context));
        assertEquals("Unauthorized", ex.getMessage());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static APIGatewayCustomAuthorizerEvent createRequestEvent(String token, String method, String resource) {
        return ApiGatewayEventBuilder.createRequestAuthorizerEvent(token, method, resource, Collections.emptyMap(), Collections.emptyMap());
    }

    private static Context createContext() {
        return LambdaContextBuilder.createContext(
            ApiGatewayEventBuilder.LAMBDA_NAME, ApiGatewayEventBuilder.LAMBDA_VERSION, UUID.randomUUID().toString());
    }

    private static void assertAllow(RestApiGwAuthorizerResponse response) {
        RestApiGwAuthorizerResponse.Statement stmt = response.policyDocument().Statement().get(0);
        assertEquals("Deny", stmt.Effect());
        assertNotNull(stmt.NotResource(), "Allow policy should use NotResource");
        assertNull(stmt.Resource(), "Allow policy should not have Resource");
    }

    private static void assertDeny(RestApiGwAuthorizerResponse response) {
        RestApiGwAuthorizerResponse.Statement stmt = response.policyDocument().Statement().get(0);
        assertEquals("Deny", stmt.Effect());
        assertNotNull(stmt.Resource(), "Deny policy should use Resource");
        assertNull(stmt.NotResource(), "Deny policy should not have NotResource");
    }
}
