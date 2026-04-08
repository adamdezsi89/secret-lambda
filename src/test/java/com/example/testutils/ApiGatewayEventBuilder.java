package com.example.testutils;

import com.amazonaws.services.lambda.runtime.events.APIGatewayCustomAuthorizerEvent;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ApiGatewayEventBuilder {

    /** Matches OAS-style path parameter placeholders like {id} or {petName}; group(1) is the name. */
    private static final Pattern PARAM = Pattern.compile("\\{([^}]+)}");

    /** Source IP of the client */
    public static final String SOURCE_IP = "10.216.1.237";

    /** AWS API GW operation */
    public static final String REGION = "eu-central-1";
    public static final String ACCOUNT_ID = "123456123456";
    public static final String API_ID = "12345678";
    public static final String STAGE = "example_stage";

    /** Lambda */
    public static final String LAMBDA_NAME = "example_lambda_name";
    public static final String LAMBDA_ALIAS = "example_lambda_alias";
    public static final String LAMBDA_VERSION = "1";

    public static APIGatewayCustomAuthorizerEvent createTokenAuthorizerEvent(String authorizationToken, String httpMethod, String resource, Map<String, String> pathParameters) {
        APIGatewayCustomAuthorizerEvent event = new APIGatewayCustomAuthorizerEvent();

        final String resolvedPath = resolvePath(resource, pathParameters);
        final String methodArn = buildMethodArn(REGION, ACCOUNT_ID, API_ID, STAGE, httpMethod, resolvedPath);

        event.setType("TOKEN");
        event.setMethodArn(methodArn);
        event.setAuthorizationToken("Bearer " + authorizationToken);

        return event;
    }

    /**
     * Creates a detailed REQUEST type authorizer event based on the provided parameters.
     */
    public static APIGatewayCustomAuthorizerEvent createRequestAuthorizerEvent(
            String authorizationToken,
            String httpMethod,
            String resource,
            Map<String, String> pathParameters,
            Map<String, String> queryStringParameters) {

        APIGatewayCustomAuthorizerEvent event = new APIGatewayCustomAuthorizerEvent();

        final String resolvedPath = resolvePath(resource, pathParameters);
        final String methodArn = buildMethodArn(REGION, ACCOUNT_ID, API_ID, STAGE, httpMethod, resolvedPath);

        event.setType("REQUEST");
        event.setMethodArn(methodArn);
        event.setHttpMethod(httpMethod);
        event.setResource(resource);
        event.setPath(resolvedPath);

        Map<String, String> headers = new HashMap<>();
        if (authorizationToken != null) {
            headers.put("Authorization", "Bearer " + authorizationToken);
        }
        headers.put("Accept", "application/json");
        headers.put("Content-Type", "application/json");
        headers.put("Host", "example.hu");
        event.setHeaders(headers);

        event.setPathParameters(pathParameters != null ? pathParameters : Collections.emptyMap());
        event.setQueryStringParameters(queryStringParameters != null ? queryStringParameters : Collections.emptyMap());

        // Setup Stage Variables
        Map<String, String> stageVars = new HashMap<>();
        stageVars.put("AUTHORIZER_LAMBDA_FUNCTION_NAME", LAMBDA_NAME);
        stageVars.put("AUTHORIZER_LAMBDA_FUNCTION_ALIAS_NAME", LAMBDA_ALIAS);
        event.setStageVariables(stageVars);

        // Setup Request Context
        APIGatewayCustomAuthorizerEvent.RequestContext requestContext = new APIGatewayCustomAuthorizerEvent.RequestContext();
        requestContext.setPath(resolvedPath);
        requestContext.setAccountId(ACCOUNT_ID);
        requestContext.setResourceId("example_resource_id");
        requestContext.setHttpMethod(httpMethod);
        requestContext.setStage(STAGE);
        requestContext.setRequestId(UUID.randomUUID().toString());
        requestContext.setResourcePath(resource);
        requestContext.setApiId(API_ID);

        APIGatewayCustomAuthorizerEvent.Identity identity = new APIGatewayCustomAuthorizerEvent.Identity();
        identity.setSourceIp(SOURCE_IP);
        requestContext.setIdentity(identity);

        event.setRequestContext(requestContext);

        return event;
    }

    private static String resolvePath(String resource, Map<String, String> pathParameters) {
        return PARAM.matcher(resource).replaceAll(m -> {
            String v = pathParameters.get(m.group(1));
            if (v == null) throw new IllegalArgumentException("Missing path parameter: " + m.group(1));
            return Matcher.quoteReplacement(URLEncoder.encode(v, StandardCharsets.UTF_8).replace("+", "%20"));
        });
    }

    private static String buildMethodArn(String region, String accountId, String apiId, String stage, String httpMethod, String resolvedPath) {
        String path = resolvedPath.startsWith("/") ? resolvedPath.substring(1) : resolvedPath;
        return String.format("arn:aws:execute-api:%s:%s:%s/%s/%s/%s",
            region, accountId, apiId, stage, httpMethod.toUpperCase(), path);
    }
}
