package com.example.config;

import com.example.config.model.AcceptedIssuers;
import com.example.config.model.Permissions;
import com.example.s3.FileCache;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Provides parsed, TTL-refreshed issuers and permissions configuration.
 * Delegates file retrieval and caching to FileCache, parsing to the injected readers.
 */
@Slf4j
public final class ConfigurationLoader implements Closeable {

    public static final String ISSUERS_FILE     = "issuers.json";
    public static final String PERMISSIONS_FILE = "permissions.yaml";

    private final FileCache fileCache;
    private final IssuersReader issuersReader;
    private final PermissionsReader permissionsReader;

    public ConfigurationLoader(FileCache fileCache,
                               IssuersReader issuersReader,
                               PermissionsReader permissionsReader) {
        this.fileCache         = Objects.requireNonNull(fileCache,         "fileCache");
        this.issuersReader     = Objects.requireNonNull(issuersReader,     "issuersReader");
        this.permissionsReader = Objects.requireNonNull(permissionsReader, "permissionsReader");
    }

    /** Returns the current issuers config, refreshed from S3 on TTL expiry. */
    public AcceptedIssuers getIssuersConfig() throws IOException {
        LOG.debug("Loading issuers config from {}", ISSUERS_FILE);
        String content = fileCache.getFile(ISSUERS_FILE);
        try (InputStream is = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))) {
            AcceptedIssuers result = issuersReader.read(is);
            LOG.trace("Issuers config loaded");
            return result;
        }
    }

    /** Returns the current permissions config, refreshed from S3 on TTL expiry. */
    public Permissions getPermissionsConfig() throws IOException {
        LOG.debug("Loading permissions config from {}", PERMISSIONS_FILE);
        String content = fileCache.getFile(PERMISSIONS_FILE);
        try (InputStream is = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))) {
            Permissions result = permissionsReader.read(is);
            LOG.trace("Permissions config loaded");
            return result;
        }
    }

    /** Closes the underlying file cache and its S3 client. */
    @Override
    public void close() {
        fileCache.close();
    }
}
