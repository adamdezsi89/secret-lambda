package com.example.lambda.exception;

/** Authorization failure — maps to 403 via Deny policy. */
public class AccessDeniedException extends AuthorizerException {
    public AccessDeniedException(String message) {
        super(ErrorCodeType.COMMON_ACCESS_DENIED, message);
    }
}
