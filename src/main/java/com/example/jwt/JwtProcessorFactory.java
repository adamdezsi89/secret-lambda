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
 * Builds a per-issuer map of configured {@link ConfigurableJWTProcessor} instances from the
 * runtime {@link AcceptedIssuers} configuration and pre-built JWK sources.
 *
 * <p>Each processor is independently configured with:
 * <ul>
 *   <li><b>Type verification</b> — accepts {@code "at+jwt"} (RFC 9068 access tokens),
 *       {@code "JWT"} (legacy plain type), and tokens with no {@code typ} header (common in
 *       enterprise IdPs such as PingFederate). Cross-JWT type confusion attacks are therefore
 *       prevented without breaking real-world compatibility.</li>
 *   <li><b>Algorithm restriction</b> — only the algorithms declared in
 *       {@link AcceptedIssuers.Issuer#getAcceptedAlgorithms()} are accepted; any other algorithm
 *       in the JWT header is rejected before key selection.</li>
 *   <li><b>Key selection</b> — {@link JWSAlgorithmFamilyJWSKeySelector} selects the correct
 *       public key from the issuer's JWK set based on algorithm family (RSA, EC, OKP), with
 *       optional {@code kid} header hint narrowing.</li>
 *   <li><b>Claims verification</b> — exact {@code iss} match; required presence of {@code sub}
 *       and {@code iat}; {@code exp} is always enforced by Nimbus regardless.</li>
 * </ul>
 *
 * <p>Processors are stateless and thread-safe once constructed; share them across Lambda
 * invocations for the lifetime of the container.
 */
@Slf4j
public final class JwtProcessorFactory {

    /**
     * Accepted {@code typ} header values.
     *
     * <p>{@code null} in this set tells Nimbus to also accept tokens where the {@code typ} header
     * is absent — required for PingFederate and many other enterprise IdPs that omit it.
     */
    private static final Set<JOSEObjectType> ACCEPTED_TYPES;
    static {
        Set<JOSEObjectType> types = new HashSet<>();
        types.add(new JOSEObjectType("at+jwt")); // RFC 9068 access tokens
        types.add(JOSEObjectType.JWT);            // legacy plain "JWT" type
        types.add(null);                          // absent typ header (e.g. PingFederate)
        ACCEPTED_TYPES = Collections.unmodifiableSet(types);
    }

    /**
     * Claims whose <em>presence</em> is required in every token, beyond the automatic
     * {@code exp} check that Nimbus always performs.
     */
    private static final Set<String> REQUIRED_CLAIMS = Set.of("sub", "iat");

    private JwtProcessorFactory() {}

    /**
     * Creates one {@link ConfigurableJWTProcessor} per accepted issuer.
     *
     * @param issuers    accepted issuers from the runtime configuration
     * @param jwkSources per-issuer JWK sources keyed by {@code iss}; produced by
     *                   {@link JwkSourceFactory#create}
     * @return unmodifiable map from {@code iss} claim value to its processor
     * @throws IllegalArgumentException if an issuer has no corresponding JWK source or
     *                                  an empty / unrecognised algorithm list
     */
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

    /**
     * Parses algorithm name strings to {@link JWSAlgorithm} objects.
     * Uses {@link JWSAlgorithm#parse} which resolves registered names (RS256, ES256, …)
     * as well as non-standard ones, so no algorithm name is silently dropped.
     */
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
