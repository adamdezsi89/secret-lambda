package com.example.jwt;

import com.example.config.model.AcceptedIssuers;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.JWSAlgorithmFamilyJWSKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Builds a per-issuer map of JWT processors.
 *
 * Each processor verifies: type header (at+jwt, JWT, absent), algorithm restriction,
 * signature via JWK set, and claims (exact iss, required sub + iat, automatic exp).
 * Thread-safe and stateless once built — shared across Lambda invocations.
 */
@Slf4j
public final class JwtProcessorFactory {

    // null = absent typ header, needed for PingFederate and other IdPs that omit it.
    private static final Set<JOSEObjectType> ACCEPTED_TYPES;
    static {
        Set<JOSEObjectType> types = new HashSet<>();
        types.add(new JOSEObjectType("at+jwt")); // RFC 9068 access tokens
        types.add(JOSEObjectType.JWT);            // legacy plain "JWT" type
        types.add(null);                          // absent typ header (e.g. PingFederate)
        ACCEPTED_TYPES = Collections.unmodifiableSet(types);
    }

    // Beyond the automatic exp check that Nimbus always performs.
    private static final Set<String> REQUIRED_CLAIMS = Set.of("sub", "iat");

    private JwtProcessorFactory() {}

    /** Creates one JWT processor per accepted issuer, keyed by iss claim value. */
    public static Map<String, ConfigurableJWTProcessor<SecurityContext>> create(
            List<AcceptedIssuers.Issuer> issuers,
            Map<String, JWKSource<SecurityContext>> jwkSources) {

        Objects.requireNonNull(issuers, "issuers");
        Objects.requireNonNull(jwkSources, "jwkSources");

        Map<String, ConfigurableJWTProcessor<SecurityContext>> processors = new LinkedHashMap<>(issuers.size());
        for (AcceptedIssuers.Issuer issuer : issuers) {
            JWKSource<SecurityContext> jwkSource = jwkSources.get(issuer.getIss());
            if (jwkSource == null) {
                throw new IllegalArgumentException(
                        "No JWKSource found for issuer: " + issuer.getIss()
                        + ". Ensure JwkSourceFactory.create() was called with the same issuer list.");
            }
            processors.put(issuer.getIss(), buildForIssuer(issuer, jwkSource));
        }

        LOG.info("JWT processors configured for {} issuer(s): {}", processors.size(), processors.keySet());
        return Collections.unmodifiableMap(processors);
    }

    private static ConfigurableJWTProcessor<SecurityContext> buildForIssuer(
            AcceptedIssuers.Issuer issuer, JWKSource<SecurityContext> jwkSource) {

        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();

        // ── Type header ──────────────────────────────────────────────────────
        processor.setJWSTypeVerifier(new DefaultJOSEObjectTypeVerifier<>(ACCEPTED_TYPES));

        // ── Key selection ────────────────────────────────────────────────────
        // Restricts to the configured algorithms; selects the matching public key
        // from the JWK set by algorithm family + optional kid hint.
        JWSAlgorithm.Family algorithms = parseAlgorithms(issuer.getAcceptedAlgorithms(), issuer.getIss());
        processor.setJWSKeySelector(new JWSAlgorithmFamilyJWSKeySelector<>(algorithms, jwkSource));

        // ── Claims ───────────────────────────────────────────────────────────
        // Exact iss match prevents tokens from one IdP being accepted by another.
        // exp is always enforced by Nimbus; sub + iat are additionally required.
        processor.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(
                new JWTClaimsSet.Builder()
                        .issuer(issuer.getIss())
                        .build(),
                REQUIRED_CLAIMS
        ));

        LOG.debug("JWT processor configured: iss={}, algorithms={}", issuer.getIss(), algorithms);
        return processor;
    }

    private static JWSAlgorithm.Family parseAlgorithms(List<String> names, String iss) {
        if (names == null || names.isEmpty()) {
            throw new IllegalArgumentException(
                    "No accepted algorithms configured for issuer: " + iss);
        }
        JWSAlgorithm[] algs = names.stream()
                .map(JWSAlgorithm::parse)
                .toArray(JWSAlgorithm[]::new);
        return new JWSAlgorithm.Family(algs);
    }
}
