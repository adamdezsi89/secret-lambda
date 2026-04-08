package com.example.config.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

/** Deserialized issuers.json — list of accepted OIDC issuers. */
public final class AcceptedIssuers {
    
    private final List<Issuer> acceptedIssuers;
    
    @JsonCreator
    public AcceptedIssuers(@JsonProperty("acceptedIssuers") List<Issuer> acceptedIssuers) {
        this.acceptedIssuers = Objects.requireNonNull(acceptedIssuers, "acceptedIssuers");
    }
    
    public List<Issuer> getAcceptedIssuers() {
        return acceptedIssuers;
    }
    
    /** Single OIDC issuer: iss claim value, JWKS URL, and accepted signing algorithms. */
    public static final class Issuer {
        private final String iss;
        private final String jwksUrl;
        private final List<String> acceptedAlgorithms;
        
        @JsonCreator
        public Issuer(
            @JsonProperty("iss") String iss,
            @JsonProperty("jwksUrl") String jwksUrl,
            @JsonProperty("acceptedAlgorithms") List<String> acceptedAlgorithms
        ) {
            this.iss = Objects.requireNonNull(iss, "iss");
            this.jwksUrl = Objects.requireNonNull(jwksUrl, "jwksUrl");
            this.acceptedAlgorithms = Objects.requireNonNull(acceptedAlgorithms, "acceptedAlgorithms");
        }
        
        public String getIss() {
            return iss;
        }
        
        public String getJwksUrl() {
            return jwksUrl;
        }
        
        public List<String> getAcceptedAlgorithms() {
            return acceptedAlgorithms;
        }
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            Issuer issuer = (Issuer) obj;
            return Objects.equals(iss, issuer.iss) &&
                   Objects.equals(jwksUrl, issuer.jwksUrl) &&
                   Objects.equals(acceptedAlgorithms, issuer.acceptedAlgorithms);
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(iss, jwksUrl, acceptedAlgorithms);
        }
        
        @Override
        public String toString() {
            return "Issuer{" +
                   "iss='" + iss + '\'' +
                   ", jwksUrl='" + jwksUrl + '\'' +
                   ", acceptedAlgorithms=" + acceptedAlgorithms +
                   '}';
        }
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        AcceptedIssuers that = (AcceptedIssuers) obj;
        return Objects.equals(acceptedIssuers, that.acceptedIssuers);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(acceptedIssuers);
    }
    
    @Override
    public String toString() {
        return "AcceptedIssuers{" +
               "acceptedIssuers=" + acceptedIssuers +
               '}';
    }
}
