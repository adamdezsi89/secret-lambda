package com.example;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayCustomAuthorizerEvent;
import com.example.lambda.AuthorizerEventValidator;
import com.example.config.ConfigurationLoader;
import com.example.config.EnvironmentConfigLoader;
import com.example.config.IssuersReader;
import com.example.config.PermissionsReader;
import com.example.config.model.AcceptedIssuers;
import com.example.json.ObjectMapperBuilder;
import com.example.jwt.JwkSourceFactory;
import com.example.jwt.JwtProcessorFactory;
import com.example.jwt.TokenValidator;
import com.example.logging.LoggingContextConfigurer;
import com.example.lambda.exception.AccessDeniedException;
import com.example.lambda.exception.ErrorCodeType;
import com.example.lambda.exception.UnauthorizedException;
import com.example.lambda.model.RestApiGwAuthorizerResponse;
import com.example.config.model.Permissions;
import com.example.s3.FileCache;
import com.example.s3.S3ClientBuilder;
import com.example.s3.S3FileCache;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.ObjectMapper;

import com.nimbusds.jwt.JWTClaimsSet;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j

public class OidcAuthorizerHandler implements RequestHandler<APIGatewayCustomAuthorizerEvent, RestApiGwAuthorizerResponse> {

    /** Exact string API Gateway interprets as a 401 response. */
    private static final String UNAUTHORIZED_MESSAGE = "Unauthorized";
    private static final String ANONYMOUS_PRINCIPAL = "anonymous";

    private final EnvironmentConfigLoader.Config config;
    private final S3Client s3Client;
    private final FileCache fileCache;
    private final ObjectMapper objectMapper;
    private final ObjectMapper yamlMapper;
    private final IssuersReader issuersReader;
    private final PermissionsReader permissionsReader;
    private final ConfigurationLoader configurationLoader;
    private final Map<String, ConfigurableJWTProcessor<SecurityContext>> jwtProcessors;
    private boolean coldStart = true;

    /** Cold start: loads config from S3, builds JWT processors. Fails fast if anything is wrong. */
    public OidcAuthorizerHandler() {
        config = EnvironmentConfigLoader.loadConfig();
        s3Client = S3ClientBuilder.create(config);
        fileCache = new S3FileCache(s3Client, config.getS3Bucket(), config.getS3Ttl());
        objectMapper = ObjectMapperBuilder.createLenient();
        yamlMapper = ObjectMapperBuilder.createYamlLenient();
        issuersReader = new IssuersReader(objectMapper);
        permissionsReader = new PermissionsReader(yamlMapper);
        configurationLoader = new ConfigurationLoader(fileCache, issuersReader, permissionsReader);

        // Warm up S3 file cache and build JWT infrastructure from the loaded issuers config.
        try {
            AcceptedIssuers issuersConfig = configurationLoader.getIssuersConfig();
            LOG.info("Loaded {} issuer(s)", issuersConfig.getAcceptedIssuers().size());

            configurationLoader.getPermissionsConfig();
            LOG.info("Loaded permissions config");

            List<AcceptedIssuers.Issuer> issuers = issuersConfig.getAcceptedIssuers();
            Map<String, JWKSource<SecurityContext>> jwkSources = JwkSourceFactory.create(issuers);
            jwtProcessors = JwtProcessorFactory.create(issuers, jwkSources);
            LOG.info("JWT processors ready for {} issuer(s)", jwtProcessors.size());
        } catch (IOException e) {
            LOG.error("Cold start failed: {}", e.getMessage(), e);
            throw new RuntimeException("Cold start failed: " + e.getMessage(), e);
        }
    }

    @Override
    public RestApiGwAuthorizerResponse handleRequest(APIGatewayCustomAuthorizerEvent event, Context context) {
        boolean isColdStart = coldStart;
        if (coldStart) {
            coldStart = false;
        }
        LoggingContextConfigurer.setRequestContext(isColdStart, event, context);

        try {
            AuthorizerEventValidator.validateEvent(event);

            // Extract bearer token early for logging context (best-effort, no validation)
            Optional<String> token = AuthorizerEventValidator.extractBearerToken(event);
            token.ifPresent(t -> {
                int lastDot = t.lastIndexOf('.');
                if (lastDot > 0) {
                    LOG.debug("Bearer token (signature stripped): {}", t.substring(0, lastDot));
                }
                LoggingContextConfigurer.setJwtContext(TokenValidator.parseClaimsUnverified(t));
            });

            String resource = event.getResource();
            String httpMethod = event.getHttpMethod();
            String methodArn = AuthorizerEventValidator.wildcardMethodArn(event.getMethodArn(), resource);
            LOG.info("Received request: [{} {}]", httpMethod, resource);

            Permissions permissions = configurationLoader.getPermissionsConfig();
            Optional<List<String>> requiredScopes = permissions.requiredScopes(resource, httpMethod);

            // Not configured → deny
            if (requiredScopes.isEmpty()) {
                throw new AccessDeniedException(
                    "Operation [%s %s] is not configured in permissions".formatted(httpMethod, resource));
            }

            // Public → allow; validate token if present (reject if invalid, use sub as principalId if valid)
            if (requiredScopes.get().isEmpty()) {
                if (token.isPresent()) {
                    JWTClaimsSet claims = TokenValidator.validate(token.get(), jwtProcessors);
                    LOG.info("Public endpoint [{} {}], token validated, principalId={}",
                            httpMethod, resource, claims.getSubject());
                    return RestApiGwAuthorizerResponse.builder(claims.getSubject())
                        .allowMethodArn(methodArn)
                        .build();
                }
                LOG.info("Public endpoint [{} {}], no token, principalId=anonymous", httpMethod, resource);
                return RestApiGwAuthorizerResponse.builder(ANONYMOUS_PRINCIPAL)
                    .allowMethodArn(methodArn)
                    .build();
            }

            // Scopes required → token must be present
            if (token.isEmpty()) {
                throw new UnauthorizedException(ErrorCodeType.COMMON_MISSING_CREDENTIALS,
                    "No Bearer token in Authorization header for operation [%s %s]".formatted(httpMethod, resource));
            }

            // Validate token (signature, algorithm, issuer, expiry, required claims)
            JWTClaimsSet claims = TokenValidator.validate(token.get(), jwtProcessors);

            // Extract scopes and check intersection (OR logic)
            List<String> tokenScopes = TokenValidator.extractScopes(claims);
            List<String> required = requiredScopes.get();
            boolean hasIntersection = required.stream().anyMatch(tokenScopes::contains);
            if (!hasIntersection) {
                throw new AccessDeniedException(
                    "Token scopes %s do not intersect with required scopes %s for [%s %s]"
                        .formatted(tokenScopes, required, httpMethod, resource));
            }

            return RestApiGwAuthorizerResponse.builder(claims.getSubject())
                .allowMethodArn(methodArn)
                .build();

        } catch (UnauthorizedException e) {
            LOG.warn("Authentication failed [{}]: {}", e.getErrorCode(), e.getMessage());
            throw new RuntimeException(UNAUTHORIZED_MESSAGE);
        } catch (AccessDeniedException e) {
            LOG.warn("Access denied [{}]: {}", e.getErrorCode(), e.getMessage());
            return RestApiGwAuthorizerResponse.builder(ANONYMOUS_PRINCIPAL).build();
        } catch (RuntimeException e) {
            LOG.error("Internal error: {}", e.getMessage(), e);
            throw e;
        } catch (Exception e) {
            LOG.error("Internal error: {}", e.getMessage(), e);
            throw new RuntimeException(e);
        } finally {
            LoggingContextConfigurer.clear();
        }
    }
}
