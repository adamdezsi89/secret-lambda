package com.example.config.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Endpoint-to-scope mapping from permissions.yaml.
 *
 * requiredScopes() returns:
 *   empty()         — path/method not configured (deny)
 *   of(emptyList)   — public, no scopes required
 *   of(nonEmptyList) — scopes required
 */
@JsonDeserialize
@JsonIgnoreProperties(ignoreUnknown = true)
@lombok.extern.slf4j.Slf4j
public record Permissions(
        @JsonProperty("paths") Map<String, PathItem> paths
) {

    /** Lints the config — warns about public or misconfigured operations. */
    public void validate() {
        if (paths == null || paths.isEmpty()) {
            LOG.warn("Permissions config has no paths");
            return;
        }

        paths.forEach((path, item) -> {
            if (item == null) {
                LOG.warn("Path {} has no operations", path);
                return;
            }
            validateOperation(path, "GET", item.get);
            validateOperation(path, "POST", item.post);
            validateOperation(path, "PUT", item.put);
            validateOperation(path, "DELETE", item.delete);
            validateOperation(path, "PATCH", item.patch);
            validateOperation(path, "HEAD", item.head);
            validateOperation(path, "OPTIONS", item.options);
            validateOperation(path, "TRACE", item.trace);
        });
    }

    private void validateOperation(String path, String method, Operation op) {
        if (op == null) return;

        List<String> scopes = op.oidcScopes();
        if (scopes == null || scopes.isEmpty()) {
            LOG.warn("Operation [{} {}] is public — no x-oidcScopes", method, path);
        } else {
            boolean hasEmptyScope = scopes.stream().anyMatch(s -> s == null || s.trim().isEmpty());
            if (hasEmptyScope) {
                LOG.warn("Operation [{} {}] has empty scope strings in x-oidcScopes", method, path);
            }
        }
    }

    /** Returns required scopes for the given path and HTTP method (case-insensitive). */
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
