package com.example.s3;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contract test suite for the {@link FileCache} interface. Every concrete implementation
 * must pass all tests defined here. Subclasses supply the implementation under test via
 * {@link #createCache}.
 */
@ExtendWith(MockitoExtension.class)
abstract class FileCacheContractTest {

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private static final String BUCKET     = "test-bucket";
    private static final String FILE       = "config/keys.json";
    private static final String CONTENT_V1 = "{\"keys\":[]}";
    private static final String CONTENT_V2 = "{\"keys\":[{\"kid\":\"new\"}]}";
    private static final String ETAG_V1    = "\"abc123\"";
    private static final String ETAG_V2    = "\"def456\"";
    private static final Duration TTL      = Duration.ofMinutes(5);

    // Fixed clocks: T0 is the base instant; CLOCK_EXPIRED is one second past TTL.
    private static final Instant T0            = Instant.parse("2024-01-01T12:00:00Z");
    private static final Clock   CLOCK_T0      = Clock.fixed(T0, ZoneOffset.UTC);
    private static final Clock   CLOCK_EXPIRED = Clock.fixed(T0.plus(TTL).plusSeconds(1), ZoneOffset.UTC);

    // ── Wiring ────────────────────────────────────────────────────────────────

    @Mock S3Client s3Client;

    FileCache cache;

    /** Subclasses return the specific implementation to test. */
    protected abstract FileCache createCache(S3Client s3Client, String bucket, Duration ttl);

    @BeforeEach
    void setUp() {
        cache = createCache(s3Client, BUCKET, TTL);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    ResponseInputStream<GetObjectResponse> s3Response(String content, String eTag) {
        GetObjectResponse response = GetObjectResponse.builder().eTag(eTag).build();
        AbortableInputStream body  = AbortableInputStream.create(
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        return new ResponseInputStream<>(response, body);
    }

    S3Exception s3Exception(int statusCode) {
        return (S3Exception) S3Exception.builder().statusCode(statusCode).message("HTTP " + statusCode).build();
    }

    // ── Contract tests ────────────────────────────────────────────────────────

    @Test
    void getFile_cacheMiss_fetchesFromS3AndReturnsContent() throws IOException {
        when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenAnswer(inv -> s3Response(CONTENT_V1, ETAG_V1));

        String result = cache.getFile(FILE, CLOCK_T0);

        assertThat(result).isEqualTo(CONTENT_V1);
        verify(s3Client, times(1)).getObject(any(GetObjectRequest.class));
    }

    @Test
    void getFile_withinTtl_returnsCachedContentWithoutS3Call() throws IOException {
        when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenAnswer(inv -> s3Response(CONTENT_V1, ETAG_V1));

        cache.getFile(FILE, CLOCK_T0);
        String second = cache.getFile(FILE, CLOCK_T0);

        assertThat(second).isEqualTo(CONTENT_V1);
        verify(s3Client, times(1)).getObject(any(GetObjectRequest.class));
    }

    @Test
    void getFile_afterTtlExpiry_refetchesFromS3() throws IOException {
        when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenAnswer(inv -> s3Response(CONTENT_V1, ETAG_V1))
                .thenAnswer(inv -> s3Response(CONTENT_V2, ETAG_V2));

        cache.getFile(FILE, CLOCK_T0);
        String refreshed = cache.getFile(FILE, CLOCK_EXPIRED);

        assertThat(refreshed).isEqualTo(CONTENT_V2);
        verify(s3Client, times(2)).getObject(any(GetObjectRequest.class));
    }

    @Test
    void getFile_afterTtlExpiry_sendsEtagInConditionalRequest() throws IOException {
        when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenAnswer(inv -> s3Response(CONTENT_V1, ETAG_V1))
                .thenAnswer(inv -> s3Response(CONTENT_V2, ETAG_V2));

        cache.getFile(FILE, CLOCK_T0);
        cache.getFile(FILE, CLOCK_EXPIRED);

        ArgumentCaptor<GetObjectRequest> captor = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3Client, times(2)).getObject(captor.capture());
        List<GetObjectRequest> requests = captor.getAllValues();

        assertThat(requests.get(0).ifNoneMatch()).as("first request must not carry ETag").isNull();
        assertThat(requests.get(1).ifNoneMatch()).as("second request must carry cached ETag").isEqualTo(ETAG_V1);
    }

    @Test
    void getFile_304NotModified_reusesContentAndResetsTtl() throws IOException {
        when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenAnswer(inv -> s3Response(CONTENT_V1, ETAG_V1))
                .thenThrow(s3Exception(304));

        cache.getFile(FILE, CLOCK_T0);
        String afterExpiry  = cache.getFile(FILE, CLOCK_EXPIRED);     // triggers 304
        String withinNewTtl = cache.getFile(FILE, CLOCK_EXPIRED);     // must be a cache hit

        assertThat(afterExpiry).isEqualTo(CONTENT_V1);
        assertThat(withinNewTtl).isEqualTo(CONTENT_V1);
        // Initial fetch + one conditional GET; the third call must NOT hit S3.
        verify(s3Client, times(2)).getObject(any(GetObjectRequest.class));
    }

    @Test
    void getFile_s3Error_throwsIOException() {
        when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenThrow(s3Exception(500));

        assertThatThrownBy(() -> cache.getFile(FILE, CLOCK_T0))
                .isInstanceOf(IOException.class);
    }

    @Test
    void getFile_multipleFiles_eachCachedIndependently() throws IOException {
        when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenAnswer(inv -> s3Response(CONTENT_V1, ETAG_V1));

        cache.getFile("a.json", CLOCK_T0);
        cache.getFile("b.json", CLOCK_T0);
        verify(s3Client, times(2)).getObject(any(GetObjectRequest.class));

        // Both are now cached — subsequent calls must not hit S3.
        cache.getFile("a.json", CLOCK_T0);
        cache.getFile("b.json", CLOCK_T0);
        verify(s3Client, times(2)).getObject(any(GetObjectRequest.class));
    }

    /**
     * Thundering-herd test: when many threads simultaneously encounter an expired entry,
     * exactly one thread must fetch from S3; the rest must reuse the result of that fetch.
     */
    @Test
    void getFile_concurrentExpiry_onlyOneFetchPerKey() throws Exception {
        when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenAnswer(inv -> s3Response(CONTENT_V1, ETAG_V1));

        // Populate the cache at T0.
        cache.getFile(FILE, CLOCK_T0);

        // All threads present an expired clock; only one must reach S3.
        int threadCount = 20;
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go    = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(threadCount);
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    cache.getFile(FILE, CLOCK_EXPIRED);
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        go.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).as("all threads must complete within 10 s").isTrue();
        pool.shutdown();

        assertThat(errors).as("no thread should throw").isEmpty();
        // 1 initial fetch at T0  +  exactly 1 re-fetch at CLOCK_EXPIRED.
        verify(s3Client, times(2)).getObject(any(GetObjectRequest.class));
    }

    @Test
    void close_delegatesToS3Client() {
        cache.close();
        verify(s3Client).close();
    }

    @Test
    void getFile_noClockOverload_fetchesFromS3() throws IOException {
        when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenAnswer(inv -> s3Response(CONTENT_V1, ETAG_V1));

        String result = cache.getFile(FILE);

        assertThat(result).isEqualTo(CONTENT_V1);
        verify(s3Client, times(1)).getObject(any(GetObjectRequest.class));
    }

    /**
     * Verifies that a slow fetch for one key does NOT block requests for other keys.
     * This is critical for high-concurrency performance across multiple config files.
     */
    @Test
    void getFile_slowFetchForOneKey_doesNotBlockOtherKeys() throws Exception {
        CountDownLatch slowFetchStarted = new CountDownLatch(1);
        CountDownLatch slowFetchCanFinish = new CountDownLatch(1);

        when(s3Client.getObject(argThat((GetObjectRequest r) -> r != null && r.key().equals("slow.json"))))
                .thenAnswer(inv -> {
                    slowFetchStarted.countDown();
                    slowFetchCanFinish.await();
                    return s3Response("slow-content", "etag-slow");
                });
        when(s3Client.getObject(argThat((GetObjectRequest r) -> r != null && r.key().equals("fast.json"))))
                .thenReturn(s3Response("fast-content", "etag-fast"));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // Start slow fetch in background
            CompletableFuture<String> slowFuture = CompletableFuture.supplyAsync(() -> {
                try { return cache.getFile("slow.json", CLOCK_T0); } catch (Exception e) { throw new RuntimeException(e); }
            }, pool);

            // Wait until slow fetch is actually inside the S3 call
            assertThat(slowFetchStarted.await(2, TimeUnit.SECONDS)).isTrue();

            // Fast fetch should complete immediately despite the slow one
            String fastResult = cache.getFile("fast.json", CLOCK_T0);
            assertThat(fastResult).isEqualTo("fast-content");

            // Release slow fetch
            slowFetchCanFinish.countDown();
            assertThat(slowFuture.get(2, TimeUnit.SECONDS)).isEqualTo("slow-content");
        } finally {
            pool.shutdown();
        }
    }

    /**
     * Verifies that if an S3 fetch fails, the failure is not "cached" and
     * subsequent requests will retry.
     */
    @Test
    void getFile_afterS3Failure_subsequentRequestRetries() throws IOException {
        when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenThrow(s3Exception(500)) // First attempt fails
                .thenAnswer(inv -> s3Response(CONTENT_V1, ETAG_V1)); // Second succeeds

        assertThatThrownBy(() -> cache.getFile(FILE, CLOCK_T0))
                .isInstanceOf(IOException.class);

        // Next call should retry and succeed
        String result = cache.getFile(FILE, CLOCK_T0);
        assertThat(result).isEqualTo(CONTENT_V1);
        verify(s3Client, times(2)).getObject(any(GetObjectRequest.class));
    }
}
