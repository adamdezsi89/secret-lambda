package com.example.s3;

import java.io.Closeable;
import java.io.IOException;
import java.time.Clock;

/**
 * TTL-based file cache backed by a remote object store. Implementations must serve cached content
 * within TTL without a remote call, perform conditional fetches (e.g. ETag / If-None-Match) on
 * expiry to avoid redundant transfers, and be safe for concurrent access. {@link #getFile(String)}
 * delegates to {@link #getFile(String, Clock)} using the system UTC clock; the clock-aware overload
 * exists primarily to enable deterministic testing. Callers own lifecycle management via
 * {@link #close()}.
 */
public interface FileCache extends Closeable {

    /**
     * Returns the UTF-8 content of the named file, loading or refreshing from the remote store as
     * needed.
     *
     * @param fileName object key / path identifying the file
     * @return file content as a UTF-8 string
     * @throws IOException if the file cannot be fetched or read
     */
    String getFile(String fileName) throws IOException;

    /**
     * Returns the UTF-8 content of the named file using the supplied {@code clock} for TTL
     * evaluation; intended for testing with a fixed or controlled time source.
     *
     * @param fileName object key / path identifying the file
     * @param clock    clock used to determine cache expiry
     * @return file content as a UTF-8 string
     * @throws IOException if the file cannot be fetched or read
     */
    String getFile(String fileName, Clock clock) throws IOException;

    /** Releases underlying resources (e.g. the remote store client). */
    @Override
    void close();
}
