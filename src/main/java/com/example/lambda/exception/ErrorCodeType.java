package com.example.lambda.exception;

/**
 * Standardized error codes for authorization failures.
 *
 * @see <a href="https://wiki.rbinternational.corp/confluence/display/RICE/API+response+and+error+codes">RICE error codes</a>
 */
public enum ErrorCodeType {
    COMMON_MISSING_CREDENTIALS,
    COMMON_INVALID_CREDENTIALS,
    COMMON_INVALID_ISSUER,
    COMMON_EXPIRED_ACCESS_TOKEN,
    COMMON_ACCESS_DENIED,
    COMMON_INTERNAL_ERROR
}
