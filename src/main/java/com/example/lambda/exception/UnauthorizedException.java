package com.example.lambda.exception;

/**
 * Thrown when authentication fails. Results in a 401 Unauthorized response.
 *
 * <p>Expected error codes:
 * <ul>
 *   <li>{@link ErrorCodeType#COMMON_MISSING_CREDENTIALS} — no Bearer token in the request</li>
 *   <li>{@link ErrorCodeType#COMMON_INVALID_CREDENTIALS} — malformed JWT or signature verification failed</li>
 *   <li>{@link ErrorCodeType#COMMON_INVALID_ISSUER} — token issuer not in the accepted issuers list</li>
 *   <li>{@link ErrorCodeType#COMMON_EXPIRED_ACCESS_TOKEN} — token has expired</li>
 * </ul>
 */
public class UnauthorizedException extends AuthorizerException {
    public UnauthorizedException(ErrorCodeType errorCode, String message) {
        super(errorCode, message);
    }
}
