package com.example.testutils;

import com.amazonaws.services.lambda.runtime.events.APIGatewayCustomAuthorizerEvent;

public class ApiGatewayEventBuilder {

    public static APIGatewayCustomAuthorizerEvent createTokenAuthorizerEvent(String authorizationToken, String methodArn) {
        APIGatewayCustomAuthorizerEvent event = new APIGatewayCustomAuthorizerEvent();

        event.setType("TOKEN");
        event.setAuthorizationToken("Bearer " + authorizationToken);
        event.setMethodArn(methodArn);

        return event;
    }

}
