package com.matilha.notification.component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;

import com.matilha.notification.TestcontainersConfiguration;
import com.matilha.notification.config.KafkaConfig;
import com.matilha.notification.dto.TransferEventMessage;
import com.matilha.notification.entity.FailureCode;
import com.matilha.notification.entity.Notification;
import com.matilha.notification.entity.NotificationType;
import com.matilha.notification.repository.NotificationRepository;
import com.matilha.notification.repository.ProcessedEventRepository;

/**
 * Publica JSON de verdade no tópico, do mesmo jeito que o transfer publica, e espera o consumidor gravar.
 * O banco não é limpo entre os testes: cada teste usa ids novos e só olha as notificações da sua transferência.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TransferEventListenerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String SOURCE = "10001";
    private static final String TARGET = "10002";

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Test
    void shouldCreateSentAndReceivedForCompletedEvent() {
        UUID eventId = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();

        send(json(eventId, TransferEventMessage.COMPLETED, transferId, null));

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(notificationsOf(transferId))
                .extracting(Notification::getAccountNumber, Notification::getType, Notification::getFailureCode)
                .containsExactlyInAnyOrder(
                        tuple(SOURCE, NotificationType.TRANSFER_SENT, null),
                        tuple(TARGET, NotificationType.TRANSFER_RECEIVED, null)));
        assertThat(processedEventRepository.existsById(eventId)).isTrue();
    }

    @Test
    void shouldCreateOneFailedNotificationWithCode() {
        UUID transferId = UUID.randomUUID();

        send(json(UUID.randomUUID(), TransferEventMessage.FAILED, transferId, "ACCOUNT_INACTIVE"));

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(notificationsOf(transferId))
                .extracting(Notification::getAccountNumber, Notification::getType, Notification::getFailureCode)
                .containsExactly(tuple(SOURCE, NotificationType.TRANSFER_FAILED, FailureCode.ACCOUNT_INACTIVE)));
    }

    /** Um código novo do transfer não leva o evento para a DLT: o cliente é avisado com o código genérico. */
    @Test
    void shouldNotifyFailureWithGenericCodeWhenCodeIsUnknown() {
        UUID transferId = UUID.randomUUID();

        send(json(UUID.randomUUID(), TransferEventMessage.FAILED, transferId, "ACCOUNT_BLOCKED"));

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(notificationsOf(transferId))
                .extracting(Notification::getType, Notification::getFailureCode)
                .containsExactly(tuple(NotificationType.TRANSFER_FAILED, FailureCode.REJECTED_BY_ACCOUNT)));
    }

    /**
     * Como saber que a segunda cópia já foi consumida, se ela não deve gravar nada? Depois dela vai um evento
     * "marcador" com a mesma chave. Mesma chave cai na mesma partição, e a partição é lida em ordem:
     * quando o marcador aparecer no banco, a cópia repetida com certeza já passou pelo consumidor.
     */
    @Test
    void shouldSaveNotificationsOnlyOnceWhenEventIsDeliveredTwice() {
        UUID transferId = UUID.randomUUID();
        String payload = json(UUID.randomUUID(), TransferEventMessage.COMPLETED, transferId, null);
        UUID markerTransferId = UUID.randomUUID();

        send(payload);
        send(payload);
        send(json(UUID.randomUUID(), TransferEventMessage.COMPLETED, markerTransferId, null));

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(notificationsOf(markerTransferId)).hasSize(2));
        assertThat(notificationsOf(transferId)).hasSize(2);
    }

    /** O transfer pode acrescentar campos ao evento sem quebrar este consumidor. */
    @Test
    void shouldIgnoreUnknownFields() {
        UUID transferId = UUID.randomUUID();
        String payload = json(UUID.randomUUID(), TransferEventMessage.COMPLETED, transferId, null)
                .replaceFirst("\\{", "{\"channel\":\"pix\",\"metadata\":{\"origin\":\"app\"},");

        send(payload);

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(notificationsOf(transferId)).hasSize(2));
    }

    /** O mesmo formato que o transfer publica: Instant como texto ISO, amount como texto, enum pelo nome. */
    private static String json(UUID eventId, String eventType, UUID transferId, String failureCode) {
        String code = failureCode == null ? "null" : "\"" + failureCode + "\"";
        return """
                {"eventId":"%s","eventType":"%s","eventVersion":1,"occurredAt":"2026-11-02T12:00:00Z",\
                "transferId":"%s","sourceAccountNumber":"%s","targetAccountNumber":"%s",\
                "amount":"250.50","failureCode":%s}"""
                .formatted(eventId, eventType, transferId, SOURCE, TARGET, code);
    }

    /** A chave é a conta de origem, como no transfer. */
    private void send(String payload) {
        kafkaTemplate.send(KafkaConfig.TRANSFER_EVENTS_TOPIC, SOURCE, payload).join();
    }

    private List<Notification> notificationsOf(UUID transferId) {
        return notificationRepository.findAll().stream()
                .filter(notification -> notification.getTransferId().equals(transferId))
                .toList();
    }
}
