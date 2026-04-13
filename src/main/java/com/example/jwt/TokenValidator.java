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
import java.time.Instant;
import java.util.Date;
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

    /** Clock skew tolerance for iat check, matching Nimbus default for exp/nbf. */
    private static final long MAX_CLOCK_SKEW_SECONDS = 60;

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

        // 3. Full validation: type, algorithm, signature, claims (iss, sub, iat, jti, exp)
        // RemoteKeySourceException and JOSEException propagate as-is (500)
        try {
            JWTClaimsSet claims = processor.process(jwt, null);
            rejectFutureIat(claims);
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

    /** Rejects tokens with iat in the future (beyond clock skew tolerance). */
    private static void rejectFutureIat(JWTClaimsSet claims) {
        Date iat = claims.getIssueTime();
        if (iat == null) {
            return;
        }
        Instant latestAcceptable = Instant.now().plusSeconds(MAX_CLOCK_SKEW_SECONDS);
        if (iat.toInstant().isAfter(latestAcceptable)) {
            throw new UnauthorizedException(ErrorCodeType.COMMON_INVALID_CREDENTIALS,
                "Token issued in the future: iat=%s".formatted(iat.toInstant()));
        }
    }

    /** Parses JWT claims without signature validation. Returns null on malformed tokens. */
    public static JWTClaimsSet parseClaimsUnverified(String rawToken) {
        try {
            return SignedJWT.parse(rawToken).getJWTClaimsSet();
        } catch (ParseException e) {
            LOG.debug("Cannot parse JWT claims for logging: {}", e.getMessage());
            return null;
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
