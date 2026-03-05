package com.example.s3;

import software.amazon.awssdk.services.s3.S3Client;

import java.time.Duration;

class S3FileCacheTest extends FileCacheContractTest {

    @Override
    protected FileCache createCache(S3Client s3Client, String bucket, Duration ttl) {
        return new S3FileCache(s3Client, bucket, ttl);
    }
}
