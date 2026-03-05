package com.example.config.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Parsed endpoint-to-scope mapping derived from a permissions YAML file.
 * 
 * <p>Lookup semantics via {@link #requiredScopes}:
 * <ul>
 *   <li>{@link Optional#empty()} — path/method not present in config; caller should apply a
 *       default policy (typically deny).</li>
 *   <li>{@code Optional.of(emptyList)} — endpoint is configured but requires no scopes
 *       (publicly accessible).</li>
 *   <li>{@code Optional.of(nonEmptyList)} — endpoint requires all listed scopes.</li>
 * </ul>
 */
@JsonDeserialize
@JsonIgnoreProperties(ignoreUnknown = true)
public record Permissions(
        @JsonProperty("paths") Map<String, PathItem> paths
) {

    /**
     * Returns the required scopes for the given path and HTTP method.
     *
     * @param path   request path, e.g. {@code /api/resource}
     * @param method HTTP method; case-insensitive
     * @return scopes if the endpoint is configured, empty Optional otherwise
     */
    public Optional<List<String>> requiredScopes(String path, String method) {
        if (paths == null) {
            return Optional.empty();
        }
        PathItem pathItem = paths.get(path);
        if (pathItem == null) {
            return Optional.empty();
        }
        
        return pathItem.getOperation(method)
                .map(op -> {
                    List<String> scopes = op.oidcScopes();
                    return scopes != null ? List.copyOf(scopes) : List.<String>of();
                });
    }

    @JsonDeserialize
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PathItem(
            @JsonProperty("get")     Operation get,
            @JsonProperty("post")    Operation post,
            @JsonProperty("put")     Operation put,
            @JsonProperty("delete")  Operation delete,
            @JsonProperty("patch")   Operation patch,
            @JsonProperty("head")    Operation head,
            @JsonProperty("options") Operation options,
            @JsonProperty("trace")   Operation trace
    ) {
        public Optional<Operation> getOperation(String method) {
            return Optional.ofNullable(switch (method.toLowerCase()) {
                case "get"     -> get;
                case "post"    -> post;
                case "put"     -> put;
                case "delete"  -> delete;
                case "patch"   -> patch;
                case "head"    -> head;
                case "options" -> options;
                case "trace"   -> trace;
                default        -> null;
            });
        }
    }

    @JsonDeserialize
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Operation(
            @JsonProperty("x-oidcScopes") List<String> oidcScopes
    ) {}
}
