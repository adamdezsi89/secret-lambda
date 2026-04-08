package com.example.lambda.exception;

import lombok.Getter;

/** Base exception for authorizer failures. Carries an ErrorCodeType for structured logging. */
@Getter
public abstract class AuthorizerException extends RuntimeException {
    private final ErrorCodeType errorCode;

    protected AuthorizerException(ErrorCodeType errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
