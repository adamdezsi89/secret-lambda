package com.example.lambda.exception;

/** Malformed API Gateway event (null, wrong type) — propagates as 500. */
public class MalformedEventException extends AuthorizerException {
    public MalformedEventException(String message) {
        super(ErrorCodeType.COMMON_INTERNAL_ERROR, message);
    }
}
