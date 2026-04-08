package com.example.lambda.exception;

/**
 * Thrown when the incoming API Gateway event is malformed (e.g. null, missing required fields).
 * Results in a 500 Internal Server Error.
 */
public class MalformedEventException extends AuthorizerException {
    public MalformedEventException(String message) {
        super(ErrorCodeType.COMMON_INTERNAL_ERROR, message);
    }
}
