package com.example.lambda.exception;

/**
 * Base exception for any authorizer-related failures.
 */
public abstract class AuthorizerException extends RuntimeException {
    protected AuthorizerException(String message) {
        super(message);
    }
}
