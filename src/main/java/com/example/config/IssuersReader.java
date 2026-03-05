package com.example.config;

import com.example.config.model.AcceptedIssuers;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

@Slf4j

/**
 * Utility class for reading issuers configuration from JSON input streams.
 */
public final class IssuersReader {
    
    private final ObjectMapper objectMapper;
    
    /**
     * Creates an IssuersReader with a custom ObjectMapper.
     * 
     * @param objectMapper the ObjectMapper to use for JSON parsing
     */
    public IssuersReader(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }
    
    /**
     * Reads issuers configuration from an input stream containing JSON structure.
     * 
     * @param inputStream input stream containing issuers.json content
     * @return AcceptedIssuers model instance
     * @throws IOException if the input stream cannot be read or JSON is invalid
     */
    public AcceptedIssuers read(InputStream inputStream) throws IOException {
        Objects.requireNonNull(inputStream, "inputStream");
        
        try {
            AcceptedIssuers result = objectMapper.readValue(inputStream, AcceptedIssuers.class);
            LOG.debug("Parsed issuers configuration: {} issuer(s)", result.getAcceptedIssuers().size());
            return result;
        } catch (Exception e) {
            LOG.error("Failed to parse issuers configuration", e);
            throw new IOException("Failed to parse issuers configuration from input stream", e);
        }
    }
}
