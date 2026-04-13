package com.example.lambda;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AuthorizerEventValidatorTest {

    private static final String ARN_PREFIX = "arn:aws:execute-api:eu-central-1:123456:abcdef/stage/GET/";

    @Test
    void wildcardMethodArn_noPathParams_returnsUnchanged() {
        String arn = ARN_PREFIX + "users";
        String result = AuthorizerEventValidator.wildcardMethodArn(arn, "/users");
        assertEquals(arn, result);
    }

    @Test
    void wildcardMethodArn_singlePathParam_replacedWithWildcard() {
        String arn = ARN_PREFIX + "users/abc123";
        String result = AuthorizerEventValidator.wildcardMethodArn(arn, "/users/{id}");
        assertEquals(ARN_PREFIX + "users/*", result);
    }

    @Test
    void wildcardMethodArn_multiplePathParams_allReplaced() {
        String arn = ARN_PREFIX + "users/abc123/orders/ord456";
        String result = AuthorizerEventValidator.wildcardMethodArn(arn, "/users/{userId}/orders/{orderId}");
        assertEquals(ARN_PREFIX + "users/*/orders/*", result);
    }

    @Test
    void wildcardMethodArn_mixedSegments_onlyParamsReplaced() {
        String arn = ARN_PREFIX + "api/v1/users/abc123/profile";
        String result = AuthorizerEventValidator.wildcardMethodArn(arn, "/api/v1/users/{id}/profile");
        assertEquals(ARN_PREFIX + "api/v1/users/*/profile", result);
    }
}
