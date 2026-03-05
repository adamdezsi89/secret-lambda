package com.example.config;

import com.example.config.model.Permissions;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

@Slf4j

/**
 * Reads a stripped OAS 3.0.1 permissions file and produces a {@link Permissions} model.
 * Requires a YAML-capable {@link ObjectMapper}
 * (see {@link com.example.json.ObjectMapperBuilder#createYamlLenient()}).
 */
public final class PermissionsReader {

    private final ObjectMapper yamlMapper;

    public PermissionsReader(ObjectMapper yamlMapper) {
        this.yamlMapper = Objects.requireNonNull(yamlMapper, "yamlMapper");
    }

    /**
     * Parses the given input stream into a {@link Permissions} model.
     *
     * @param input the input stream containing the YAML content
     * @return the parsed Permissions model
     * @throws IOException if parsing fails
     */
    public Permissions read(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        try {
            Permissions result = yamlMapper.readValue(input, Permissions.class);
            LOG.debug("Parsed permissions configuration: {} path(s)", result.paths().size());
            return result;
        } catch (Exception e) {
            LOG.error("Failed to parse permissions configuration", e);
            throw new IOException("Failed to parse permissions configuration", e);
        }
    }
}
