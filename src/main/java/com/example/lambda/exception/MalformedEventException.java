package com.example.lambda.exception;

/**
 * Thrown when the incoming API Gateway event is malformed (e.g. null, missing required fields).
 */
public class MalformedEventException extends AuthorizerException {
    public MalformedEventException(String message) {
        super(message);
    }
}
