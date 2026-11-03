package com.matilha.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.matilha.notification.dto.TransferEventMessageDto;
import com.matilha.notification.entity.FailureCode;
import com.matilha.notification.entity.Notification;
import com.matilha.notification.entity.NotificationType;
import com.matilha.notification.exception.UnsupportedEventException;

class NotificationFactoryTest {

    private static final UUID EVENT_ID = UUID.fromString("5d1e2f3a-4b5c-4d6e-8f70-8192a3b4c5d6");
    private static final UUID TRANSFER_ID = UUID.fromString("8a2b4c6d-1e3f-4a5b-8c7d-9e0f1a2b3c4d");
    private static final String SOURCE = "10001";
    private static final String TARGET = "10002";

    /** O failureCode chega como texto, do jeito que está no JSON. */
    private static TransferEventMessageDto event(String type, int version, String failureCode) {
        return event(type, failureCode, "250.50", version);
    }

    /** O amount também chega como texto: o transfer publica o BigDecimal com toPlainString(). */
    private static TransferEventMessageDto event(String type, String failureCode, String amount, int version) {
        return new TransferEventMessageDto(EVENT_ID, type, version, Instant.now(), TRANSFER_ID,
                SOURCE, TARGET, amount, failureCode);
    }

    private static String messageOf(List<Notification> notifications, NotificationType type) {
        return notifications.stream()
                .filter(notification -> notification.getType() == type)
                .map(Notification::getMessage)
                .findFirst()
                .orElseThrow();
    }

    @Nested
    class Completed {

        @Test
        void shouldCreateSentForSourceAndReceivedForTarget() {
            List<Notification> notifications = NotificationFactory.from(
                    event(TransferEventMessageDto.COMPLETED, 1, null));

            assertThat(notifications)
                    .extracting(Notification::getAccountNumber, Notification::getType, Notification::getFailureCode)
                    .containsExactlyInAnyOrder(
                            tuple(SOURCE, NotificationType.TRANSFER_SENT, null),
                            tuple(TARGET, NotificationType.TRANSFER_RECEIVED, null));
        }

        @Test
        void shouldKeepEventAndTransferIdsInEveryNotification() {
            List<Notification> notifications = NotificationFactory.from(
                    event(TransferEventMessageDto.COMPLETED, 1, null));

            assertThat(notifications).allSatisfy(notification -> {
                assertThat(notification.getEventId()).isEqualTo(EVENT_ID);
                assertThat(notification.getTransferId()).isEqualTo(TRANSFER_ID);
                assertThat(notification.getMessage()).isNotBlank();
            });
        }
    }

    @Nested
    class Failed {

        @Test
        void shouldCreateOneFailedNotificationForSourceWithCode() {
            List<Notification> notifications = NotificationFactory.from(
                    event(TransferEventMessageDto.FAILED, 1, "INSUFFICIENT_BALANCE"));

            assertThat(notifications)
                    .singleElement()
                    .satisfies(notification -> {
                        assertThat(notification.getAccountNumber()).isEqualTo(SOURCE);
                        assertThat(notification.getType()).isEqualTo(NotificationType.TRANSFER_FAILED);
                        assertThat(notification.getFailureCode()).isEqualTo(FailureCode.INSUFFICIENT_BALANCE);
                        assertThat(notification.getEventId()).isEqualTo(EVENT_ID);
                        assertThat(notification.getTransferId()).isEqualTo(TRANSFER_ID);
                    });
        }

        /**
         * Um código que este consumidor não conhece, ou nenhum código, não impede o aviso de falha:
         * o cliente recebe a notificação com o código genérico, como o transfer faz com o account (ADR-0007).
         */
        @ParameterizedTest
        @NullSource
        @ValueSource(strings = { "ACCOUNT_BLOCKED", "", "insufficient_balance" })
        void shouldUseGenericCodeWhenCodeIsUnknownOrMissing(String failureCode) {
            List<Notification> notifications = NotificationFactory.from(
                    event(TransferEventMessageDto.FAILED, 1, failureCode));

            assertThat(notifications)
                    .singleElement()
                    .satisfies(notification -> {
                        assertThat(notification.getType()).isEqualTo(NotificationType.TRANSFER_FAILED);
                        assertThat(notification.getFailureCode()).isEqualTo(FailureCode.REJECTED_BY_ACCOUNT);
                    });
        }

        /** O código só faz sentido na falha: num TransferCompleted ele é ignorado. */
        @Test
        void shouldIgnoreCodeInCompletedEvent() {
            List<Notification> notifications = NotificationFactory.from(
                    event(TransferEventMessageDto.COMPLETED, 1, "INSUFFICIENT_BALANCE"));

            assertThat(notifications)
                    .extracting(Notification::getFailureCode)
                    .containsOnlyNulls();
        }
    }

    /**
     * O valor aparece em reais, no formato brasileiro. O espaço depois do "R$" é um espaço comum: o
     * NumberFormat.getCurrencyInstance usaria um espaço não separável (U+00A0), que parece igual mas não é.
     */
    @Nested
    class Amount {

        @Test
        void shouldFormatAmountInEveryMessage() {
            List<Notification> completed = NotificationFactory.from(
                    event(TransferEventMessageDto.COMPLETED, 1, null));
            List<Notification> failed = NotificationFactory.from(
                    event(TransferEventMessageDto.FAILED, 1, "INSUFFICIENT_BALANCE"));

            assertThat(messageOf(completed, NotificationType.TRANSFER_SENT))
                    .isEqualTo("Você enviou R$ 250,50 para a conta 10002");
            assertThat(messageOf(completed, NotificationType.TRANSFER_RECEIVED))
                    .isEqualTo("Você recebeu R$ 250,50 da conta 10001");
            assertThat(messageOf(failed, NotificationType.TRANSFER_FAILED))
                    .isEqualTo("Sua transferência de R$ 250,50 para a conta 10002 falhou");
        }

        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {
                "1234567.89 | R$ 1.234.567,89",
                "1000       | R$ 1.000,00",
                "100        | R$ 100,00",
                "0.5        | R$ 0,50",
                "0.01       | R$ 0,01" })
        void shouldUseBrazilianSeparatorsAndTwoDecimals(String amount, String expected) {
            List<Notification> notifications = NotificationFactory.from(
                    event(TransferEventMessageDto.COMPLETED, null, amount, 1));

            assertThat(messageOf(notifications, NotificationType.TRANSFER_SENT))
                    .isEqualTo("Você enviou " + expected + " para a conta 10002")
                    .doesNotContain(" ");
        }

        /**
         * Um amount que não é número falha sempre, então é mensagem envenenada: UnsupportedEventException, que vai
         * direto para a DLT. Um NumberFormatException ou NullPointerException seria repetido para sempre e travaria
         * a partição. "1,50" fica de fora de propósito: o transfer publica com ponto, a vírgula é só da mensagem.
         */
        @ParameterizedTest
        @NullSource
        @ValueSource(strings = { "", "   ", "abc", "1,50", "250.50.1", "R$ 250,50" })
        void shouldRejectInvalidAmount(String amount) {
            TransferEventMessageDto event = event(TransferEventMessageDto.COMPLETED, null, amount, 1);

            assertThatThrownBy(() -> NotificationFactory.from(event))
                    .isInstanceOf(UnsupportedEventException.class)
                    .hasMessageContaining("amount");
        }

        /** A falha também mostra o valor, então um amount inválido num TransferFailed é recusado do mesmo jeito. */
        @Test
        void shouldRejectInvalidAmountInFailedEvent() {
            TransferEventMessageDto event = event(TransferEventMessageDto.FAILED, "INSUFFICIENT_BALANCE", "abc", 1);

            assertThatThrownBy(() -> NotificationFactory.from(event))
                    .isInstanceOf(UnsupportedEventException.class);
        }
    }

    @Nested
    class Unsupported {

        @Test
        void shouldRejectNewerVersion() {
            TransferEventMessageDto event = event(TransferEventMessageDto.COMPLETED, 2, null);

            assertThatThrownBy(() -> NotificationFactory.from(event))
                    .isInstanceOf(UnsupportedEventException.class)
                    .hasMessageContaining("2");
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = { "", "TransferCreated", "transferCompleted" })
        void shouldRejectUnknownType(String type) {
            TransferEventMessageDto event = event(type, 1, null);

            assertThatThrownBy(() -> NotificationFactory.from(event))
                    .isInstanceOf(UnsupportedEventException.class);
        }
    }
}
