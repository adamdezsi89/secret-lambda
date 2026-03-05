package com.example.s3;

import com.example.config.EnvironmentConfigLoader;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryMode;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;
import java.time.Duration;

/**
 * Builder for creating configured S3 clients.
 */
@Slf4j
public final class S3ClientBuilder {

    private static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration SOCKET_TIMEOUT     = Duration.ofSeconds(5);
    private static final int      MAX_CONNECTIONS    = 50;

    private S3ClientBuilder() {}

    public static S3Client create(EnvironmentConfigLoader.Config config) {
        S3Client client;
        if (config.hasTestConfiguration()) {
            client = createMinioClient(config.getS3Region(), config.getS3Endpoint(), config.getS3AccessKey(), config.getS3SecretKey());
        } else {
            client = createS3Client(config.getS3Region());
        }
        LOG.debug("S3 client created: region={}, endpoint={}",
                config.getS3Region(), config.hasTestConfiguration() ? config.getS3Endpoint() : "AWS default");
        return client;
    }

    private static S3Client createS3Client(String region) {
        return S3Client.builder()
            .region(Region.of(region))
            .credentialsProvider(DefaultCredentialsProvider.create())
            .overrideConfiguration(createOverrideConfig())
            .httpClient(createHttpClient())
            .build();
    }

    private static S3Client createMinioClient(String region, String endpoint, String accessKey, String secretKey) {
        return S3Client.builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.of(region))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey, secretKey)
                )
            )
            .overrideConfiguration(createOverrideConfig())
            .httpClient(createHttpClient())
            .serviceConfiguration(
                S3Configuration.builder()
                    .pathStyleAccessEnabled(true) // critical for MinIO
                    .build()
            )
            .build();
    }

    private static ClientOverrideConfiguration createOverrideConfig() {
        return ClientOverrideConfiguration.builder()
            .retryStrategy(RetryMode.STANDARD)
            .build();
    }

    private static SdkHttpClient createHttpClient() {
        return Apache5HttpClient.builder()
            .connectionTimeout(CONNECTION_TIMEOUT)
            .socketTimeout(SOCKET_TIMEOUT)
            .maxConnections(MAX_CONNECTIONS)
            .build();
    }

}
