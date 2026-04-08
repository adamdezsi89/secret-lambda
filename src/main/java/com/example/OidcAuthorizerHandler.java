package com.example;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayCustomAuthorizerEvent;
import com.example.lambda.AuthorizerEventTokenExtractor;
import com.example.config.ConfigurationLoader;
import com.example.config.EnvironmentConfigLoader;
import com.example.config.IssuersReader;
import com.example.config.PermissionsReader;
import com.example.config.model.AcceptedIssuers;
import com.example.json.ObjectMapperBuilder;
import com.example.jwt.JwkSourceFactory;
import com.example.jwt.JwtProcessorFactory;
import com.example.lambda.model.RestApiGwAuthorizerResponse;
import com.example.s3.FileCache;
import com.example.s3.S3ClientBuilder;
import com.example.s3.S3FileCache;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j

public class OidcAuthorizerHandler implements RequestHandler<APIGatewayCustomAuthorizerEvent, RestApiGwAuthorizerResponse> {
    private final EnvironmentConfigLoader.Config config;
    private final S3Client s3Client;
    private final FileCache fileCache;
    private final ObjectMapper objectMapper;
    private final ObjectMapper yamlMapper;
    private final IssuersReader issuersReader;
    private final PermissionsReader permissionsReader;
    private final ConfigurationLoader configurationLoader;
    private final Map<String, ConfigurableJWTProcessor<SecurityContext>> jwtProcessors;

    /**
     * Initialize the SDK client outside of the handler method so that it can be reused for subsequent invocations.
     * It is initialized when the class is loaded.
     * Consider invoking a simple api here to pre-warm up the application, eg: dynamodb#listTables
     */
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
        // MalformedURLException is a subclass of IOException — one catch covers both.
        try {
            AcceptedIssuers issuersConfig = configurationLoader.getIssuersConfig();
            configurationLoader.getPermissionsConfig();

            List<AcceptedIssuers.Issuer> issuers = issuersConfig.getAcceptedIssuers();
            Map<String, JWKSource<SecurityContext>> jwkSources = JwkSourceFactory.create(issuers);
            jwtProcessors = JwtProcessorFactory.create(issuers, jwkSources);
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialise JWT infrastructure during cold start", e);
        }
    }

    @Override
    public RestApiGwAuthorizerResponse handleRequest(APIGatewayCustomAuthorizerEvent event, Context context) {
        LOG.info("Received request: {}", event.toString());

        return AuthorizerEventTokenExtractor.extractToken(event)
            .map(token -> {
                // TODO: Validate token and check permissions
                return RestApiGwAuthorizerResponse.builder("test-principalId")
                    .allowMethodArn(event.getMethodArn())
                    .build();
            })
            .orElseGet(() -> {
                LOG.warn("No authorization token found. Returning DENY policy.");
                return RestApiGwAuthorizerResponse.builder("anonymous")
                    .build();
            });
    }
}
