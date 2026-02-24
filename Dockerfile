
# Lambda supports two types of deployment packages: .zip file archives and container images
# This will create a container image, which has to be uploaded to Amazon Elastic Container Registry
# Permissions for the user or role that creates the function must include GetRepositoryPolicy, SetRepositoryPolicy, BatchGetImage, and GetDownloadUrlForLayer
#
# AWS base images are preloaded with a language runtime, and a runtime interface client to manage the interaction between Lambda and your function code
# You don't need to specify a USER in your Dockerfile
#   - Lambda follows security best practices by automatically defining a default Linux user with least-privileged permissions
#   - The default Lambda user must be able to read all the files required to run your function code
# Amazon Linux 2023 minimal container image
#   - package manager: microdnf (symlinked as dnf)
#   - you must use Docker version 20.10.10 or later


############################
# 1) Build stage (JDK 25 + Maven)
############################
FROM maven:3.9.12-eclipse-temurin-25-noble AS build

WORKDIR /workspace

ENV MAVEN_OPTS="-Dmaven.repo.local=/workspace/.m2"

COPY pom.xml ./

RUN --mount=type=cache,target=/workspace/.m2 \
    mvn -q -e -DskipTests dependency:go-offline

COPY src ./src

RUN --mount=type=cache,target=/workspace/.m2 \
    mvn -q -DskipTests package

RUN mkdir -p /out && \
    cp target/lambda-oidc-authorizer.jar /out/lambda-oidc-authorizer.jar

############################
# 2) Runtime stage (AWS Lambda Java base image)
############################
FROM public.ecr.aws/lambda/java:25 AS runtime

COPY --from=build /out/lambda-oidc-authorizer.jar ${LAMBDA_TASK_ROOT}/lambda-oidc-authorizer.jar

# Set the CMD to your handler (could also be done as a parameter override outside of the Dockerfile)
CMD ["com.example.OidcAuthorizerHandler::handleRequest"]
