package com.haloticket.api_gateway.config;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DockerJwtDecoderTest {
    @Test
    void internalJwksValidatesPublicIssuerAndAudience() throws Exception {
        RSAKey key = new RSAKeyGenerator(2048).keyID("test-key").generate();
        byte[] jwks = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/certs", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, jwks.length);
            try (var output = exchange.getResponseBody()) { output.write(jwks); }
        });
        server.start();
        try {
            SecurityConfig config = new SecurityConfig();
            ReflectionTestUtils.setField(config, "issuerUri", "https://login.example.test/realms/haloticket");
            ReflectionTestUtils.setField(config, "jwkSetUri", "http://127.0.0.1:" + server.getAddress().getPort() + "/certs");
            var decoder = config.jwtDecoder();
            String valid = token(key, "https://login.example.test/realms/haloticket", "api-gateway");
            assertThat(decoder.decode(valid).block().getSubject()).isEqualTo("demo-user");
            String wrongIssuer = token(key, "https://untrusted.example.test", "api-gateway");
            assertThatThrownBy(() -> decoder.decode(wrongIssuer).block()).isInstanceOf(org.springframework.security.oauth2.jwt.JwtException.class);
            String wrongAudience = token(key, "https://login.example.test/realms/haloticket", "another-service");
            assertThatThrownBy(() -> decoder.decode(wrongAudience).block()).isInstanceOf(org.springframework.security.oauth2.jwt.JwtException.class);
        } finally {
            server.stop(0);
        }
    }

    private String token(RSAKey key, String issuer, String audience) throws Exception {
        Instant now = Instant.now();
        SignedJWT jwt = new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(key.getKeyID()).build(),
            new JWTClaimsSet.Builder().subject("demo-user").issuer(issuer).audience(audience)
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(120))).build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}

