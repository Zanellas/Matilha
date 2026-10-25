package com.matilha.transfer.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import jakarta.persistence.EntityManager;

/**
 * Roda contra um PostgreSQL real com as migrações do Flyway, por isso precisa do Docker.
 * Se a migração não bater com a entidade, o contexto nem sobe (ddl-auto: validate).
 */
@SpringBootTest
@Testcontainers
class TransferPersistenceTest {

    private static final String SOURCE = "10001";
    private static final String TARGET = "10002";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM transfers");
    }

    private Transfer save(Transfer transfer) {
        transactionTemplate.executeWithoutResult(status -> entityManager.persist(transfer));
        return transfer;
    }

    private Transfer reload(UUID id) {
        return transactionTemplate.execute(status -> entityManager.find(Transfer.class, id));
    }

    @Nested
    class Mapping {

        @Test
        void shouldPersistAndLoadPendingTransfer() {
            Transfer saved = save(new Transfer(SOURCE, TARGET, new BigDecimal("250.50")));

            Transfer loaded = reload(saved.getId());

            assertThat(loaded.getId()).isEqualTo(saved.getId());
            assertThat(loaded.getSourceAccountNumber()).isEqualTo(SOURCE);
            assertThat(loaded.getTargetAccountNumber()).isEqualTo(TARGET);
            assertThat(loaded.getAmount()).isEqualByComparingTo("250.50");
            assertThat(loaded.getAmount().scale()).isEqualTo(2);
            assertThat(loaded.getStatus()).isEqualTo(TransferStatus.PENDING);
            assertThat(loaded.getFailureReason()).isNull();
            assertThat(loaded.getCreatedAt()).isNotNull();
            assertThat(loaded.getVersion()).isZero();
        }

        @Test
        void shouldPersistCompletedTransfer() {
            Transfer saved = save(new Transfer(SOURCE, TARGET, new BigDecimal("10.00")));

            transactionTemplate.executeWithoutResult(status -> entityManager.find(Transfer.class, saved.getId()).complete());

            Transfer loaded = reload(saved.getId());
            assertThat(loaded.getStatus()).isEqualTo(TransferStatus.COMPLETED);
            assertThat(loaded.getUpdatedAt()).isAfterOrEqualTo(loaded.getCreatedAt());
            assertThat(loaded.getVersion()).isEqualTo(1L);
        }

        @Test
        void shouldPersistFailedTransferWithReason() {
            Transfer saved = save(new Transfer(SOURCE, TARGET, new BigDecimal("10.00")));

            transactionTemplate.executeWithoutResult(status -> entityManager.find(Transfer.class, saved.getId())
                    .fail("Insufficient balance"));

            Transfer loaded = reload(saved.getId());
            assertThat(loaded.getStatus()).isEqualTo(TransferStatus.FAILED);
            assertThat(loaded.getFailureReason()).isEqualTo("Insufficient balance");
        }
    }

    @Nested
    class Constraints {

        private void insert(String source, String target, String amount, String status) {
            jdbcTemplate.update("""
                    INSERT INTO transfers (id, source_account_number, target_account_number, amount, status, created_at)
                    VALUES (?, ?, ?, ?, ?, now())
                    """, UUID.randomUUID(), source, target, new BigDecimal(amount), status);
        }

        @Test
        void shouldAcceptValidRow() {
            insert(SOURCE, TARGET, "10.00", "PENDING");

            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transfers", Integer.class)).isEqualTo(1);
        }

        @Test
        void shouldRejectUnknownStatus() {
            assertThatThrownBy(() -> insert(SOURCE, TARGET, "10.00", "CANCELLED"))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_transfers_status");
        }

        @Test
        void shouldRejectZeroAmount() {
            assertThatThrownBy(() -> insert(SOURCE, TARGET, "0.00", "PENDING"))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_transfers_amount_positive");
        }

        @Test
        void shouldRejectNegativeAmount() {
            assertThatThrownBy(() -> insert(SOURCE, TARGET, "-10.00", "PENDING"))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_transfers_amount_positive");
        }

        @Test
        void shouldRejectSameSourceAndTarget() {
            assertThatThrownBy(() -> insert(SOURCE, SOURCE, "10.00", "PENDING"))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_transfers_different_accounts");
        }

        @Test
        void shouldRejectMissingRequiredColumn() {
            assertThatThrownBy(() -> insert(null, TARGET, "10.00", "PENDING"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }
}
