package com.matilha.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Os três serviços apontam para portas fechadas: toda chamada termina em conexão recusada,
 * como quando o account é derrubado.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayServiceUnavailableTest {

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @LocalServerPort
    private int port;

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
        HttpResponse<String> response = get(path);

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
     */
    @Test
    void shouldStayUpWhenServicesAreDown() throws Exception {
        HttpResponse<String> response = get("/actuator/health/readiness");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        return http.send(request, BodyHandlers.ofString());
    }

    private static String closedPortUrl() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return "http://localhost:" + socket.getLocalPort();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
