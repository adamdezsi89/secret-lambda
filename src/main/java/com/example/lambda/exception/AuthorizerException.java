package com.example.lambda.exception;

import lombok.Getter;

/**
 * Base exception for any authorizer-related failures.
 * Carries a standardized {@link ErrorCodeType} for structured logging and diagnostics.
 */
@Getter
public abstract class AuthorizerException extends RuntimeException {
    private final ErrorCodeType errorCode;

    protected AuthorizerException(ErrorCodeType errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
