package com.matilha.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Os três serviços apontam para portas fechadas: toda chamada termina em conexão recusada,
 * como quando o account é derrubado. O JwtDecoder é um mock: o assunto aqui não é o token.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayServiceUnavailableTest {

    private static final String TOKEN = "test-token";

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @LocalServerPort
    private int port;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void acceptAnyToken() {
        given(jwtDecoder.decode(anyString())).willReturn(Jwt.withTokenValue(TOKEN)
                .header("alg", "RS256")
                .subject("00000000-0000-0000-0003-000000000001")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build());
    }

    @DynamicPropertySource
    static void offlineServiceUrls(DynamicPropertyRegistry registry) {
        registry.add("matilha.account.base-url", GatewayServiceUnavailableTest::closedPortUrl);
        registry.add("matilha.transfer.base-url", GatewayServiceUnavailableTest::closedPortUrl);
        registry.add("matilha.notification.base-url", GatewayServiceUnavailableTest::closedPortUrl);
    }

    @ParameterizedTest
    @ValueSource(strings = { "/accounts/10001", "/transfers/c1d2e3f4-a5b6-4c7d-8e9f-0a1b2c3d4e5f",
            "/notifications?accountNumber=10001" })
    void shouldReturnServiceUnavailableAsProblemDetails(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + TOKEN)
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
        assertThat(response.body())
                .contains("\"code\":\"SERVICE_UNAVAILABLE\"")
                .contains("\"status\":503")
                .doesNotContain("Connection refused")
                .doesNotContain("localhost");
    }

    /**
     * O health do gateway não depende dos serviços (ADR-0012). Se ficasse DOWN, o Kubernetes tiraria
     * o gateway do balanceamento e todas as rotas parariam por causa de um serviço só.
     * A chamada vai sem token, como a probe do Kubernetes.
     */
    @Test
    void shouldStayUpWhenServicesAreDown() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/actuator/health/readiness")).GET().build();
        HttpResponse<String> response = http.send(request, BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    private static String closedPortUrl() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return "http://localhost:" + socket.getLocalPort();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
