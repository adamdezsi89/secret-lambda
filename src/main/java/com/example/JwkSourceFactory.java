package com.example;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSetSourceWithHealthStatusReporting;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.jwk.source.RateLimitedJWKSetSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jose.util.events.EventListener;
import com.nimbusds.jose.util.health.HealthReportListener;
import com.nimbusds.jose.util.health.HealthStatus;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Duration;
import java.util.Objects;

/**
 * Factory for Nimbus JWKSource with "enhanced JWK retrieval":
 *  - HTTPS retrieval with strict timeouts + response size limit
 *  - Rate limiting
 *  - Cache + refresh-ahead
 *  - Retry
 *  - Outage tolerance
 *  - Optional local immutable failover (classpath JWKS JSON)
 *  - Health status reporting hook
 */
public final class JwkSourceFactory {

    private JwkSourceFactory() {}

    public static final class Config {
        public URL primaryJwksUrl;

        /** Example: "/jwks-backup.json" (must be a JWKS JSON, not a single JWK). Nullable. */
        public String localJwksJsonClasspathResource;

        // Network hardening
        public Duration connectTimeout = Duration.ofSeconds(2);
        public Duration readTimeout = Duration.ofSeconds(2);
        public int responseSizeLimitBytes = 128 * 1024;

        // Enhanced retrieval knobs
        public Duration rateLimitMinInterval = Duration.ofSeconds(10);
        public Duration cacheTtl = Duration.ofMinutes(15);
        public Duration refreshTimeout = Duration.ofSeconds(3);
        public Duration outageTtl = Duration.ofHours(2);

        public boolean retrying = true;

        /**
         * Optional: observe health status changes; wire into your logger/metrics.
         * If null, a minimal stderr logger is used.
         */
        public HealthReportListener<JWKSetSourceWithHealthStatusReporting<SecurityContext>, SecurityContext>
            healthReportListener;

        /**
         * Optional: observe rate limiting events.
         * If null, rate limiting is enabled without event callbacks.
         */
        public EventListener<RateLimitedJWKSetSource<SecurityContext>, SecurityContext>
            rateLimitEventListener;
    }

    public static JWKSource<SecurityContext> create(final Config cfg) throws IOException, ParseException {
        Objects.requireNonNull(cfg, "cfg");
        Objects.requireNonNull(cfg.primaryJwksUrl, "cfg.primaryJwksUrl");
        requirePositive(cfg.responseSizeLimitBytes, "cfg.responseSizeLimitBytes");

        final DefaultResourceRetriever retriever = new DefaultResourceRetriever(
            toMillis(cfg.connectTimeout, "cfg.connectTimeout"),
            toMillis(cfg.readTimeout, "cfg.readTimeout"),
            cfg.responseSizeLimitBytes
        );

        final HealthReportListener<JWKSetSourceWithHealthStatusReporting<SecurityContext>, SecurityContext> healthListener =
            cfg.healthReportListener != null ? cfg.healthReportListener : defaultHealthListener();

        JWKSourceBuilder<SecurityContext> builder = JWKSourceBuilder
            .create(cfg.primaryJwksUrl, retriever)
            .healthReporting(healthListener);

        // Rate limiting (with optional event listener)
        if (cfg.rateLimitEventListener != null) {
            builder = builder.rateLimited(cfg.rateLimitMinInterval.toMillis(), cfg.rateLimitEventListener);
        } else {
            builder = builder.rateLimited(cfg.rateLimitMinInterval.toMillis());
        }

        // Cache + refresh-ahead
        builder = builder.cache(cfg.cacheTtl.toMillis(), cfg.refreshTimeout.toMillis());

        // Retry + outage tolerance
        builder = builder.retrying(cfg.retrying).outageTolerant(cfg.outageTtl.toMillis());

        // Optional local immutable failover
        if (cfg.localJwksJsonClasspathResource != null) {
            final JWKSet local = loadJwkSetFromClasspath(cfg.localJwksJsonClasspathResource);
            builder = builder.failover(new ImmutableJWKSet<>(local));
        }

        return builder.build();
    }

    private static HealthReportListener<JWKSetSourceWithHealthStatusReporting<SecurityContext>, SecurityContext>
    defaultHealthListener() {
        return report -> {
            if (!HealthStatus.HEALTHY.equals(report.getHealthStatus())) {
                System.err.println("[JWKS] health=" + report.getHealthStatus()
                    + " time=" + report.getTimestamp()
                    + " err=" + report.getException());
            }
        };
    }

    private static JWKSet loadJwkSetFromClasspath(final String classpathResource)
        throws IOException, ParseException {

        try (InputStream is = JwkSourceFactory.class.getResourceAsStream(classpathResource)) {
            if (is == null) {
                throw new IOException("Classpath resource not found: " + classpathResource);
            }
            final byte[] bytes = is.readAllBytes();
            final String json = new String(bytes, StandardCharsets.UTF_8);
            return JWKSet.parse(json);
        }
    }

    private static int toMillis(final Duration d, final String name) {
        Objects.requireNonNull(d, name);
        final long ms = d.toMillis();
        if (ms <= 0 || ms > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(name + " must be between 1ms and " + Integer.MAX_VALUE + "ms");
        }
        return (int) ms;
    }

    private static void requirePositive(final int v, final String name) {
        if (v <= 0) {
            throw new IllegalArgumentException(name + " must be > 0");
        }
    }
}
