package com.example.lambda.exception;

/** Authentication failure — maps to 401 via throw "Unauthorized". */
public class UnauthorizedException extends AuthorizerException {
    public UnauthorizedException(ErrorCodeType errorCode, String message) {
        super(errorCode, message);
    }
}
