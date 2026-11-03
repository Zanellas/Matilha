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
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.matilha.notification.dto.TransferEventMessage;
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
    private static TransferEventMessage event(String type, int version, String failureCode) {
        return new TransferEventMessage(EVENT_ID, type, version, Instant.now(), TRANSFER_ID,
                SOURCE, TARGET, "250.50", failureCode);
    }

    @Nested
    class Completed {

        @Test
        void shouldCreateSentForSourceAndReceivedForTarget() {
            List<Notification> notifications = NotificationFactory.from(
                    event(TransferEventMessage.COMPLETED, 1, null));

            assertThat(notifications)
                    .extracting(Notification::getAccountNumber, Notification::getType, Notification::getFailureCode)
                    .containsExactlyInAnyOrder(
                            tuple(SOURCE, NotificationType.TRANSFER_SENT, null),
                            tuple(TARGET, NotificationType.TRANSFER_RECEIVED, null));
        }

        @Test
        void shouldKeepEventAndTransferIdsInEveryNotification() {
            List<Notification> notifications = NotificationFactory.from(
                    event(TransferEventMessage.COMPLETED, 1, null));

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
                    event(TransferEventMessage.FAILED, 1, "INSUFFICIENT_BALANCE"));

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
                    event(TransferEventMessage.FAILED, 1, failureCode));

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
                    event(TransferEventMessage.COMPLETED, 1, "INSUFFICIENT_BALANCE"));

            assertThat(notifications)
                    .extracting(Notification::getFailureCode)
                    .containsOnlyNulls();
        }
    }

    @Nested
    class Unsupported {

        @Test
        void shouldRejectNewerVersion() {
            TransferEventMessage event = event(TransferEventMessage.COMPLETED, 2, null);

            assertThatThrownBy(() -> NotificationFactory.from(event))
                    .isInstanceOf(UnsupportedEventException.class)
                    .hasMessageContaining("2");
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = { "", "TransferCreated", "transferCompleted" })
        void shouldRejectUnknownType(String type) {
            TransferEventMessage event = event(type, 1, null);

            assertThatThrownBy(() -> NotificationFactory.from(event))
                    .isInstanceOf(UnsupportedEventException.class);
        }
    }
}
