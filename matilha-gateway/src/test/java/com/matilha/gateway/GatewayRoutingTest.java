package com.matilha.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
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

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;

/**
 * Sobe o gateway de verdade, numa porta aleatória, com um servidor HTTP do JDK no lugar de cada serviço.
 * É a mesma técnica do AccountClientTest do transfer. Não precisa do Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayRoutingTest {

    private static final FakeService ACCOUNT = FakeService.start();
    private static final FakeService TRANSFER = FakeService.start();
    private static final FakeService NOTIFICATION = FakeService.start();

    private static final String IDEMPOTENCY_KEY = "8a2b4c6d-1e3f-4a5b-8c7d-9e0f1a2b3c4d";
    private static final String TRANSFER_ID = "c1d2e3f4-a5b6-4c7d-8e9f-0a1b2c3d4e5f";
    private static final String TRANSFER_REQUEST = """
            {"sourceAccountNumber":"10001","targetAccountNumber":"10002","amount":250.50}""";

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void serviceUrls(DynamicPropertyRegistry registry) {
        registry.add("matilha.account.base-url", ACCOUNT::url);
        registry.add("matilha.transfer.base-url", TRANSFER::url);
        registry.add("matilha.notification.base-url", NOTIFICATION::url);
    }

    @AfterAll
    static void stopServices() {
        ACCOUNT.stop();
        TRANSFER.stop();
        NOTIFICATION.stop();
    }

    @BeforeEach
    void resetServices() {
        ACCOUNT.reset();
        TRANSFER.reset();
        NOTIFICATION.reset();
    }

    @Nested
    class Routes {

        @Test
        void shouldRouteAccountsToAccountService() throws Exception {
            ACCOUNT.respond(200, "application/json", """
                    {"accountNumber":"10001","holderName":"Ana Silva","status":"ACTIVE"}""");

            HttpResponse<String> response = get("/accounts/10001");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("""
                    {"accountNumber":"10001","holderName":"Ana Silva","status":"ACTIVE"}""");
            assertThat(ACCOUNT.single().path()).isEqualTo("/accounts/10001");
            assertThat(TRANSFER.received()).isEmpty();
            assertThat(NOTIFICATION.received()).isEmpty();
        }

        @Test
        void shouldRouteTransfersToTransferService() throws Exception {
            TRANSFER.respond(200, "application/json", "{\"id\":\"" + TRANSFER_ID + "\"}");

            HttpResponse<String> response = get("/transfers/" + TRANSFER_ID);

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(TRANSFER.single().path()).isEqualTo("/transfers/" + TRANSFER_ID);
            assertThat(ACCOUNT.received()).isEmpty();
            assertThat(NOTIFICATION.received()).isEmpty();
        }

        @Test
        void shouldRouteNotificationsToNotificationServiceKeepingQuery() throws Exception {
            NOTIFICATION.respond(200, "application/json", "{\"content\":[]}");

            HttpResponse<String> response = get("/notifications?accountNumber=10001&page=1");

            assertThat(response.statusCode()).isEqualTo(200);
            Received received = NOTIFICATION.single();
            assertThat(received.path()).isEqualTo("/notifications");
            assertThat(received.query()).isEqualTo("accountNumber=10001&page=1");
            assertThat(ACCOUNT.received()).isEmpty();
            assertThat(TRANSFER.received()).isEmpty();
        }
    }

    /**
     * Lista de liberação (ADR-0012): o que não tem rota recebe 404 do próprio gateway e nunca chega a um serviço.
     */
    @Nested
    class Blocked {

        @ParameterizedTest
        @ValueSource(strings = { "/internal/transfers", "/internal/deposits", "/admin/accounts", "/accountsx" })
        void shouldReturnRouteNotFoundWithoutCallingAnyService(String path) throws Exception {
            HttpResponse<String> response = post(path, Map.of(), TRANSFER_REQUEST);

            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                    type -> assertThat(type).startsWith("application/problem+json"));
            assertThat(response.body())
                    .contains("\"code\":\"ROUTE_NOT_FOUND\"")
                    .contains("\"instance\":\"" + path + "\"");
            assertThat(ACCOUNT.received()).isEmpty();
            assertThat(TRANSFER.received()).isEmpty();
            assertThat(NOTIFICATION.received()).isEmpty();
        }
    }

    @Nested
    class ForwardedHeaders {

        @ParameterizedTest
        @ValueSource(ints = { 201, 202 })
        void shouldForwardIdempotencyKeyAndReturnLocation(int status) throws Exception {
            TRANSFER.respond(status, "application/json", "{\"id\":\"" + TRANSFER_ID + "\"}",
                    Map.of("Location", "/transfers/" + TRANSFER_ID));

            HttpResponse<String> response = post("/transfers",
                    Map.of("Idempotency-Key", IDEMPOTENCY_KEY), TRANSFER_REQUEST);

            assertThat(response.statusCode()).isEqualTo(status);
            assertThat(response.headers().firstValue("Location")).hasValue("/transfers/" + TRANSFER_ID);

            Received received = TRANSFER.single();
            assertThat(received.method()).isEqualTo("POST");
            assertThat(received.path()).isEqualTo("/transfers");
            assertThat(received.headers().getFirst("Idempotency-Key")).isEqualTo(IDEMPOTENCY_KEY);
            assertThat(received.headers().getFirst("Content-Type")).startsWith("application/json");
            assertThat(received.body()).isEqualTo(TRANSFER_REQUEST);
        }
    }

    /**
     * Erros dos serviços são respostas normais para o gateway: status, tipo e corpo passam sem alteração.
     */
    @Nested
    class ServiceErrors {

        @Test
        void shouldPassUnprocessableProblemDetailsUnchanged() throws Exception {
            String problem = """
                    {"type":"about:blank","title":"Unprocessable Content","status":422,\
                    "detail":"Insufficient balance","instance":"/transfers","code":"INSUFFICIENT_BALANCE"}""";
            TRANSFER.respond(422, "application/problem+json", problem);

            HttpResponse<String> response = post("/transfers",
                    Map.of("Idempotency-Key", IDEMPOTENCY_KEY), TRANSFER_REQUEST);

            assertThat(response.statusCode()).isEqualTo(422);
            assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
            assertThat(response.body()).isEqualTo(problem);
        }

        /**
         * O 404 do serviço (o recurso não existe) é diferente do 404 do gateway (a rota não existe).
         */
        @Test
        void shouldPassServiceNotFoundUnchanged() throws Exception {
            String problem = """
                    {"type":"about:blank","title":"Not Found","status":404,\
                    "detail":"Transfer not found: %s","instance":"/transfers/%s"}""".formatted(TRANSFER_ID, TRANSFER_ID);
            TRANSFER.respond(404, "application/problem+json", problem);

            HttpResponse<String> response = get("/transfers/" + TRANSFER_ID);

            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(response.body()).isEqualTo(problem).doesNotContain("ROUTE_NOT_FOUND");
        }
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(gatewayUri(path)).GET().build();
        return http.send(request, BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, Map<String, String> headers, String json)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(gatewayUri(path))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(json));
        headers.forEach(request::header);
        return http.send(request.build(), BodyHandlers.ofString());
    }

    private URI gatewayUri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private record Received(String method, String path, String query, Headers headers, String body) {
    }

    /**
     * Um serviço falso: guarda cada requisição recebida e devolve a resposta configurada.
     */
    private static final class FakeService {

        private final HttpServer server;
        private final List<Received> received = new CopyOnWriteArrayList<>();

        private volatile int status;
        private volatile String contentType;
        private volatile String body;
        private volatile Map<String, String> extraHeaders;

        private FakeService(HttpServer server) {
            this.server = server;
            reset();
        }

        static FakeService start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
                FakeService service = new FakeService(server);
                server.createContext("/", exchange -> {
                    service.received.add(new Received(
                            exchange.getRequestMethod(),
                            exchange.getRequestURI().getPath(),
                            exchange.getRequestURI().getRawQuery(),
                            new Headers(exchange.getRequestHeaders()),
                            new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));

                    byte[] bytes = service.body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", service.contentType);
                    service.extraHeaders.forEach(exchange.getResponseHeaders()::set);
                    exchange.sendResponseHeaders(service.status, bytes.length == 0 ? -1 : bytes.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                });
                server.start();
                return service;
            } catch (IOException ex) {
                throw new IllegalStateException("Could not start fake service", ex);
            }
        }

        String url() {
            return "http://localhost:" + server.getAddress().getPort();
        }

        void respond(int status, String contentType, String body) {
            respond(status, contentType, body, Map.of());
        }

        void respond(int status, String contentType, String body, Map<String, String> extraHeaders) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
            this.extraHeaders = extraHeaders;
        }

        List<Received> received() {
            return received;
        }

        Received single() {
            assertThat(received).hasSize(1);
            return received.getFirst();
        }

        void reset() {
            received.clear();
            respond(200, "application/json", "{}");
        }

        void stop() {
            server.stop(0);
        }
    }
}
