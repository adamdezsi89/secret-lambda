package com.example.config;

import com.example.config.model.Permissions;
import com.example.json.ObjectMapperBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PermissionsReader Specification")
class PermissionsReaderTest {

    private PermissionsReader reader;

    @BeforeEach
    void setUp() {
        reader = new PermissionsReader(ObjectMapperBuilder.createYamlLenient());
    }

    @Nested
    @DisplayName("When reading from the classpath 'permissions.yaml'")
    class ClasspathResource {

        @Test
        @DisplayName("should correctly parse multiple endpoints and scopes")
        void parseStandardFile() throws IOException {
            try (InputStream is = getClass().getResourceAsStream("/mock-s3/permissions.yaml")) {
                assertThat(is).as("resource /mock-s3/permissions.yaml must exist").isNotNull();

                Permissions permissions = reader.read(is);

                // /test-endpoint-01: GET -> [TEST-SCOPE-01], POST -> [TEST-SCOPE-02]
                assertScopes(permissions, "/test-endpoint-01", "GET", "TEST-SCOPE-01");
                assertScopes(permissions, "/test-endpoint-01", "POST", "TEST-SCOPE-02");

                // /test-endpoint-02: GET -> [TEST-SCOPE-01, TEST-SCOPE-02]
                assertScopes(permissions, "/test-endpoint-02", "GET", "TEST-SCOPE-01", "TEST-SCOPE-02");
            }
        }

        @ParameterizedTest
        @ValueSource(strings = {"GET", "get", "GeT"})
        @DisplayName("should be case-insensitive for HTTP methods")
        void caseInsensitivity(String method) throws IOException {
            try (InputStream is = getClass().getResourceAsStream("/mock-s3/permissions.yaml")) {
                Permissions permissions = reader.read(is);
                assertScopes(permissions, "/test-endpoint-01", method, "TEST-SCOPE-01");
            }
        }
    }

    @Nested
    @DisplayName("When handling edge cases and empty configurations")
    class EdgeCases {

        @Test
        @DisplayName("should return an empty Optional for unconfigured paths")
        void unconfiguredPath() throws IOException {
            Permissions permissions = readFromYaml("openapi: 3.0.1\npaths: {}");
            assertThat(permissions.requiredScopes("/any", "GET")).isEmpty();
        }

        @Test
        @DisplayName("should return an empty Optional for unconfigured methods on a configured path")
        void unconfiguredMethod() throws IOException {
            Permissions permissions = readFromYaml("""
                paths:
                  /api:
                    get: { x-oidcScopes: [read] }
                """);
            assertThat(permissions.requiredScopes("/api", "POST")).isEmpty();
        }

        @Test
        @DisplayName("should return an empty List (public) for operations without x-oidcScopes")
        void publicOperation() throws IOException {
            Permissions permissions = readFromYaml("""
                paths:
                  /public:
                    get: { summary: "No scopes defined" }
                """);
            
            Optional<List<String>> result = permissions.requiredScopes("/public", "GET");
            assertThat(result).isPresent();
            assertThat(result.get()).isEmpty();
        }

        @Test
        @DisplayName("should ignore null paths and null operations")
        void nullElements() throws IOException {
            Permissions permissions = readFromYaml("""
                paths:
                  /null-op: null
                  /empty-op: {}
                """);
            assertThat(permissions.requiredScopes("/null-op", "GET")).isEmpty();
            assertThat(permissions.requiredScopes("/empty-op", "GET")).isEmpty();
        }
    }

    @Nested
    @DisplayName("When encountering errors")
    class ErrorHandling {

        @Test
        @DisplayName("should throw NullPointerException if input is null")
        void nullInput() {
            assertThatThrownBy(() -> reader.read(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("input");
        }

        @Test
        @DisplayName("should throw Exception for malformed YAML")
        void malformedYaml() {
            // A truly malformed YAML that triggers a parser error
            InputStream malformed = new ByteArrayInputStream("paths:\n  /test: : :".getBytes());
            // In Jackson 3, some exceptions might be RuntimeExceptions
            assertThatThrownBy(() -> reader.read(malformed))
                    .isInstanceOf(Exception.class);
        }
    }

    @Nested
    @DisplayName("When parsing all standard HTTP methods")
    class HttpMethods {

        @Test
        @DisplayName("should support GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS, TRACE")
        void allMethods() throws IOException {
            Permissions permissions = readFromYaml("""
                paths:
                  /all:
                    get:     { x-oidcScopes: [s1] }
                    post:    { x-oidcScopes: [s2] }
                    put:     { x-oidcScopes: [s3] }
                    delete:  { x-oidcScopes: [s4] }
                    patch:   { x-oidcScopes: [s5] }
                    head:    { x-oidcScopes: [s6] }
                    options: { x-oidcScopes: [s7] }
                    trace:   { x-oidcScopes: [s8] }
                """);

            assertScopes(permissions, "/all", "GET", "s1");
            assertScopes(permissions, "/all", "POST", "s2");
            assertScopes(permissions, "/all", "PUT", "s3");
            assertScopes(permissions, "/all", "DELETE", "s4");
            assertScopes(permissions, "/all", "PATCH", "s5");
            assertScopes(permissions, "/all", "HEAD", "s6");
            assertScopes(permissions, "/all", "OPTIONS", "s7");
            assertScopes(permissions, "/all", "TRACE", "s8");
        }
    }

    // ── Helper Methods ────────────────────────────────────────────────────────

    private Permissions readFromYaml(String yaml) throws IOException {
        InputStream is = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
        return reader.read(is);
    }

    private void assertScopes(Permissions p, String path, String method, String... expected) {
        Optional<List<String>> result = p.requiredScopes(path, method);
        assertThat(result)
                .as("Scopes for %s %s", method, path)
                .isPresent();
        assertThat(result.get())
                .containsExactly(expected);
    }
}
