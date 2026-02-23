package com.example;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayCustomAuthorizerEvent;
import com.example.model.RestApiGwAuthorizerResponse;

public class OidcAuthorizerHandler implements RequestHandler<APIGatewayCustomAuthorizerEvent, RestApiGwAuthorizerResponse> {

    @Override
    public RestApiGwAuthorizerResponse handleRequest(APIGatewayCustomAuthorizerEvent event, Context context) {
        context.getLogger().log("Received request: " + event.toString());

        return RestApiGwAuthorizerResponse.builder("test-principalId")
            .allowMethodArn(event.getMethodArn())
            .build();
    }
}
