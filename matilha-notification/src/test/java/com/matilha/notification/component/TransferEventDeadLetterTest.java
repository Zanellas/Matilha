package com.matilha.notification.component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;

import com.matilha.notification.TestcontainersConfiguration;
import com.matilha.notification.config.KafkaConfig;
import com.matilha.notification.dto.TransferEventMessageDto;
import com.matilha.notification.entity.Notification;
import com.matilha.notification.repository.NotificationRepository;
import com.matilha.notification.repository.ProcessedEventRepository;

/**
 * Mensagens que o consumidor não sabe processar vão para a DLT, com os cabeçalhos do erro, e não travam a partição.
 * A DLT não é limpa entre os testes: cada teste procura a sua mensagem pelo conteúdo, que tem ids únicos.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TransferEventDeadLetterTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String SOURCE = "10001";
    private static final String TARGET = "10002";

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ConsumerFactory<String, String> consumerFactory;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    /** Um consumidor só do teste, num grupo próprio, para ler a DLT sem interferir no grupo do notification. */
    private Consumer<String, String> dltConsumer;

    private final List<ConsumerRecord<String, String>> dltRecords = new ArrayList<>();

    @BeforeEach
    void subscribeToDlt() {
        dltConsumer = consumerFactory.createConsumer("dlt-test-" + UUID.randomUUID(), null);
        dltConsumer.subscribe(List.of(KafkaConfig.TRANSFER_EVENTS_DLT));
    }

    @AfterEach
    void closeDltConsumer() {
        dltConsumer.close();
    }

    @Test
    void shouldSendInvalidJsonToDltInTheSamePartition() {
        String payload = "isto não é json " + UUID.randomUUID();

        int partition = send(payload);

        ConsumerRecord<String, String> record = awaitInDlt(payload);
        assertThat(record.partition()).isEqualTo(partition);
        assertThat(record.key()).isEqualTo(SOURCE);
        assertThat(header(record, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(KafkaConfig.TRANSFER_EVENTS_TOPIC);
        assertThat(exceptionClasses(record)).contains("tools.jackson");
    }

    @Test
    void shouldSendNewerVersionToDlt() {
        UUID eventId = UUID.randomUUID();
        String payload = json(eventId, TransferEventMessageDto.COMPLETED, 2, UUID.randomUUID(), null);

        send(payload);

        ConsumerRecord<String, String> record = awaitInDlt(payload);
        assertThat(exceptionClasses(record)).contains("UnsupportedEventException");
        assertThat(processedEventRepository.existsById(eventId)).isFalse();
    }

    @Test
    void shouldSendUnknownEventTypeToDlt() {
        String payload = json(UUID.randomUUID(), "TransferReversed", 1, UUID.randomUUID(), null);

        send(payload);

        assertThat(exceptionClasses(awaitInDlt(payload))).contains("UnsupportedEventException");
    }

    /** Sem a conta não há a quem notificar: repetir não resolve, então vai direto para a DLT. */
    @Test
    void shouldSendEventWithoutSourceAccountToDlt() {
        UUID transferId = UUID.randomUUID();
        String payload = json(UUID.randomUUID(), TransferEventMessageDto.COMPLETED, 1, transferId, null)
                .replace("\"sourceAccountNumber\":\"" + SOURCE + "\"", "\"sourceAccountNumber\":\"\"");

        send(payload);

        assertThat(exceptionClasses(awaitInDlt(payload))).contains("BusinessException");
        assertThat(notificationsOf(transferId)).isEmpty();
    }

    /** Um valor que não é número falha sempre: vai para a DLT, e não fica sendo repetido com espera crescente. */
    @Test
    void shouldSendEventWithInvalidAmountToDlt() {
        UUID eventId = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();
        String payload = json(eventId, TransferEventMessageDto.COMPLETED, 1, transferId, null)
                .replace("\"amount\":\"250.50\"", "\"amount\":\"abc\"");

        send(payload);

        assertThat(exceptionClasses(awaitInDlt(payload))).contains("UnsupportedEventException");
        assertThat(processedEventRepository.existsById(eventId)).isFalse();
        assertThat(notificationsOf(transferId)).isEmpty();
    }

    /** A mensagem envenenada sai do caminho: o evento seguinte, na mesma partição, é processado normalmente. */
    @Test
    void shouldKeepConsumingAfterPoisonMessage() {
        String poison = "{ quebrado " + UUID.randomUUID();
        UUID transferId = UUID.randomUUID();

        send(poison);
        send(json(UUID.randomUUID(), TransferEventMessageDto.COMPLETED, 1, transferId, null));

        awaitInDlt(poison);
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(notificationsOf(transferId)).hasSize(2));
    }

    /** Mesmo formato que o transfer publica: Instant como texto ISO, amount como texto, enum pelo nome. */
    private static String json(UUID eventId, String eventType, int eventVersion, UUID transferId, String failureCode) {
        String code = failureCode == null ? "null" : "\"" + failureCode + "\"";
        return """
                {"eventId":"%s","eventType":"%s","eventVersion":%d,"occurredAt":"2026-11-02T12:00:00Z",\
                "transferId":"%s","sourceAccountNumber":"%s","targetAccountNumber":"%s",\
                "amount":"250.50","failureCode":%s}"""
                .formatted(eventId, eventType, eventVersion, transferId, SOURCE, TARGET, code);
    }

    /** Envia com a conta de origem como chave, como no transfer, e devolve a partição onde a mensagem caiu. */
    private int send(String payload) {
        return kafkaTemplate.send(KafkaConfig.TRANSFER_EVENTS_TOPIC, SOURCE, payload)
                .join()
                .getRecordMetadata()
                .partition();
    }

    /** O consumidor do Kafka não é thread-safe, por isso o Awaitility roda o poll na mesma thread do teste. */
    private ConsumerRecord<String, String> awaitInDlt(String payload) {
        await().atMost(TIMEOUT).pollInSameThread().until(() -> {
            dltConsumer.poll(Duration.ofMillis(200)).forEach(dltRecords::add);
            return dltRecords.stream().anyMatch(record -> payload.equals(record.value()));
        });
        return dltRecords.stream()
                .filter(record -> payload.equals(record.value()))
                .findFirst()
                .orElseThrow();
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    /** A exceção do listener chega embrulhada; a classe real pode estar no cabeçalho da exceção ou no da causa. */
    private static String exceptionClasses(ConsumerRecord<String, String> record) {
        return header(record, KafkaHeaders.DLT_EXCEPTION_FQCN) + " " + header(record, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN);
    }

    private List<Notification> notificationsOf(UUID transferId) {
        return notificationRepository.findAll().stream()
                .filter(notification -> notification.getTransferId().equals(transferId))
                .toList();
    }
}
