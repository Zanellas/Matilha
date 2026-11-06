package com.matilha.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

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
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.sun.net.httpserver.HttpServer;

/**
 * Confere que o read-timeout de spring.http.clients chega de verdade ao cliente HTTP do gateway.
 *
 * O teste troca os 10s do application.yml por 1s, para não demorar, e o serviço falso demora 5s para responder.
 * Se a propriedade não valesse (nome errado, por exemplo), o gateway esperaria os 5s e devolveria 200.
 * Com ela valendo, o gateway desiste em 1s e devolve 503.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.http.clients.read-timeout=1s")
class GatewayTimeoutTest {

    private static final long SLOW_RESPONSE_MILLIS = 5_000;

    private static final AtomicInteger RECEIVED = new AtomicInteger();
    private static final HttpServer SLOW_ACCOUNT = startSlowService();

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @LocalServerPort
    private int port;

    /** O assunto aqui não é o token: o mock aceita qualquer um. */
    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void acceptAnyToken() {
        given(jwtDecoder.decode(anyString())).willReturn(Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .subject("00000000-0000-0000-0003-000000000001")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build());
    }

    @DynamicPropertySource
    static void serviceUrls(DynamicPropertyRegistry registry) {
        registry.add("matilha.account.base-url", () -> "http://localhost:" + SLOW_ACCOUNT.getAddress().getPort());
    }

    @AfterAll
    static void stopService() {
        SLOW_ACCOUNT.stop(0);
    }

    @Test
    void shouldReturnServiceUnavailableWhenServiceIsSlowerThanReadTimeout() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/accounts/10001"))
                .header("Authorization", "Bearer test-token")
                .timeout(Duration.ofSeconds(4))
                .GET()
                .build();

        long start = System.nanoTime();
        HttpResponse<String> response = http.send(request, BodyHandlers.ofString());
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(RECEIVED.get()).as("a requisição chegou ao serviço").isEqualTo(1);
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
        assertThat(response.body()).contains("\"code\":\"SERVICE_UNAVAILABLE\"");
        assertThat(elapsed).as("o gateway desistiu antes do serviço responder")
                .isLessThan(Duration.ofMillis(SLOW_RESPONSE_MILLIS));
    }

    private static HttpServer startSlowService() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                RECEIVED.incrementAndGet();
                sleep(SLOW_RESPONSE_MILLIS);
                byte[] body = "{\"accountNumber\":\"10001\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                } catch (IOException ignored) {
                    // o gateway já fechou a conexão: é exatamente o que o teste espera
                }
            });
            server.start();
            return server;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
