package com.example.config;

import com.example.config.model.Permissions;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

@Slf4j

/** Parses permissions.yaml into a Permissions model. Requires a YAML-capable ObjectMapper. */
public final class PermissionsReader {

    private final ObjectMapper yamlMapper;

    public PermissionsReader(ObjectMapper yamlMapper) {
        this.yamlMapper = Objects.requireNonNull(yamlMapper, "yamlMapper");
    }

    public Permissions read(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        try {
            Permissions result = yamlMapper.readValue(input, Permissions.class);
            LOG.debug("Parsed {} path(s)", result.paths().size());
            return result;
        } catch (Exception e) {
            LOG.error("Failed to parse permissions config", e);
            throw new IOException("Failed to parse permissions configuration", e);
        }
    }
}
