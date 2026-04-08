package com.example.config;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Map;

@Slf4j

/** Loads and validates required/optional environment variables into a Config object. */
public final class EnvironmentConfigLoader {

    private static final String S3_REGION_ENV_VAR = "APP_CONF_S3_REGION";
    private static final String S3_BUCKET_ENV_VAR = "APP_CONF_S3_BUCKET";
    private static final String S3_TTL_SEC_ENV_VAR = "APP_CONF_S3_TTL_SEC";

    // Test configuration (optional)
    private static final String S3_ENDPOINT = "APP_TEST_CONF_S3_ENDPOINT";
    private static final String S3_ACCESS_KEY = "APP_TEST_CONF_S3_ACCESS_KEY";
    private static final String S3_SECRET_KEY = "APP_TEST_CONF_S3_SECRET_KEY";

    private EnvironmentConfigLoader() {}

    /** Validated configuration loaded from environment variables. */
    public static final class Config {
        private final String s3Region;
        private final String s3Bucket;
        private final Duration s3Ttl;
        private final String s3Endpoint;
        private final String s3AccessKey;
        private final String s3SecretKey;

        private Config(String s3Region, String s3Bucket, Duration s3Ttl, String s3Endpoint, String s3AccessKey, String s3SecretKey) {
            this.s3Region = s3Region;
            this.s3Bucket = s3Bucket;
            this.s3Ttl = s3Ttl;
            this.s3Endpoint = s3Endpoint;
            this.s3AccessKey = s3AccessKey;
            this.s3SecretKey = s3SecretKey;
        }

        public String getS3Region() {
            return s3Region;
        }

        public String getS3Bucket() {
            return s3Bucket;
        }

        public Duration getS3Ttl() {
            return s3Ttl;
        }

        public String getS3Endpoint() {
            return s3Endpoint;
        }

        public String getS3AccessKey() {
            return s3AccessKey;
        }

        public String getS3SecretKey() {
            return s3SecretKey;
        }

        public boolean hasTestConfiguration() {
            return s3Endpoint != null || s3AccessKey != null || s3SecretKey != null;
        }
    }

    /** Loads and validates configuration from environment variables. */
    public static Config loadConfig() {
        Map<String, String> env = System.getenv();

        String s3Region = env.get(S3_REGION_ENV_VAR);
        String configS3Bucket = env.get(S3_BUCKET_ENV_VAR);
        String s3TtlSec = env.get(S3_TTL_SEC_ENV_VAR);
        String s3Endpoint = env.get(S3_ENDPOINT);
        String s3AccessKey = env.get(S3_ACCESS_KEY);
        String s3SecretKey = env.get(S3_SECRET_KEY);

        validateRequiredEnvironmentVariable(S3_REGION_ENV_VAR, s3Region);
        validateRequiredEnvironmentVariable(S3_BUCKET_ENV_VAR, configS3Bucket);
        validateRequiredEnvironmentVariable(S3_TTL_SEC_ENV_VAR, s3TtlSec);

        Duration s3Ttl = parseTtlSeconds(s3TtlSec);

        Config config = new Config(s3Region, configS3Bucket, s3Ttl, s3Endpoint, s3AccessKey, s3SecretKey);
        LOG.info("Config loaded: region={}, bucket={}, ttl={}s, testMode={}",
                s3Region, configS3Bucket, s3Ttl.toSeconds(), config.hasTestConfiguration());
        if (config.hasTestConfiguration()) {
            LOG.warn("Test S3 config active, endpoint={} — not for production", s3Endpoint);
        }
        return config;
    }

    /** Parses a positive integer TTL from the raw env var string. */
    private static Duration parseTtlSeconds(String ttlSeconds) {
        try {
            long seconds = Long.parseLong(ttlSeconds);
            if (seconds <= 0) {
                throw new IllegalStateException("TTL seconds must be positive, got: " + seconds);
            }
            return Duration.ofSeconds(seconds);
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                "Invalid TTL seconds format: '" + ttlSeconds + "'. Must be a positive integer."
            );
        }
    }

    /** Throws if the given required env var is null or blank. */
    private static void validateRequiredEnvironmentVariable(String varName, String value) {
        if (value == null || value.trim().isBlank()) {
            throw new IllegalStateException(
                "Required environment variable '" + varName + "' is missing or blank. " +
                "Please set this environment variable before starting the Lambda function."
            );
        }
    }
}
