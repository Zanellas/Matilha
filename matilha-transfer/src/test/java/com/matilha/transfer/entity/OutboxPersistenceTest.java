package com.matilha.transfer.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.matilha.transfer.repository.OutboxEventRepository;

import jakarta.persistence.EntityManagerFactory;

/**
 * Testa a tabela outbox_events no PostgreSQL. As classes que usam JdbcTemplate provam que cada regra
 * da V4 recusa o que deveria; a EntityMapping prova que a OutboxEvent é gravada e lida corretamente.
 */
@SpringBootTest(properties = {
        "matilha.reconciler.enabled=false",
        "spring.jpa.properties.hibernate.generate_statistics=true" })
@Testcontainers
class OutboxPersistenceTest {

    private static final String COMPLETED = "TransferCompleted";
    private static final String FAILED = "TransferFailed";
    private static final String KEY = "10001";
    private static final String PAYLOAD = """
            {"eventType": "TransferCompleted", "amount": "250.50"}
            """;

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM outbox_events");
    }

    /** O CAST é necessário porque o JDBC envia o payload como texto e a coluna é JSONB. */
    private UUID insert(UUID aggregateId, String eventType, String messageKey, String payload) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO outbox_events (id, aggregate_id, event_type, message_key, payload, created_at)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb), now())
                """, id, aggregateId, eventType, messageKey, payload);
        return id;
    }

    private UUID insertValid() {
        return insert(UUID.randomUUID(), COMPLETED, KEY, PAYLOAD);
    }

    private int count() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM outbox_events", Integer.class);
    }

    @Nested
    class Defaults {

        @Test
        void shouldStartUnpublishedWithZeroAttempts() {
            UUID id = insertValid();

            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT attempts, published_at, last_error FROM outbox_events WHERE id = ?", id);

            assertThat(row.get("attempts")).isEqualTo(0);
            assertThat(row.get("published_at")).isNull();
            assertThat(row.get("last_error")).isNull();
        }

        @Test
        void shouldStorePayloadAsQueryableJson() {
            UUID id = insertValid();

            String amount = jdbcTemplate.queryForObject(
                    "SELECT payload->>'amount' FROM outbox_events WHERE id = ?", String.class, id);

            assertThat(amount).isEqualTo("250.50");
        }
    }

    @Nested
    class EventType {

        @Test
        void shouldAcceptBothKnownTypes() {
            insert(UUID.randomUUID(), COMPLETED, KEY, PAYLOAD);
            insert(UUID.randomUUID(), FAILED, KEY, PAYLOAD);

            assertThat(count()).isEqualTo(2);
        }

        @Test
        void shouldRejectUnknownType() {
            assertThatThrownBy(() -> insert(UUID.randomUUID(), "TransferComplete", KEY, PAYLOAD))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_outbox_events_event_type");
        }

        @Test
        void shouldRejectTypeWithDifferentCase() {
            assertThatThrownBy(() -> insert(UUID.randomUUID(), "transfercompleted", KEY, PAYLOAD))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_outbox_events_event_type");
        }
    }

    @Nested
    class OneEventPerTypeAndTransfer {

        @Test
        void shouldRejectSameTypeTwiceForSameTransfer() {
            UUID transferId = UUID.randomUUID();
            insert(transferId, COMPLETED, KEY, PAYLOAD);

            assertThatThrownBy(() -> insert(transferId, COMPLETED, KEY, PAYLOAD))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("uk_outbox_events_aggregate_event");
            assertThat(count()).isEqualTo(1);
        }

        @Test
        void shouldAcceptSameTypeForDifferentTransfers() {
            insert(UUID.randomUUID(), COMPLETED, KEY, PAYLOAD);
            insert(UUID.randomUUID(), COMPLETED, KEY, PAYLOAD);

            assertThat(count()).isEqualTo(2);
        }

        @Test
        void shouldRejectDuplicateId() {
            UUID id = insertValid();

            assertThatThrownBy(() -> jdbcTemplate.update("""
                    INSERT INTO outbox_events (id, aggregate_id, event_type, message_key, payload, created_at)
                    VALUES (?, ?, ?, ?, CAST(? AS jsonb), now())
                    """, id, UUID.randomUUID(), FAILED, KEY, PAYLOAD))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Nested
    class RequiredColumns {

        @Test
        void shouldRejectMissingAggregateId() {
            assertThatThrownBy(() -> insert(null, COMPLETED, KEY, PAYLOAD))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("aggregate_id");
        }

        @Test
        void shouldRejectMissingEventType() {
            assertThatThrownBy(() -> insert(UUID.randomUUID(), null, KEY, PAYLOAD))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("event_type");
        }

        @Test
        void shouldRejectMissingMessageKey() {
            assertThatThrownBy(() -> insert(UUID.randomUUID(), COMPLETED, null, PAYLOAD))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("message_key");
        }

        @Test
        void shouldRejectMissingPayload() {
            assertThatThrownBy(() -> insert(UUID.randomUUID(), COMPLETED, KEY, null))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("payload");
        }

        @Test
        void shouldRejectInvalidJson() {
            assertThatThrownBy(() -> insert(UUID.randomUUID(), COMPLETED, KEY, "{ not json"))
                    .isInstanceOf(DataAccessException.class);
        }
    }

    @Nested
    class Attempts {

        @Test
        void shouldRejectNegativeAttempts() {
            UUID id = insertValid();

            assertThatThrownBy(() -> jdbcTemplate.update("UPDATE outbox_events SET attempts = -1 WHERE id = ?", id))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_outbox_events_attempts_non_negative");
        }

        @Test
        void shouldRejectLastErrorLongerThan500Characters() {
            UUID id = insertValid();

            assertThatThrownBy(() -> jdbcTemplate.update(
                    "UPDATE outbox_events SET last_error = ? WHERE id = ?", "x".repeat(501), id))
                    .isInstanceOf(DataAccessException.class);
        }
    }

    @Nested
    class EntityMapping {

        private static final String ENTITY_PAYLOAD = """
                {"transferId": "8a2b4c6d-1e3f-4a5b-8c7d-9e0f1a2b3c4d", "amount": "250.50"}
                """;

        private OutboxEvent newEvent(UUID transferId, String eventType) {
            return new OutboxEvent(UUID.randomUUID(), transferId, eventType, KEY, ENTITY_PAYLOAD);
        }

        private OutboxEvent reload(UUID id) {
            return outboxEventRepository.findById(id).orElseThrow();
        }

        private Statistics statistics() {
            Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
            statistics.clear();
            return statistics;
        }

        @Test
        void shouldPersistAndLoadEvent() {
            UUID transferId = UUID.randomUUID();
            OutboxEvent saved = outboxEventRepository.save(newEvent(transferId, COMPLETED));

            OutboxEvent loaded = reload(saved.getId());

            assertThat(loaded.getId()).isEqualTo(saved.getId());
            assertThat(loaded.getAggregateId()).isEqualTo(transferId);
            assertThat(loaded.getEventType()).isEqualTo(COMPLETED);
            assertThat(loaded.getMessageKey()).isEqualTo(KEY);
            assertThat(loaded.getCreatedAt()).isNotNull();
            assertThat(loaded.getPublishedAt()).isNull();
            assertThat(loaded.getAttempts()).isZero();
        }

        @Test
        void shouldStorePayloadAsJsonObjectNotAsQuotedString() {
            OutboxEvent saved = outboxEventRepository.save(newEvent(UUID.randomUUID(), COMPLETED));

            Map<String, Object> row = jdbcTemplate.queryForMap("""
                    SELECT jsonb_typeof(payload) AS type, payload->>'amount' AS amount
                    FROM outbox_events WHERE id = ?
                    """, saved.getId());

            assertThat(row.get("type")).isEqualTo("object");
            assertThat(row.get("amount")).isEqualTo("250.50");
        }

        @Test
        void shouldInsertWithoutSelectingFirst() {
            OutboxEvent event = newEvent(UUID.randomUUID(), COMPLETED);
            Statistics statistics = statistics();

            outboxEventRepository.save(event);

            assertThat(statistics.getPrepareStatementCount())
                    .as("only the INSERT; a SELECT here means save() called merge instead of persist")
                    .isEqualTo(1);
        }

        @Test
        void shouldNotBeNewAfterSaveOrLoad() {
            OutboxEvent event = newEvent(UUID.randomUUID(), COMPLETED);
            assertThat(event.isNew()).isTrue();

            outboxEventRepository.save(event);

            assertThat(event.isNew()).isFalse();
            assertThat(reload(event.getId()).isNew()).isFalse();
        }

        @Test
        void shouldUpdateWhenPublished() {
            OutboxEvent event = outboxEventRepository.save(newEvent(UUID.randomUUID(), COMPLETED));
            Instant publishedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

            event.markPublished(publishedAt);
            outboxEventRepository.save(event);

            assertThat(reload(event.getId()).getPublishedAt()).isEqualTo(publishedAt);
            assertThat(count()).isEqualTo(1);
        }

        @Test
        void shouldUpdateWhenFailed() {
            OutboxEvent event = outboxEventRepository.save(newEvent(UUID.randomUUID(), COMPLETED));

            event.registerFailure("Broker unavailable");
            outboxEventRepository.save(event);

            OutboxEvent loaded = reload(event.getId());
            assertThat(loaded.getAttempts()).isEqualTo(1);
            assertThat(loaded.getLastError()).isEqualTo("Broker unavailable");
            assertThat(loaded.getPublishedAt()).isNull();
        }

        @Test
        void shouldRejectSecondEventOfSameTypeForSameTransfer() {
            UUID transferId = UUID.randomUUID();
            outboxEventRepository.save(newEvent(transferId, COMPLETED));

            assertThatThrownBy(() -> outboxEventRepository.saveAndFlush(newEvent(transferId, COMPLETED)))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("uk_outbox_events_aggregate_event");
        }
    }

    @Nested
    class UnpublishedIndex {

        @Test
        void shouldBePartialOnUnpublishedRows() {
            String definition = jdbcTemplate.queryForObject(
                    "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_outbox_events_unpublished'",
                    String.class);

            assertThat(definition)
                    .contains("(created_at)")
                    .contains("WHERE (published_at IS NULL)");
        }
    }
}
