package com.matilha.transfer.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.matilha.transfer.dto.AccountTransferRequestDto;
import com.matilha.transfer.dto.AccountTransferResponseDto;
import com.matilha.transfer.exception.AccountTransferRejectedException;
import com.matilha.transfer.exception.AccountUnavailableException;
import com.sun.net.httpserver.HttpServer;

/**
 * Usa um servidor HTTP de verdade (o do JDK) no lugar do account. O MockRestServiceServer não serve aqui
 * porque o AccountClient troca o requestFactory do builder para configurar os timeouts.
 */
class AccountClientTest {

    private static final UUID TRANSFER_ID = UUID.fromString("8a2b4c6d-1e3f-4a5b-8c7d-9e0f1a2b3c4d");
    private static final AccountTransferRequestDto REQUEST =
            new AccountTransferRequestDto(TRANSFER_ID, "10001", "10002", new BigDecimal("250.50"));

    private static final String SUCCESS_BODY = """
            {
              "transferId": "8a2b4c6d-1e3f-4a5b-8c7d-9e0f1a2b3c4d",
              "sourceAccountNumber": "10001",
              "sourceBalanceAfter": 1249.50,
              "targetAccountNumber": "10002",
              "targetBalanceAfter": 500.50,
              "amount": 250.50
            }
            """;

    private HttpServer server;
    private AccountClient client;

    private int responseStatus;
    private String responseBody;
    private String responseContentType;
    private long responseDelayMillis;

    private String receivedMethod;
    private String receivedPath;
    private String receivedContentType;
    private String receivedBody;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            receivedMethod = exchange.getRequestMethod();
            receivedPath = exchange.getRequestURI().getPath();
            receivedContentType = exchange.getRequestHeaders().getFirst("Content-Type");
            receivedBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

            sleep(responseDelayMillis);

            byte[] bytes = responseBody == null ? new byte[0] : responseBody.getBytes(StandardCharsets.UTF_8);
            if (responseContentType != null) {
                exchange.getResponseHeaders().set("Content-Type", responseContentType);
            }
            exchange.sendResponseHeaders(responseStatus, bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();

        client = new AccountClient(RestClient.builder(), "http://localhost:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void respond(int status, String contentType, String body) {
        this.responseStatus = status;
        this.responseContentType = contentType;
        this.responseBody = body;
    }

    private void respondWithProblem(int status, String detail) {
        respond(status, "application/problem+json", """
                {"type": "about:blank", "status": %d, "detail": "%s", "instance": "/internal/transfers"}
                """.formatted(status, detail));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    @Nested
    class Success {

        @Test
        void shouldReturnAccountResponse() {
            respond(200, "application/json", SUCCESS_BODY);

            AccountTransferResponseDto response = client.transfer(REQUEST);

            assertThat(response.transferId()).isEqualTo(TRANSFER_ID);
            assertThat(response.sourceAccountNumber()).isEqualTo("10001");
            assertThat(response.sourceBalanceAfter()).isEqualByComparingTo("1249.50");
            assertThat(response.targetAccountNumber()).isEqualTo("10002");
            assertThat(response.targetBalanceAfter()).isEqualByComparingTo("500.50");
            assertThat(response.amount()).isEqualByComparingTo("250.50");
        }

        @Test
        void shouldPostJsonToInternalTransfers() {
            respond(200, "application/json", SUCCESS_BODY);

            client.transfer(REQUEST);

            assertThat(receivedMethod).isEqualTo("POST");
            assertThat(receivedPath).isEqualTo("/internal/transfers");
            assertThat(receivedContentType).startsWith("application/json");
            assertThat(receivedBody)
                    .contains("\"transferId\":\"8a2b4c6d-1e3f-4a5b-8c7d-9e0f1a2b3c4d\"")
                    .contains("\"sourceAccountNumber\":\"10001\"")
                    .contains("\"targetAccountNumber\":\"10002\"")
                    .contains("\"amount\":250.50");
        }
    }

    @Nested
    class Rejected {

        @Test
        void shouldRejectWhenAccountIsNotFound() {
            respondWithProblem(404, "Account not found: 10002");

            assertThatThrownBy(() -> client.transfer(REQUEST))
                    .isInstanceOf(AccountTransferRejectedException.class)
                    .hasMessage("Account not found: 10002");
        }

        @Test
        void shouldRejectWhenBusinessRuleIsViolated() {
            respondWithProblem(422, "Insufficient balance");

            assertThatThrownBy(() -> client.transfer(REQUEST))
                    .isInstanceOf(AccountTransferRejectedException.class)
                    .hasMessage("Insufficient balance");
        }

        @Test
        void shouldUseFallbackReasonWhenBodyIsEmpty() {
            respond(422, null, null);

            assertThatThrownBy(() -> client.transfer(REQUEST))
                    .isInstanceOf(AccountTransferRejectedException.class)
                    .hasMessage("Rejected by account service (422)");
        }

        @Test
        void shouldUseFallbackReasonWhenProblemHasNoDetail() {
            respond(422, "application/problem+json", """
                    {"type": "about:blank", "status": 422}
                    """);

            assertThatThrownBy(() -> client.transfer(REQUEST))
                    .isInstanceOf(AccountTransferRejectedException.class)
                    .hasMessage("Rejected by account service (422)");
        }

        @Test
        void shouldUseFallbackReasonWhenDetailIsBlank() {
            respondWithProblem(422, "   ");

            assertThatThrownBy(() -> client.transfer(REQUEST))
                    .isInstanceOf(AccountTransferRejectedException.class)
                    .hasMessage("Rejected by account service (422)");
        }

        @Test
        void shouldUseFallbackReasonWhenBodyIsNotJson() {
            respond(404, "text/html", "<html>Not Found</html>");

            assertThatThrownBy(() -> client.transfer(REQUEST))
                    .isInstanceOf(AccountTransferRejectedException.class)
                    .hasMessage("Rejected by account service (404)");
        }

        @Test
        void shouldUseFallbackReasonWhenJsonIsMalformed() {
            respond(422, "application/problem+json", "{ not json");

            assertThatThrownBy(() -> client.transfer(REQUEST))
                    .isInstanceOf(AccountTransferRejectedException.class)
                    .hasMessage("Rejected by account service (422)");
        }
    }

    @Nested
    class Unavailable {

        @Test
        void shouldTreatConflictAsUnavailable() {
            respondWithProblem(409, "The account was modified by another request. Please try again.");

            assertThatThrownBy(() -> client.transfer(REQUEST))
                    .isInstanceOf(AccountUnavailableException.class)
                    .hasMessageContaining("409");
        }

        @Test
        void shouldTreatOtherClientErrorsAsUnavailable() {
            respondWithProblem(400, "Invalid request");

            assertThatThrownBy(() -> client.transfer(REQUEST))
                    .isInstanceOf(AccountUnavailableException.class)
                    .hasMessageContaining("400");
        }

        @ParameterizedTest
        @ValueSource(ints = { 500, 502, 503 })
        void shouldTreatServerErrorsAsUnavailable(int status) {
            respondWithProblem(status, "An unexpected error occurred");

            assertThatThrownBy(() -> client.transfer(REQUEST))
                    .isInstanceOf(AccountUnavailableException.class)
                    .hasMessage("Account service unavailable")
                    .hasCauseInstanceOf(HttpServerErrorException.class);
        }

        @Test
        void shouldTreatConnectionRefusedAsUnavailable() throws IOException {
            int closedPort;
            try (ServerSocket socket = new ServerSocket(0)) {
                closedPort = socket.getLocalPort();
            }
            AccountClient offline = new AccountClient(RestClient.builder(), "http://localhost:" + closedPort);

            assertThatThrownBy(() -> offline.transfer(REQUEST))
                    .isInstanceOf(AccountUnavailableException.class)
                    .hasCauseInstanceOf(ResourceAccessException.class);
        }

        /**
         * Demora ~5s: espera o read timeout configurado no AccountClient estourar de verdade.
         */
        @Test
        void shouldTreatReadTimeoutAsUnavailable() {
            respond(200, "application/json", SUCCESS_BODY);
            responseDelayMillis = 6_000;

            assertThatThrownBy(() -> client.transfer(REQUEST))
                    .isInstanceOf(AccountUnavailableException.class)
                    .hasRootCauseInstanceOf(SocketTimeoutException.class);
        }

        /**
         * Um 200 que não dá para ler significa que o account pode ter movido o dinheiro.
         * Por isso não é "rejeitada": fica pendente e o reenvio com o mesmo transferId é seguro.
         */
        @Test
        void shouldTreatUnreadableSuccessBodyAsUnavailable() {
            respond(200, "application/json", "{ not json");

            assertThatThrownBy(() -> client.transfer(REQUEST))
                    .isInstanceOf(AccountUnavailableException.class)
                    .hasCauseInstanceOf(RestClientException.class);
        }
    }
}
