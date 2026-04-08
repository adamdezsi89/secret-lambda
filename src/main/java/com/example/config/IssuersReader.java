package com.example.config;

import com.example.config.model.AcceptedIssuers;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

@Slf4j

/** Parses issuers.json into an AcceptedIssuers model. */
public final class IssuersReader {
    
    private final ObjectMapper objectMapper;
    
    public IssuersReader(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }
    
    public AcceptedIssuers read(InputStream inputStream) throws IOException {
        Objects.requireNonNull(inputStream, "inputStream");
        
        try {
            AcceptedIssuers result = objectMapper.readValue(inputStream, AcceptedIssuers.class);
            result.validate();
            LOG.debug("Parsed and validated {} issuer(s)", result.getAcceptedIssuers().size());
            return result;
        } catch (Exception e) {
            LOG.error("Failed to parse issuers config", e);
            throw new IOException("Failed to parse issuers configuration from input stream", e);
        }
    }
}
