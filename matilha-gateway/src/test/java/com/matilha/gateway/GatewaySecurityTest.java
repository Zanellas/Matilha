package com.matilha.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nimbusds.jose.JOSEException;
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

/**
 * Valida o token de verdade, sem mock: o teste faz o papel do Keycloak.
 *
 * Ele gera um par de chaves RSA, publica a chave pública num JWKS servido por um servidor do JDK
 * e assina os tokens com a chave privada. O gateway baixa o JWKS pelo jwk-set-uri, como faz com o Keycloak,
 * e confere assinatura, validade, issuer e audiência (matilha-api, do application.yml).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewaySecurityTest {

    private static final String ISSUER = "http://localhost:8180/realms/matilha";
    private static final String AUDIENCE = "matilha-api";
    private static final String ANA = "00000000-0000-0000-0003-000000000001";

    private static final RSAKey SIGNING_KEY = generateKey("matilha-key");
    private static final RSAKey UNKNOWN_KEY = generateKey("unknown-key");

    /** O header Authorization de cada requisição que chegou ao account falso. */
    private static final List<String> ACCOUNT_AUTHORIZATIONS = new CopyOnWriteArrayList<>();

    private static final HttpServer KEYCLOAK = startJwks();
    private static final HttpServer ACCOUNT = startAccount();

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> "http://localhost:" + KEYCLOAK.getAddress().getPort() + "/certs");
        registry.add("matilha.account.base-url", () -> "http://localhost:" + ACCOUNT.getAddress().getPort());
    }

    @AfterAll
    static void stopServers() {
        KEYCLOAK.stop(0);
        ACCOUNT.stop(0);
    }

    @BeforeEach
    void resetAccount() {
        ACCOUNT_AUTHORIZATIONS.clear();
    }

    @Nested
    class ValidToken {

        @Test
        void shouldRouteAndForwardTheSameToken() throws Exception {
            String token = token(SIGNING_KEY, ISSUER, AUDIENCE, Instant.now().plusSeconds(300));

            HttpResponse<String> response = get("/accounts/10001", token);

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(ACCOUNT_AUTHORIZATIONS).containsExactly("Bearer " + token);
        }

        /**
         * Com token, um caminho sem rota continua 404: a segurança não substitui a lista de liberação.
         */
        @Test
        void shouldStillReturnRouteNotFoundForInternalPaths() throws Exception {
            String token = token(SIGNING_KEY, ISSUER, AUDIENCE, Instant.now().plusSeconds(300));

            HttpResponse<String> response = get("/internal/deposits", token);

            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(response.body()).contains("\"code\":\"ROUTE_NOT_FOUND\"");
            assertThat(ACCOUNT_AUTHORIZATIONS).isEmpty();
        }
    }

    @Nested
    class RejectedToken {

        @Test
        void shouldRejectMissingToken() throws Exception {
            assertUnauthenticated(get("/accounts/10001", null));
        }

        @Test
        void shouldRejectMalformedToken() throws Exception {
            assertUnauthenticated(get("/accounts/10001", "abc.def.ghi"));
        }

        /**
         * O Spring tolera 60 segundos de diferença de relógio, por isso o token venceu há 5 minutos.
         */
        @Test
        void shouldRejectExpiredToken() throws Exception {
            String token = token(SIGNING_KEY, ISSUER, AUDIENCE, Instant.now().minusSeconds(300));

            assertUnauthenticated(get("/accounts/10001", token));
        }

        /**
         * Como um token do realm master: assinatura válida, mas emitido por outro issuer.
         */
        @Test
        void shouldRejectTokenFromAnotherIssuer() throws Exception {
            String token = token(SIGNING_KEY, "http://localhost:8180/realms/master", AUDIENCE,
                    Instant.now().plusSeconds(300));

            assertUnauthenticated(get("/accounts/10001", token));
        }

        /**
         * Emitido pelo issuer certo, mas para outro sistema.
         */
        @Test
        void shouldRejectTokenForAnotherAudience() throws Exception {
            String token = token(SIGNING_KEY, ISSUER, "outro-sistema", Instant.now().plusSeconds(300));

            assertUnauthenticated(get("/accounts/10001", token));
        }

        /**
         * Um token forjado: as claims estão certas, mas a chave não é a publicada no JWKS.
         */
        @Test
        void shouldRejectTokenSignedWithUnknownKey() throws Exception {
            String token = token(UNKNOWN_KEY, ISSUER, AUDIENCE, Instant.now().plusSeconds(300));

            assertUnauthenticated(get("/accounts/10001", token));
        }

        /**
         * Seguro por padrão: sem token, até um caminho sem rota recebe 401 antes do 404.
         */
        @Test
        void shouldRejectInternalPathWithoutToken() throws Exception {
            assertUnauthenticated(get("/internal/deposits", null));
        }
    }

    /**
     * As probes do Kubernetes chamam o health sem token.
     */
    @ParameterizedTest
    @ValueSource(strings = { "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness" })
    void shouldKeepHealthPublic(String path) throws Exception {
        HttpResponse<String> response = get(path, null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
    }

    private void assertUnauthenticated(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/problem+json"));
        assertThat(response.headers().firstValue("WWW-Authenticate")).hasValue("Bearer");
        assertThat(response.headers().firstValue("Set-Cookie")).as("sem sessão HTTP").isEmpty();
        assertThat(response.body())
                .contains("\"code\":\"UNAUTHENTICATED\"")
                .contains("\"status\":401")
                .as("o motivo da recusa fica só no log").doesNotContain("Jwt", "expired", "Malformed");
        assertThat(ACCOUNT_AUTHORIZATIONS).as("a requisição não chegou ao serviço").isEmpty();
    }

    private HttpResponse<String> get(String path, String token) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), BodyHandlers.ofString());
    }

    private static String token(RSAKey key, String issuer, String audience, Instant expiresAt) {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(ANA)
                .audience(audience)
                .issueTime(Date.from(expiresAt.minusSeconds(300)))
                .expirationTime(Date.from(expiresAt))
                .claim("preferred_username", "ana")
                .build();
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(JOSEObjectType.JWT)
                .keyID(key.getKeyID())
                .build();
        try {
            SignedJWT jwt = new SignedJWT(header, claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static RSAKey generateKey(String keyId) {
        try {
            return new RSAKeyGenerator(2048).keyID(keyId).generate();
        } catch (JOSEException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * O Keycloak falso: publica só a chave pública da SIGNING_KEY, no mesmo formato do endpoint /certs.
     */
    private static HttpServer startJwks() {
        byte[] jwks = new JWKSet(SIGNING_KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        return startServer(jwks, false);
    }

    private static HttpServer startAccount() {
        byte[] body = "{\"accountNumber\":\"10001\"}".getBytes(StandardCharsets.UTF_8);
        return startServer(body, true);
    }

    private static HttpServer startServer(byte[] body, boolean recordAuthorization) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                if (recordAuthorization) {
                    ACCOUNT_AUTHORIZATIONS.add(exchange.getRequestHeaders().getFirst("Authorization"));
                }
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
