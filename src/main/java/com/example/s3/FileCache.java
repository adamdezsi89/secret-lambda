package com.example.s3;

import java.io.Closeable;
import java.io.IOException;
import java.time.Clock;

/**
 * TTL-based file cache backed by a remote object store.
 * Serves cached content within TTL, uses conditional fetches (ETag) on expiry,
 * and must be safe for concurrent access. The Clock overload exists for testing.
 */
public interface FileCache extends Closeable {

    String getFile(String fileName) throws IOException;

    String getFile(String fileName, Clock clock) throws IOException;

    @Override
    void close();
}
