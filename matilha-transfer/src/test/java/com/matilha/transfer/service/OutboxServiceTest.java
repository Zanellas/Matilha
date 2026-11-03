package com.matilha.transfer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.matilha.transfer.TestcontainersConfiguration;
import com.matilha.transfer.entity.OutboxEvent;
import com.matilha.transfer.repository.OutboxEventRepository;

/**
 * A busca do publicador (FOR UPDATE SKIP LOCKED), a reserva com prazo (claimed_until) e o registro do resultado.
 * Só dá para testar contra o PostgreSQL de verdade. O Kafka não entra aqui: o envio é do OutboxPublisher.
 */
@SpringBootTest(properties = { "matilha.reconciler.enabled=false", "spring.kafka.admin.auto-create=false" })
@Import(TestcontainersConfiguration.class)
class OutboxServiceTest {

    private static final Duration LEASE = Duration.ofMinutes(1);

    @Autowired
    private OutboxService outboxService;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM outbox_events");
    }

    /** Insere um evento não publicado criado há "ago". Cada um com uma transferência diferente. */
    private UUID eventCreated(Duration ago) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO outbox_events (id, aggregate_id, event_type, message_key, payload, created_at)
                VALUES (?, ?, 'TransferCompleted', '10001', CAST('{}' AS jsonb), ?)
                """, id, UUID.randomUUID(), Timestamp.from(Instant.now().minus(ago)));
        return id;
    }

    private void claimedUntil(UUID id, Instant until) {
        jdbcTemplate.update("UPDATE outbox_events SET claimed_until = ? WHERE id = ?", Timestamp.from(until), id);
    }

    private void publishedAt(UUID id, Instant when) {
        jdbcTemplate.update("UPDATE outbox_events SET published_at = ? WHERE id = ?", Timestamp.from(when), id);
    }

    private Map<String, Object> row(UUID id) {
        return jdbcTemplate.queryForMap(
                "SELECT published_at, claimed_until, attempts, last_error FROM outbox_events WHERE id = ?", id);
    }

    private static Instant instant(Object timestamp) {
        return timestamp == null ? null : ((Timestamp) timestamp).toInstant();
    }

    private static List<UUID> ids(List<OutboxEvent> events) {
        return events.stream().map(OutboxEvent::getId).toList();
    }

    @Nested
    class Claim {

        @Test
        void shouldClaimOnlyUnpublishedEventsWithoutValidLeaseOldestFirst() {
            UUID old = eventCreated(Duration.ofMinutes(10));
            UUID older = eventCreated(Duration.ofMinutes(20));
            UUID expiredLease = eventCreated(Duration.ofMinutes(15));
            claimedUntil(expiredLease, Instant.now().minusSeconds(60));
            UUID activeLease = eventCreated(Duration.ofMinutes(25));
            claimedUntil(activeLease, Instant.now().plus(LEASE));
            UUID published = eventCreated(Duration.ofMinutes(30));
            publishedAt(published, Instant.now().minusSeconds(60));

            List<OutboxEvent> claimed = outboxService.claimUnpublished(LEASE, 20);

            assertThat(ids(claimed)).containsExactly(older, expiredLease, old);
        }

        /** Diferente do reconciliador, não há idade mínima: um evento novo já pode ser publicado. */
        @Test
        void shouldClaimEventJustCreated() {
            UUID justCreated = eventCreated(Duration.ZERO);

            assertThat(ids(outboxService.claimUnpublished(LEASE, 20))).containsExactly(justCreated);
        }

        @Test
        void shouldRespectTheLimit() {
            UUID oldest = eventCreated(Duration.ofMinutes(30));
            UUID middle = eventCreated(Duration.ofMinutes(20));
            eventCreated(Duration.ofMinutes(10));

            List<OutboxEvent> claimed = outboxService.claimUnpublished(LEASE, 2);

            assertThat(ids(claimed)).containsExactly(oldest, middle);
        }

        @Test
        void shouldSaveTheLeaseInTheDatabase() {
            UUID id = eventCreated(Duration.ofMinutes(1));
            Instant before = Instant.now();

            outboxService.claimUnpublished(LEASE, 20);

            assertThat(instant(row(id).get("claimed_until")))
                    .isBetween(before.plus(LEASE).minusSeconds(1), Instant.now().plus(LEASE));
        }

        @Test
        void shouldNotClaimTheSameEventTwiceWhileTheLeaseIsValid() {
            UUID id = eventCreated(Duration.ofMinutes(1));

            List<OutboxEvent> first = outboxService.claimUnpublished(LEASE, 20);
            List<OutboxEvent> second = outboxService.claimUnpublished(LEASE, 20);

            assertThat(ids(first)).containsExactly(id);
            assertThat(second).isEmpty();
        }

        /** O publicador reservou e caiu antes de publicar: quando a reserva vence, outro pega o evento. */
        @Test
        void shouldClaimAgainWhenTheLeaseExpires() {
            UUID id = eventCreated(Duration.ofMinutes(1));
            outboxService.claimUnpublished(LEASE, 20);
            claimedUntil(id, Instant.now().minusSeconds(1));

            assertThat(ids(outboxService.claimUnpublished(LEASE, 20))).containsExactly(id);
        }

        /**
         * Simula duas réplicas buscando ao mesmo tempo. A primeira trava 2 linhas e segura a transação aberta;
         * a segunda precisa pular essas 2 na hora (sem esperar) e pegar as outras.
         */
        @Test
        void shouldSkipRowsLockedByAnotherTransaction() throws Exception {
            UUID first = eventCreated(Duration.ofMinutes(40));
            UUID second = eventCreated(Duration.ofMinutes(30));
            UUID third = eventCreated(Duration.ofMinutes(20));
            UUID fourth = eventCreated(Duration.ofMinutes(10));

            CountDownLatch locked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<List<UUID>> replicaA = executor.submit(() -> transactionTemplate.execute(status -> {
                    List<UUID> rows = ids(outboxEventRepository.findUnpublishedToClaim(Instant.now(), 2));
                    locked.countDown();
                    await(release);
                    return rows;
                }));
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

                long start = System.nanoTime();
                List<UUID> replicaB = transactionTemplate.execute(
                        status -> ids(outboxEventRepository.findUnpublishedToClaim(Instant.now(), 10)));
                Duration waited = Duration.ofNanos(System.nanoTime() - start);

                release.countDown();
                assertThat(replicaA.get(10, TimeUnit.SECONDS)).containsExactly(first, second);
                assertThat(replicaB).containsExactly(third, fourth);
                assertThat(waited).isLessThan(Duration.ofSeconds(2));
            } finally {
                release.countDown();
                executor.shutdownNow();
            }
        }

        private static void await(CountDownLatch latch) {
            try {
                latch.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Nested
    class MarkPublished {

        @Test
        void shouldSavePublishedAt() {
            UUID id = eventCreated(Duration.ofMinutes(1));
            Instant before = Instant.now();

            outboxService.markPublished(id);

            assertThat(instant(row(id).get("published_at"))).isBetween(before.minusSeconds(1), Instant.now());
        }

        @Test
        void shouldNotClaimPublishedEventEvenAfterTheLeaseExpires() {
            UUID id = eventCreated(Duration.ofMinutes(1));
            outboxService.claimUnpublished(LEASE, 20);
            outboxService.markPublished(id);
            claimedUntil(id, Instant.now().minusSeconds(1));

            assertThat(outboxService.claimUnpublished(LEASE, 20)).isEmpty();
        }

        /**
         * A reserva venceu e outra réplica publicou primeiro. O segundo envio é a duplicata que o notification
         * descarta pelo eventId. Aqui não é erro: a data da primeira publicação fica.
         */
        @Test
        void shouldKeepTheFirstPublicationWhenAlreadyPublished() {
            UUID id = eventCreated(Duration.ofMinutes(5));
            Instant firstPublication = Instant.parse("2026-11-01T12:00:00Z");
            publishedAt(id, firstPublication);

            assertThatCode(() -> outboxService.markPublished(id)).doesNotThrowAnyException();

            assertThat(instant(row(id).get("published_at"))).isEqualTo(firstPublication);
        }

        @Test
        void shouldIgnoreUnknownEvent() {
            assertThatCode(() -> outboxService.markPublished(UUID.randomUUID())).doesNotThrowAnyException();
        }
    }

    @Nested
    class RegisterFailure {

        @Test
        void shouldCountAttemptsAndKeepTheLastError() {
            UUID id = eventCreated(Duration.ofMinutes(1));

            outboxService.registerFailure(id, "Timeout waiting for broker");
            outboxService.registerFailure(id, "Broker not available");

            Map<String, Object> row = row(id);
            assertThat(row.get("attempts")).isEqualTo(2);
            assertThat(row.get("last_error")).isEqualTo("Broker not available");
            assertThat(row.get("published_at")).isNull();
        }

        /** Uma falha não publica nem descarta o evento: quando a reserva vence, ele é tentado de novo. */
        @Test
        void shouldTryAgainAfterTheLeaseExpires() {
            UUID id = eventCreated(Duration.ofMinutes(1));
            outboxService.claimUnpublished(LEASE, 20);
            outboxService.registerFailure(id, "Broker not available");
            claimedUntil(id, Instant.now().minusSeconds(1));

            assertThat(ids(outboxService.claimUnpublished(LEASE, 20))).containsExactly(id);
        }

        @Test
        void shouldIgnoreUnknownEvent() {
            assertThatCode(() -> outboxService.registerFailure(UUID.randomUUID(), "boom"))
                    .doesNotThrowAnyException();
        }
    }
}
