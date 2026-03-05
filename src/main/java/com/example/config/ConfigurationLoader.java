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
 * Provides parsed, TTL-refreshed configuration to the rest of the application.
 * Raw file loading and TTL-based caching are fully delegated to {@link FileCache};
 * parsing is delegated to the injected readers. Callers are shielded from all three
 * concerns and interact only with typed configuration models.
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

    /**
     * Returns the current issuers configuration.
     * Content is refreshed from S3 when the TTL managed by {@link FileCache} expires.
     */
    public AcceptedIssuers getIssuersConfig() throws IOException {
        LOG.debug("Loading issuers configuration from file: {}", ISSUERS_FILE);
        String content = fileCache.getFile(ISSUERS_FILE);
        try (InputStream is = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))) {
            AcceptedIssuers result = issuersReader.read(is);
            LOG.trace("Successfully loaded issuers configuration.");
            return result;
        }
    }

    /**
     * Returns the current permissions configuration.
     * Content is refreshed from S3 when the TTL managed by {@link FileCache} expires.
     */
    public Permissions getPermissionsConfig() throws IOException {
        LOG.debug("Loading permissions configuration from file: {}", PERMISSIONS_FILE);
        String content = fileCache.getFile(PERMISSIONS_FILE);
        try (InputStream is = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))) {
            Permissions result = permissionsReader.read(is);
            LOG.trace("Successfully loaded permissions configuration.");
            return result;
        }
    }

    /** Closes the underlying {@link FileCache} and releases its S3 client. */
    @Override
    public void close() {
        fileCache.close();
    }
}
