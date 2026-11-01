package com.matilha.transfer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.matilha.transfer.entity.FailureCode;
import com.matilha.transfer.entity.Transfer;
import com.matilha.transfer.entity.TransferStatus;
import com.matilha.transfer.repository.TransferRepository;

/**
 * Roda contra um PostgreSQL real: o que se testa aqui é a transação. A mudança de status e o evento no
 * outbox são gravados juntos, ou nenhum dos dois é.
 */
@SpringBootTest(properties = "matilha.reconciler.enabled=false")
@Testcontainers
class TransferFinisherTest {

    private static final String SOURCE = "10001";
    private static final String TARGET = "10002";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @Autowired
    private TransferFinisher transferFinisher;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM transfers");
    }

    /** Como no fluxo real: a PENDING já foi gravada numa transação anterior e chega aqui desanexada. */
    private Transfer savedPending() {
        return transferRepository.save(new Transfer(UUID.randomUUID(), SOURCE, TARGET, new BigDecimal("250.50")));
    }

    private TransferStatus statusInDatabase(UUID transferId) {
        return transferRepository.findById(transferId).orElseThrow().getStatus();
    }

    private int outboxCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM outbox_events", Integer.class);
    }

    private Map<String, Object> outboxRow(UUID transferId) {
        return jdbcTemplate.queryForMap("""
                SELECT id, event_type, message_key, published_at, attempts,
                       payload->>'eventId'       AS payload_event_id,
                       payload->>'eventType'     AS payload_event_type,
                       payload->>'transferId'    AS payload_transfer_id,
                       payload->>'amount'        AS payload_amount,
                       payload->>'failureCode'   AS payload_failure_code,
                       payload::text             AS payload_text
                FROM outbox_events
                WHERE aggregate_id = ?
                """, transferId);
    }

    @Nested
    class Complete {

        @Test
        void shouldSaveCompletedStatusAndEventTogether() {
            Transfer pending = savedPending();

            Transfer completed = transferFinisher.complete(pending);

            assertThat(completed.getStatus()).isEqualTo(TransferStatus.COMPLETED);
            assertThat(statusInDatabase(pending.getId())).isEqualTo(TransferStatus.COMPLETED);
            assertThat(outboxCount()).isEqualTo(1);

            Map<String, Object> row = outboxRow(pending.getId());
            assertThat(row.get("event_type")).isEqualTo("TransferCompleted");
            assertThat(row.get("message_key")).isEqualTo(SOURCE);
            assertThat(row.get("published_at")).isNull();
            assertThat(row.get("attempts")).isEqualTo(0);
            assertThat(row.get("payload_event_type")).isEqualTo("TransferCompleted");
            assertThat(row.get("payload_transfer_id")).isEqualTo(pending.getId().toString());
            assertThat(row.get("payload_amount")).isEqualTo("250.50");
            assertThat(row.get("payload_failure_code")).isNull();
        }

        /** O notification vai descartar duplicadas pelo eventId, então ele precisa ser o id da linha. */
        @Test
        void shouldUseTheEventIdAsTheOutboxId() {
            Transfer pending = savedPending();

            transferFinisher.complete(pending);

            Map<String, Object> row = outboxRow(pending.getId());
            assertThat(row.get("id").toString()).isEqualTo(row.get("payload_event_id"));
        }
    }

    @Nested
    class Fail {

        @Test
        void shouldSaveFailedStatusAndEventTogether() {
            Transfer pending = savedPending();

            Transfer failed = transferFinisher.fail(pending, FailureCode.INSUFFICIENT_BALANCE, "Insufficient balance");

            assertThat(failed.getStatus()).isEqualTo(TransferStatus.FAILED);
            Transfer inDatabase = transferRepository.findById(pending.getId()).orElseThrow();
            assertThat(inDatabase.getStatus()).isEqualTo(TransferStatus.FAILED);
            assertThat(inDatabase.getFailureCode()).isEqualTo(FailureCode.INSUFFICIENT_BALANCE);

            Map<String, Object> row = outboxRow(pending.getId());
            assertThat(row.get("event_type")).isEqualTo("TransferFailed");
            assertThat(row.get("payload_failure_code")).isEqualTo("INSUFFICIENT_BALANCE");
        }

        @Test
        void shouldNotPutTheAccountTextInTheEvent() {
            Transfer pending = savedPending();

            transferFinisher.fail(pending, FailureCode.ACCOUNT_INACTIVE, "Account is not active");

            assertThat((String) outboxRow(pending.getId()).get("payload_text")).doesNotContain("Account is not active");
        }
    }

    /**
     * A prova de que é uma transação só: se o evento não puder ser gravado, a mudança de status também volta.
     * Aqui o evento falha porque já existe um TransferCompleted para a mesma transferência (uk_outbox_events_aggregate_event).
     */
    @Nested
    class Atomicity {

        @Test
        void shouldRollBackTheStatusWhenTheEventCannotBeSaved() {
            Transfer pending = savedPending();
            jdbcTemplate.update("""
                    INSERT INTO outbox_events (id, aggregate_id, event_type, message_key, payload, created_at)
                    VALUES (?, ?, 'TransferCompleted', ?, CAST('{}' AS jsonb), now())
                    """, UUID.randomUUID(), pending.getId(), SOURCE);

            assertThatThrownBy(() -> transferFinisher.complete(pending))
                    .isInstanceOf(DataIntegrityViolationException.class);

            assertThat(statusInDatabase(pending.getId())).isEqualTo(TransferStatus.PENDING);
            assertThat(outboxCount()).isEqualTo(1);
        }

        /**
         * A corrida que o reconciliador trata: a requisição e o reconciliador têm cada um a sua cópia da mesma
         * PENDING. Quem termina primeiro grava. O outro esbarra no @Version e o evento dele volta junto.
         */
        @Test
        void shouldKeepOnlyTheWinnersEventWhenTwoCopiesFinishTheSameTransfer() {
            Transfer requestCopy = savedPending();
            Transfer reconcilerCopy = transferRepository.findById(requestCopy.getId()).orElseThrow();

            transferFinisher.complete(requestCopy);

            assertThatThrownBy(() -> transferFinisher.fail(reconcilerCopy, FailureCode.INSUFFICIENT_BALANCE,
                    "Insufficient balance"))
                    .isInstanceOf(ObjectOptimisticLockingFailureException.class);

            assertThat(statusInDatabase(requestCopy.getId())).isEqualTo(TransferStatus.COMPLETED);
            assertThat(outboxCount()).isEqualTo(1);
            assertThat(outboxRow(requestCopy.getId()).get("event_type")).isEqualTo("TransferCompleted");
        }
    }
}
