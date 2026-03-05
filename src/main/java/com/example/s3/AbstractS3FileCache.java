package com.example.s3;

import software.amazon.awssdk.services.s3.S3Client;

import java.time.Duration;
import java.util.Objects;

public abstract class AbstractS3FileCache implements FileCache {

    protected final S3Client s3Client;
    protected final String bucketName;
    protected final Duration ttl;

    protected AbstractS3FileCache(S3Client s3Client, String bucketName, Duration ttl) {
        this.s3Client = Objects.requireNonNull(s3Client, "s3Client");
        this.bucketName = Objects.requireNonNull(bucketName, "bucketName");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
    }

    @Override
    public void close() {
        s3Client.close();
    }
}
