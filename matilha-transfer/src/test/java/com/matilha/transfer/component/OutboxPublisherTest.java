package com.matilha.transfer.component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

import com.matilha.transfer.config.KafkaConfig;
import com.matilha.transfer.entity.OutboxEvent;
import com.matilha.transfer.service.OutboxService;

/**
 * O Kafka é simulado pelo future que o send devolve: completo é a confirmação do broker, com exceção é a falha,
 * e um future que nunca completa é o broker que não responde.
 */
@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    private static final Duration LEASE = Duration.ofMinutes(1);
    private static final String PAYLOAD = "{\"eventType\":\"TransferCompleted\"}";

    @Mock
    private OutboxService outboxService;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new OutboxPublisher(outboxService, kafkaTemplate);
        ReflectionTestUtils.setField(publisher, "lease", LEASE);
        ReflectionTestUtils.setField(publisher, "sendTimeout", Duration.ofMillis(200));
    }

    private static OutboxEvent event(String key) {
        return new OutboxEvent(UUID.randomUUID(), UUID.randomUUID(), "TransferCompleted", key, PAYLOAD);
    }

    private void claim(OutboxEvent... events) {
        when(outboxService.claimUnpublished(LEASE, 50)).thenReturn(List.of(events));
    }

    private void brokerConfirms(OutboxEvent event) {
        when(kafkaTemplate.send(KafkaConfig.TRANSFER_EVENTS_TOPIC, event.getMessageKey(), event.getPayload()))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    private void brokerFails(OutboxEvent event, String error) {
        when(kafkaTemplate.send(KafkaConfig.TRANSFER_EVENTS_TOPIC, event.getMessageKey(), event.getPayload()))
                .thenReturn(CompletableFuture.failedFuture(new KafkaException(error)));
    }

    private void brokerNeverAnswers(OutboxEvent event) {
        when(kafkaTemplate.send(KafkaConfig.TRANSFER_EVENTS_TOPIC, event.getMessageKey(), event.getPayload()))
                .thenReturn(new CompletableFuture<SendResult<String, String>>());
    }

    @Nested
    class Success {

        @Test
        void shouldSendKeyAndPayloadToTheTransferEventsTopic() {
            OutboxEvent event = event("10001");
            claim(event);
            brokerConfirms(event);

            publisher.publish();

            verify(kafkaTemplate).send("matilha.transfer.events", "10001", PAYLOAD);
        }

        @Test
        void shouldMarkPublishedOnlyAfterTheBrokerConfirms() {
            OutboxEvent event = event("10001");
            claim(event);
            brokerConfirms(event);

            publisher.publish();

            InOrder order = inOrder(kafkaTemplate, outboxService);
            order.verify(kafkaTemplate).send(anyString(), anyString(), anyString());
            order.verify(outboxService).markPublished(event.getId());
            verify(outboxService, never()).registerFailure(any(), any());
        }

        @Test
        void shouldPublishTheWholeBatchInOrder() {
            OutboxEvent first = event("10001");
            OutboxEvent second = event("10002");
            OutboxEvent third = event("10003");
            claim(first, second, third);
            brokerConfirms(first);
            brokerConfirms(second);
            brokerConfirms(third);

            publisher.publish();

            InOrder order = inOrder(outboxService);
            order.verify(outboxService).markPublished(first.getId());
            order.verify(outboxService).markPublished(second.getId());
            order.verify(outboxService).markPublished(third.getId());
        }

        @Test
        void shouldClaimWithTheConfiguredLeaseAndBatchSize() {
            claim();

            publisher.publish();

            verify(outboxService).claimUnpublished(LEASE, 50);
        }

        @Test
        void shouldNotTouchKafkaWhenThereIsNothingToPublish() {
            claim();

            publisher.publish();

            verifyNoInteractions(kafkaTemplate);
        }
    }

    @Nested
    class Failure {

        @Test
        void shouldRegisterTheKafkaErrorAndNotMarkPublished() {
            OutboxEvent event = event("10001");
            claim(event);
            brokerFails(event, "Broker not available");

            publisher.publish();

            verify(outboxService).registerFailure(eq(event.getId()), contains("Broker not available"));
            verify(outboxService, never()).markPublished(any());
        }

        /** O ExecutionException é só o envelope do future: o que vai para o last_error é a causa, com a classe. */
        @Test
        void shouldRegisterTheCauseNotTheExecutionException() {
            OutboxEvent event = event("10001");
            claim(event);
            brokerFails(event, "Broker not available");

            publisher.publish();

            verify(outboxService).registerFailure(event.getId(),
                    "org.springframework.kafka.KafkaException: Broker not available");
        }

        /** Sem confirmação dentro do send-timeout, o evento não é dado como publicado. */
        @Test
        void shouldRegisterATimeoutWhenTheBrokerDoesNotAnswer() {
            OutboxEvent event = event("10001");
            claim(event);
            brokerNeverAnswers(event);

            publisher.publish();

            verify(outboxService).registerFailure(eq(event.getId()), contains("TimeoutException"));
            verify(outboxService, never()).markPublished(any());
        }

        /** Se o Kafka caiu, os próximos também iam esperar o timeout. Eles voltam quando a reserva vencer. */
        @Test
        void shouldStopTheBatchAfterTheFirstFailure() {
            OutboxEvent first = event("10001");
            OutboxEvent second = event("10002");
            OutboxEvent third = event("10003");
            claim(first, second, third);
            brokerConfirms(first);
            brokerFails(second, "Broker not available");

            publisher.publish();

            verify(outboxService).markPublished(first.getId());
            verify(outboxService).registerFailure(eq(second.getId()), anyString());
            verify(kafkaTemplate, times(2)).send(anyString(), anyString(), anyString());
            verify(outboxService, never()).markPublished(third.getId());
        }
    }

    @Nested
    class Interruption {

        /** A aplicação está desligando: para sem registrar falha e devolve a interrupção para quem chamou. */
        @Test
        void shouldStopAndKeepTheInterruptFlag() {
            OutboxEvent event = event("10001");
            claim(event);
            brokerNeverAnswers(event);

            Thread.currentThread().interrupt();
            publisher.publish();
            boolean interrupted = Thread.interrupted();

            assertThat(interrupted).isTrue();
            verify(outboxService, never()).markPublished(any());
            verify(outboxService, never()).registerFailure(any(), any());
        }
    }
}
