package com.example.lambda.exception;

/**
 * Thrown when a request is authenticated but not authorized. Results in a 403 Forbidden response (Deny policy).
 *
 * <p>Covers two cases (distinguished by log message, same error code):
 * <ul>
 *   <li>Operation not configured in {@code permissions.yaml}</li>
 *   <li>Valid token but its scopes do not intersect with the required scopes</li>
 * </ul>
 */
public class AccessDeniedException extends AuthorizerException {
    public AccessDeniedException(String message) {
        super(ErrorCodeType.COMMON_ACCESS_DENIED, message);
    }
}
