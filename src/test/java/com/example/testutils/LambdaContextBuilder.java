package com.example.testutils;

import com.amazonaws.services.lambda.runtime.ClientContext;
import com.amazonaws.services.lambda.runtime.CognitoIdentity;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;

public class LambdaContextBuilder {

    public static Context createContext(String functionName, String functionVersion, String awsRequestId) {
        return new TestContext(functionName, functionVersion, awsRequestId);
    }

    private static class TestContext implements Context {
        private final String functionName;
        private final String functionVersion;
        private final String awsRequestId;
        private final String logGroupName;
        private final String logStreamName;
        private final String memoryLimit;
        private final int remainingTimeInMillis;

        public TestContext(String functionName, String functionVersion, String awsRequestId) {
            this.functionName = functionName;
            this.functionVersion = functionVersion;
            this.awsRequestId = awsRequestId;
            this.logGroupName = "/aws/lambda/" + functionName;
            this.logStreamName = "2024/01/01/[$LATEST]" + awsRequestId;
            this.memoryLimit = "128";
            this.remainingTimeInMillis = 30000;
        }

        @Override
        public String getAwsRequestId() {
            return awsRequestId;
        }

        @Override
        public String getLogGroupName() {
            return logGroupName;
        }

        @Override
        public String getLogStreamName() {
            return logStreamName;
        }

        @Override
        public String getFunctionName() {
            return functionName;
        }

        @Override
        public String getFunctionVersion() {
            return functionVersion;
        }

        @Override
        public String getInvokedFunctionArn() {
            return "arn:aws:lambda:us-east-1:123456789012:function:" + functionName;
        }

        @Override
        public CognitoIdentity getIdentity() {
            return null;
        }

        @Override
        public int getMemoryLimitInMB() {
            return Integer.parseInt(memoryLimit);
        }

        @Override
        public int getRemainingTimeInMillis() {
            return remainingTimeInMillis;
        }

        @Override
        public LambdaLogger getLogger() {
            return new TestLogger();
        }

        @Override
        public ClientContext getClientContext() {
            return null;
        }

        private static class TestLogger implements LambdaLogger {
            @Override
            public void log(String message) {
                System.out.println(message);
            }

            @Override
            public void log(byte[] message) {
                System.out.println(new String(message));
            }
        }
    }
}
