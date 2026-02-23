package com.example.testutils;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

public class MockOidcServer {

    private static final String JWKS_PATH = "/mock-oidc-server/jwks.json";

    private static final String PRIVATE_KEY_PATH = "/mock-oidc-server/rs512-private.pem";

    private final RSAPrivateKey privateKey;
    private final WireMockServer wireMockServer;
    private final String jwksUrl;

    public MockOidcServer() {
        this.privateKey = loadPrivateKey();
        this.wireMockServer = new WireMockServer(WireMockConfiguration.options().port(0));
        setupJwksEndpoint();
        this.wireMockServer.start();
        this.jwksUrl = wireMockServer.baseUrl() + "/.well-known/jwks.json";
    }

    public String getJwksUrl() {
        return jwksUrl;
    }

    public String createSignedAccessToken(String subject, String issuer, String audience) {
        try {
            JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                    .subject(subject)
                    .issuer(issuer)
                    .audience(audience)
                    .jwtID(UUID.randomUUID().toString())
                    .issueTime(Date.from(Instant.now()))
                    .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
                    .claim("scope", "openid profile email")
                    .build();

            JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS512)
                    .keyID("test-key-id")
                    .build();

            SignedJWT signedJWT = new SignedJWT(header, claimsSet);
            RSASSASigner signer = new RSASSASigner(privateKey);
            signedJWT.sign(signer);

            return signedJWT.serialize();
        } catch (JOSEException e) {
            throw new RuntimeException("Failed to sign JWT", e);
        }
    }

    private RSAPrivateKey loadPrivateKey() {
        try (InputStream inputStream = getClass().getResourceAsStream(PRIVATE_KEY_PATH)) {
            if (inputStream == null) {
                throw new RuntimeException("Private key file not found: " + PRIVATE_KEY_PATH);
            }

            String privateKeyPem = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            String privateKeyContent = privateKeyPem
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");

            byte[] decodedKey = Base64.getDecoder().decode(privateKeyContent);
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(decodedKey);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");

            return (RSAPrivateKey) keyFactory.generatePrivate(keySpec);
        } catch (IOException | NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new RuntimeException("Failed to load private key", e);
        }
    }

    private void setupJwksEndpoint() {
        try (InputStream inputStream = getClass().getResourceAsStream(JWKS_PATH)) {
            if (inputStream == null) {
                throw new RuntimeException("JWKS file not found: " + JWKS_PATH);
            }

            String jwksContent = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);

            wireMockServer.stubFor(WireMock.get(WireMock.urlPathEqualTo("/.well-known/jwks.json"))
                    .willReturn(WireMock.aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(jwksContent)));
        } catch (IOException e) {
            throw new RuntimeException("Failed to setup JWKS endpoint", e);
        }
    }

    public void stop() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }
}
