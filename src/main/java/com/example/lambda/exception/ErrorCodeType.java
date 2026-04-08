package com.example.lambda.exception;

/** RICE-standardized error codes used for structured logging of authorization failures. */
public enum ErrorCodeType {
    COMMON_MISSING_CREDENTIALS,
    COMMON_INVALID_CREDENTIALS,
    COMMON_INVALID_ISSUER,
    COMMON_EXPIRED_ACCESS_TOKEN,
    COMMON_ACCESS_DENIED,
    COMMON_INTERNAL_ERROR
}
