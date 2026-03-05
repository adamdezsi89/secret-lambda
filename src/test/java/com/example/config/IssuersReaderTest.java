package com.example.config;

import com.example.config.model.AcceptedIssuers;
import com.example.json.ObjectMapperBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IssuersReaderTest {

    private IssuersReader issuersReader;

    @BeforeEach
    void setUp() {
        issuersReader = new IssuersReader(ObjectMapperBuilder.createLenient());
    }

    @Test
    void read_shouldParseIssuersJsonCorrectly() throws IOException {
        // Arrange
        try (InputStream inputStream = getClass().getResourceAsStream("/mock-s3/issuers.json")) {
            assertThat(inputStream).isNotNull();

            // Act
            AcceptedIssuers acceptedIssuers = issuersReader.read(inputStream);

            // Assert
            List<AcceptedIssuers.Issuer> issuers = acceptedIssuers.getAcceptedIssuers();
            assertThat(issuers).hasSize(1);

            AcceptedIssuers.Issuer issuer = issuers.get(0);
            assertThat(issuer.getIss()).isEqualTo("https://test-issuer.com");
            assertThat(issuer.getJwksUrl()).isEqualTo("https://test-issuer.com/.well-known/jwks.json");
            assertThat(issuer.getAcceptedAlgorithms()).containsExactly("RS512");
        }
    }

    @Test
    void read_shouldThrowExceptionForNullInputStream() {
        // Act & Assert
        assertThatThrownBy(() -> issuersReader.read(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("inputStream");
    }

    @Test
    void read_shouldThrowExceptionForInvalidJson() {
        // Arrange
        InputStream invalidJson = InputStream.nullInputStream();

        // Act & Assert
        assertThatThrownBy(() -> issuersReader.read(invalidJson))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Failed to parse issuers configuration");
    }
}
