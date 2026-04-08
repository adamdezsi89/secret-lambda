package com.example.jwt;

import com.example.config.model.AcceptedIssuers;
import com.nimbusds.jose.jwk.source.JWKSetSourceWithHealthStatusReporting;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jose.util.health.HealthReportListener;
import com.nimbusds.jose.util.health.HealthStatus;
import lombok.extern.slf4j.Slf4j;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builds a per-issuer map of resilient JWK sources using the Nimbus enhanced retrieval stack:
 * rate limiting, cache with refresh-ahead, retry, outage tolerance, and health reporting.
 */
@Slf4j
public final class JwkSourceFactory {

    // ── Network ───────────────────────────────────────────────────────────────
    /** TCP connect timeout for each JWKS fetch. */
    private static final int CONNECT_TIMEOUT_MS = 2_000;
    /** Read timeout for each JWKS fetch. */
    private static final int READ_TIMEOUT_MS = 3_000;
    /** Response-body size cap; prevents memory exhaustion from oversized or hostile responses. */
    private static final int RESPONSE_SIZE_LIMIT_BYTES = 128 * 1_024;

    // ── Cache ─────────────────────────────────────────────────────────────────
    /** How long a successfully fetched JWKS is considered fresh. */
    private static final long CACHE_TTL_MS = Duration.ofHours(1).toMillis();
    /**
     * Remaining TTL at which a background refresh is triggered.
     * 30 s gives ample time to complete the refresh before callers encounter an expired entry.
     */
    private static final long CACHE_REFRESH_AHEAD_MS = Duration.ofSeconds(30).toMillis();

    // ── Rate limiting ─────────────────────────────────────────────────────────
    /** Minimum interval between consecutive calls to a single issuer's JWKS endpoint. */
    private static final long RATE_LIMIT_MIN_INTERVAL_MS = Duration.ofSeconds(30).toMillis();

    // ── Outage tolerance ──────────────────────────────────────────────────────
    /** Maximum age of a stale JWKS that may be served during an OIDC provider outage. */
    private static final long OUTAGE_TOLERANCE_TTL_MS = Duration.ofHours(4).toMillis();

    private JwkSourceFactory() {}

    /** Creates one resilient JWK source per accepted issuer, keyed by iss claim value. */
    public static Map<String, JWKSource<SecurityContext>> create(List<AcceptedIssuers.Issuer> issuers)
            throws MalformedURLException {
        Objects.requireNonNull(issuers, "issuers");

        DefaultResourceRetriever retriever = new DefaultResourceRetriever(
                CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, RESPONSE_SIZE_LIMIT_BYTES);

        Map<String, JWKSource<SecurityContext>> sources = new LinkedHashMap<>(issuers.size());
        for (AcceptedIssuers.Issuer issuer : issuers) {
            sources.put(issuer.getIss(), buildForIssuer(issuer, retriever));
        }

        LOG.info("JWK sources ready for {} issuer(s): {}", sources.size(), sources.keySet());
        return Collections.unmodifiableMap(sources);
    }

    private static JWKSource<SecurityContext> buildForIssuer(
            AcceptedIssuers.Issuer issuer, DefaultResourceRetriever retriever) throws MalformedURLException {

        URL jwksUrl = URI.create(issuer.getJwksUrl()).toURL();

        return JWKSourceBuilder
                .<SecurityContext>create(jwksUrl, retriever)
                .rateLimited(RATE_LIMIT_MIN_INTERVAL_MS)
                .cache(CACHE_TTL_MS, CACHE_REFRESH_AHEAD_MS)
                .retrying(true)
                .outageTolerant(OUTAGE_TOLERANCE_TTL_MS)
                .healthReporting(healthListener(issuer.getIss()))
                .build();
    }

    private static HealthReportListener<JWKSetSourceWithHealthStatusReporting<SecurityContext>, SecurityContext>
    healthListener(String iss) {
        return report -> {
            if (HealthStatus.HEALTHY.equals(report.getHealthStatus())) {
                LOG.debug("JWKS source recovered, iss={}", iss);
            } else {
                LOG.warn("JWKS source degraded, iss={}, status={}, cause={}",
                        iss,
                        report.getHealthStatus(),
                        report.getException() != null ? report.getException().getMessage() : "none");
            }
        };
    }
}
