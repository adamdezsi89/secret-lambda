package com.example.s3;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TTL-based S3 file cache with ETag conditional fetching.
 *
 * Hot path: lock-free ConcurrentHashMap.get(). On TTL expiry, compute() provides per-key
 * mutual exclusion with a double-check to avoid duplicate S3 calls. ETag-driven conditional
 * GET resets TTL without re-downloading on 304. Stale-on-error is intentionally omitted —
 * serving outdated JWKS after key rotation would break token validation.
 */
@Slf4j
public final class S3FileCache extends AbstractS3FileCache {

    private final ConcurrentHashMap<String, CachedFile> cache = new ConcurrentHashMap<>();

    public S3FileCache(S3Client s3Client, String bucketName, Duration ttl) {
        super(s3Client, bucketName, ttl);
    }

    @Override
    public String getFile(String fileName) throws IOException {
        return getFile(fileName, Clock.systemUTC());
    }

    @Override
    public String getFile(String fileName, Clock clock) throws IOException {
        // Hot path: lock-free read — covers the vast majority of calls.
        CachedFile entry = cache.get(fileName);
        if (entry != null && !entry.isExpired(clock)) {
            LOG.debug("Cache hit for {}", fileName);
            return entry.content();
        }

        // Cold path: compute() holds a per-key lock for the duration of the S3 fetch.
        // UncheckedIOException tunnels the checked IOException out of the lambda.
        LOG.info("Cache miss/expiry for {}, refreshing", fileName);
        try {
            return cache.compute(fileName, (key, existing) -> {
                // Double-check: a concurrent thread may have refreshed while we waited.
                if (existing != null && !existing.isExpired(clock)) {
                    LOG.debug("Double-check hit for {}, reusing", fileName);
                    return existing;
                }
                try {
                    return fetchFromS3(key, existing, clock);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }).content();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private CachedFile fetchFromS3(String fileName, CachedFile existing, Clock clock) throws IOException {
        GetObjectRequest.Builder request = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(fileName);

        if (existing != null && existing.eTag() != null) {
            LOG.debug("Conditional GET for {}, etag={}", fileName, existing.eTag());
            request.ifNoneMatch(existing.eTag());
        } else {
            LOG.debug("Full GET for {}", fileName);
        }

        try (ResponseInputStream<GetObjectResponse> stream = s3Client.getObject(request.build())) {
            byte[] bytes = stream.readAllBytes();
            String content = new String(bytes, StandardCharsets.UTF_8);
            GetObjectResponse meta = stream.response();

            LOG.info("Fetched {} from S3, etag={}, size={}", fileName, meta.eTag(), bytes.length);

            return new CachedFile(content, meta.eTag(), clock.instant().plus(ttl));
        } catch (S3Exception e) {
            if (e.statusCode() == 304 && existing != null) {
                LOG.info("Not modified (304) for {}, resetting TTL", fileName);
                return new CachedFile(existing.content(), existing.eTag(), clock.instant().plus(ttl));
            }
            LOG.error("S3 fetch failed, bucket={}, key={}, status={}: {}",
                    bucketName, fileName, e.statusCode(), e.getMessage());
            throw new IOException("S3 fetch failed [bucket=" + bucketName + ", key=" + fileName + "]", e);
        }
    }

    private record CachedFile(String content, String eTag, Instant expiresAt) {
        boolean isExpired(Clock clock) {
            return !clock.instant().isBefore(expiresAt);
        }
    }
}
