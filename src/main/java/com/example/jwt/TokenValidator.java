package com.example.jwt;

import com.example.lambda.exception.ErrorCodeType;
import com.example.lambda.exception.UnauthorizedException;
import com.nimbusds.jose.JOSEException;
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
 * Routes a raw JWT to the correct per-issuer processor and maps Nimbus exceptions
 * to UnauthorizedException (401). Infrastructure errors propagate as-is (500).
 */
@Slf4j
public final class TokenValidator {

    private static final String SCOPE_CLAIM = "scope";
    private static final String EXPIRED_INDICATOR = "Expired";

    private TokenValidator() {}

    /**
     * Parses the JWT, resolves the issuer's processor, and runs full validation.
     * Throws UnauthorizedException on client errors; lets infra errors (JWKS, crypto) propagate.
     */
    public static JWTClaimsSet validate(
            String rawToken,
            Map<String, ConfigurableJWTProcessor<SecurityContext>> processors) throws JOSEException {

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
            if (message != null && message.contains(EXPIRED_INDICATOR)) {
                throw new UnauthorizedException(ErrorCodeType.COMMON_EXPIRED_ACCESS_TOKEN,
                    "Token expired: " + message);
            }
            throw new UnauthorizedException(ErrorCodeType.COMMON_INVALID_CREDENTIALS,
                "Token validation failed: " + message);
        }
    }

    /** Extracts scopes from the "scope" claim (space-delimited string). */
    public static List<String> extractScopes(JWTClaimsSet claims) {
        Object scopeValue = claims.getClaim(SCOPE_CLAIM);
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
