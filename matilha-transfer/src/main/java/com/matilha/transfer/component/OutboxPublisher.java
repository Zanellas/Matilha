package com.matilha.transfer.component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.matilha.transfer.config.KafkaConfig;
import com.matilha.transfer.entity.OutboxEvent;
import com.matilha.transfer.service.OutboxService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private static final int BATCH_SIZE = 50;

    private final OutboxService outboxService;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${matilha.outbox.lease}")
    private Duration lease;

    @Value("${matilha.outbox.send-timeout}")
    private Duration sendTimeout;

    @Scheduled(fixedDelayString = "${matilha.outbox.interval}")
    public void publish() {
        List<OutboxEvent> batch = outboxService.claimUnpublished(lease, BATCH_SIZE);
        for (OutboxEvent event : batch) {
            if (!send(event)) {
                return;
            }
        }
    }

    private boolean send(OutboxEvent event) {
        try {
            kafkaTemplate.send(KafkaConfig.TRANSFER_EVENTS_TOPIC, event.getMessageKey(), event.getPayload())
                    .get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException ex) {
            log.warn("Could not publish event {}, it will be retried when the lease expires", event.getId(), ex);
            outboxService.registerFailure(event.getId(), errorOf(ex));
            return false;
        }
        outboxService.markPublished(event.getId());
        return true;
    }

    private static String errorOf(Exception ex) {
        Throwable cause = ex instanceof ExecutionException && ex.getCause() != null ? ex.getCause() : ex;
        return cause.toString();
    }
}
