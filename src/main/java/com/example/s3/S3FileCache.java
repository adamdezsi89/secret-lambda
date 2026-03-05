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
 * TTL-based S3 file cache with ETag conditional fetching and per-key locking via
 * {@link ConcurrentHashMap#compute}.
 *
 * <p>Reads are served lock-free via {@link ConcurrentHashMap#get} on the hot path. On TTL expiry,
 * {@code compute()} provides per-key mutual exclusion — preventing thundering-herd duplicate S3
 * calls without a separate lock map and its associated memory overhead. A double-check inside the
 * compute lambda ensures threads that waited for the key lock reuse the result fetched by the first
 * thread rather than issuing their own S3 request.
 *
 * <p>On expiry, an ETag-driven conditional GET ({@code If-None-Match}) is issued. A 304 response
 * resets the TTL without retransferring the body. S3 errors always propagate as {@link IOException}
 * — stale-on-error is intentionally omitted because serving an outdated JWKS after key rotation
 * would silently break validation of legitimately issued tokens.
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
            LOG.debug("Cache hit for file: {}", fileName);
            return entry.content();
        }

        // Cold path: compute() holds a per-key lock for the duration of the S3 fetch.
        // UncheckedIOException tunnels the checked IOException out of the lambda.
        LOG.info("Cache miss or expiry for file: {}. Refreshing...", fileName);
        try {
            return cache.compute(fileName, (key, existing) -> {
                // Double-check: a concurrent thread may have refreshed while we waited.
                if (existing != null && !existing.isExpired(clock)) {
                    LOG.debug("Double-check success for file: {}. Reusing content refreshed by another thread.", fileName);
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
            LOG.debug("Issuing conditional GET for {} [If-None-Match: {}]", fileName, existing.eTag());
            request.ifNoneMatch(existing.eTag());
        } else {
            LOG.debug("Issuing full GET for {} (no cached version found)", fileName);
        }

        try (ResponseInputStream<GetObjectResponse> stream = s3Client.getObject(request.build())) {
            byte[] bytes = stream.readAllBytes();
            String content = new String(bytes, StandardCharsets.UTF_8);
            GetObjectResponse meta = stream.response();

            LOG.info("Successfully fetched new content for {} from S3 [ETag: {}, Size: {} bytes]",
                    fileName, meta.eTag(), bytes.length);

            return new CachedFile(content, meta.eTag(), clock.instant().plus(ttl));
        } catch (S3Exception e) {
            if (e.statusCode() == 304 && existing != null) {
                LOG.info("File {} not modified in S3 (304). Resetting TTL for another {}.", fileName, ttl);
                return new CachedFile(existing.content(), existing.eTag(), clock.instant().plus(ttl));
            }
            LOG.error("S3 fetch failed for bucket={} key={} [HTTP {}]: {}",
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
