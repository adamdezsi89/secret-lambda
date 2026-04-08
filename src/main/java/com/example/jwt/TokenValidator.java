package com.example.jwt;

import com.example.lambda.exception.ErrorCodeType;
import com.example.lambda.exception.UnauthorizedException;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import lombok.extern.slf4j.Slf4j;

import java.text.ParseException;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates JWT validation by routing tokens to the correct per-issuer processor
 * and mapping Nimbus exceptions to the authorizer exception hierarchy.
 *
 * <p>Client errors (bad token) become {@link UnauthorizedException} (401).
 * Infrastructure errors (JWKS unreachable, crypto failures) propagate as-is (500).
 */
@Slf4j
public final class TokenValidator {

    private TokenValidator() {}

    /**
     * Validates a raw JWT string against the configured processors.
     *
     * @param rawToken   the serialized JWT (without "Bearer " prefix)
     * @param processors per-issuer processors keyed by {@code iss} claim value
     * @return the validated claims set
     * @throws UnauthorizedException if the token is malformed, expired, from an unknown issuer,
     *                               or fails signature/claims verification
     * @throws com.nimbusds.jose.RemoteKeySourceException if the JWKS endpoint is unreachable (propagates as 500)
     * @throws com.nimbusds.jose.JOSEException            if a crypto operation fails (propagates as 500)
     */
    public static JWTClaimsSet validate(
            String rawToken,
            Map<String, ConfigurableJWTProcessor<SecurityContext>> processors) throws Exception {

        // 1. Parse JWT (unverified) to peek at the issuer claim for processor routing
        SignedJWT jwt;
        try {
            jwt = SignedJWT.parse(rawToken);
        } catch (ParseException e) {
            throw new UnauthorizedException(ErrorCodeType.COMMON_INVALID_CREDENTIALS,
                "Malformed JWT: " + e.getMessage());
        }

        String issuer;
        try {
            issuer = jwt.getJWTClaimsSet().getIssuer();
        } catch (ParseException e) {
            throw new UnauthorizedException(ErrorCodeType.COMMON_INVALID_CREDENTIALS,
                "Cannot parse JWT claims: " + e.getMessage());
        }

        // 2. Look up the processor for this issuer
        if (issuer == null) {
            throw new UnauthorizedException(ErrorCodeType.COMMON_INVALID_CREDENTIALS,
                "JWT has no 'iss' claim");
        }

        ConfigurableJWTProcessor<SecurityContext> processor = processors.get(issuer);
        if (processor == null) {
            throw new UnauthorizedException(ErrorCodeType.COMMON_INVALID_ISSUER,
                "Issuer '%s' is not in the accepted issuers list".formatted(issuer));
        }

        // 3. Full validation: type, algorithm, signature, claims (iss, sub, iat, exp)
        // RemoteKeySourceException and JOSEException propagate as-is (500)
        try {
            JWTClaimsSet claims = processor.process(jwt, null);
            LOG.debug("Token validated: iss={}, sub={}", claims.getIssuer(), claims.getSubject());
            return claims;
        } catch (BadJOSEException e) {
            String message = e.getMessage();
            if (message != null && message.contains("Expired")) {
                throw new UnauthorizedException(ErrorCodeType.COMMON_EXPIRED_ACCESS_TOKEN,
                    "Token expired: " + message);
            }
            throw new UnauthorizedException(ErrorCodeType.COMMON_INVALID_CREDENTIALS,
                "Token validation failed: " + message);
        }
    }

    /**
     * Extracts scopes from the validated JWT claims.
     * Expects the {@code scope} claim as a space-delimited string (OAuth2 standard).
     *
     * @param claims validated claims set
     * @return list of scope strings, empty if no scope claim present
     */
    public static List<String> extractScopes(JWTClaimsSet claims) {
        Object scopeValue = claims.getClaim("scope");
        if (scopeValue == null) {
            return List.of();
        }
        String scopeStr = scopeValue.toString().trim();
        if (scopeStr.isEmpty()) {
            return List.of();
        }
        return List.of(scopeStr.split(" "));
    }
}
