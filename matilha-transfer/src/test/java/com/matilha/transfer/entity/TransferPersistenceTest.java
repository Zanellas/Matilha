package com.matilha.transfer.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.matilha.transfer.repository.TransferRepository;
import com.matilha.transfer.service.TransferService;

/**
 * Roda contra um PostgreSQL real com as migrações do Flyway, por isso precisa do Docker.
 * Se a migração não bater com a entidade, o contexto nem sobe (ddl-auto: validate).
 */
@SpringBootTest(properties = "matilha.reconciler.enabled=false")
@Testcontainers
class TransferPersistenceTest {

    private static final String SOURCE = "10001";
    private static final String TARGET = "10002";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransferService transferService;

    @BeforeEach
    void cleanDatabase() {
        transferRepository.deleteAll();
    }

    private static Transfer newTransfer(UUID key, String amount) {
        return new Transfer(key, SOURCE, TARGET, new BigDecimal(amount));
    }

    private Transfer reload(UUID id) {
        return transferRepository.findById(id).orElseThrow();
    }

    @Nested
    class Mapping {

        @Test
        void shouldGenerateIdWhenPersisted() {
            Transfer saved = transferRepository.save(newTransfer(UUID.randomUUID(), "10.00"));

            assertThat(saved.getId()).isNotNull();
        }

        @Test
        void shouldGenerateDifferentIdForEachTransfer() {
            Transfer first = transferRepository.save(newTransfer(UUID.randomUUID(), "10.00"));
            Transfer second = transferRepository.save(newTransfer(UUID.randomUUID(), "10.00"));

            assertThat(first.getId()).isNotEqualTo(second.getId());
        }

        @Test
        void shouldPersistAndLoadPendingTransfer() {
            UUID key = UUID.randomUUID();
            Transfer saved = transferRepository.save(newTransfer(key, "250.50"));

            Transfer loaded = reload(saved.getId());

            assertThat(loaded.getIdempotencyKey()).isEqualTo(key);
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
            Transfer saved = transferRepository.save(newTransfer(UUID.randomUUID(), "10.00"));

            transactionTemplate.executeWithoutResult(status -> reload(saved.getId()).complete());

            Transfer loaded = reload(saved.getId());
            assertThat(loaded.getStatus()).isEqualTo(TransferStatus.COMPLETED);
            assertThat(loaded.getUpdatedAt()).isAfterOrEqualTo(loaded.getCreatedAt());
            assertThat(loaded.getVersion()).isEqualTo(1L);
        }

        @Test
        void shouldPersistFailedTransferWithReason() {
            Transfer saved = transferRepository.save(newTransfer(UUID.randomUUID(), "10.00"));

            transactionTemplate.executeWithoutResult(status -> reload(saved.getId()).fail("Insufficient balance"));

            Transfer loaded = reload(saved.getId());
            assertThat(loaded.getStatus()).isEqualTo(TransferStatus.FAILED);
            assertThat(loaded.getFailureReason()).isEqualTo("Insufficient balance");
        }

        @Test
        void shouldFindByIdempotencyKey() {
            UUID key = UUID.randomUUID();
            Transfer saved = transferRepository.save(newTransfer(key, "10.00"));
            transferRepository.save(newTransfer(UUID.randomUUID(), "20.00"));

            assertThat(transferRepository.findByIdempotencyKey(key))
                    .get()
                    .extracting(Transfer::getId)
                    .isEqualTo(saved.getId());
        }

        @Test
        void shouldReturnEmptyForUnknownIdempotencyKey() {
            transferRepository.save(newTransfer(UUID.randomUUID(), "10.00"));

            assertThat(transferRepository.findByIdempotencyKey(UUID.randomUUID())).isEmpty();
        }

        @Test
        void shouldRejectDuplicateIdempotencyKey() {
            UUID key = UUID.randomUUID();
            transferRepository.save(newTransfer(key, "10.00"));

            assertThatThrownBy(() -> transferRepository.saveAndFlush(newTransfer(key, "10.00")))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("uk_transfers_idempotency_key");
            assertThat(transferRepository.count()).isEqualTo(1);
        }
    }

    /**
     * A busca do reconciliador (FOR UPDATE SKIP LOCKED) e a reserva com prazo (claimed_until).
     * Só dá para testar contra o PostgreSQL de verdade.
     */
    @Nested
    class Reconciliation {

        private static final Duration LEASE = Duration.ofMinutes(5);

        private Instant cutoff;

        @BeforeEach
        void setUp() {
            cutoff = Instant.now().minusSeconds(30);
        }

        /** Salva uma PENDING e "envelhece" a criação direto no banco (o created_at é @CreationTimestamp). */
        private UUID pendingCreated(Duration ago) {
            Transfer saved = transferRepository.save(newTransfer(UUID.randomUUID(), "10.00"));
            jdbcTemplate.update("UPDATE transfers SET created_at = ? WHERE id = ?",
                    Timestamp.from(Instant.now().minus(ago)), saved.getId());
            return saved.getId();
        }

        private void claimedUntil(UUID id, Instant until) {
            jdbcTemplate.update("UPDATE transfers SET claimed_until = ? WHERE id = ?", Timestamp.from(until), id);
        }

        private static List<UUID> ids(List<Transfer> transfers) {
            return transfers.stream().map(Transfer::getId).toList();
        }

        @Test
        void shouldClaimOnlyOldUnclaimedPendingTransfersOldestFirst() {
            UUID old = pendingCreated(Duration.ofMinutes(10));
            UUID older = pendingCreated(Duration.ofMinutes(20));
            UUID expiredClaim = pendingCreated(Duration.ofMinutes(15));
            claimedUntil(expiredClaim, Instant.now().minusSeconds(60));
            UUID activeClaim = pendingCreated(Duration.ofMinutes(25));
            claimedUntil(activeClaim, Instant.now().plus(LEASE));
            pendingCreated(Duration.ofSeconds(5));
            UUID completed = pendingCreated(Duration.ofMinutes(30));
            jdbcTemplate.update("UPDATE transfers SET status = 'COMPLETED' WHERE id = ?", completed);

            List<Transfer> claimed = transferService.claimPending(cutoff, LEASE, 20);

            assertThat(ids(claimed)).containsExactly(older, expiredClaim, old);
        }

        @Test
        void shouldRespectTheLimit() {
            UUID oldest = pendingCreated(Duration.ofMinutes(30));
            UUID middle = pendingCreated(Duration.ofMinutes(20));
            pendingCreated(Duration.ofMinutes(10));

            List<Transfer> claimed = transferService.claimPending(cutoff, LEASE, 2);

            assertThat(ids(claimed)).containsExactly(oldest, middle);
        }

        @Test
        void shouldSaveTheLeaseInTheDatabase() {
            UUID id = pendingCreated(Duration.ofMinutes(10));
            Instant before = Instant.now();

            transferService.claimPending(cutoff, LEASE, 20);

            Instant claimedUntil = reload(id).getClaimedUntil();
            assertThat(claimedUntil).isBetween(before.plus(LEASE).minusSeconds(1), Instant.now().plus(LEASE));
        }

        @Test
        void shouldNotClaimTheSameTransferTwiceWhileTheLeaseIsValid() {
            UUID id = pendingCreated(Duration.ofMinutes(10));

            List<Transfer> first = transferService.claimPending(cutoff, LEASE, 20);
            List<Transfer> second = transferService.claimPending(cutoff, LEASE, 20);

            assertThat(ids(first)).containsExactly(id);
            assertThat(second).isEmpty();
        }

        @Test
        void shouldLetTheClaimedTransferBeProcessedAfterwards() {
            UUID id = pendingCreated(Duration.ofMinutes(10));
            Transfer claimed = transferService.claimPending(cutoff, LEASE, 20).getFirst();

            claimed.complete();
            transferRepository.save(claimed);

            assertThat(reload(id).getStatus()).isEqualTo(TransferStatus.COMPLETED);
        }

        /**
         * Simula duas réplicas buscando ao mesmo tempo. A primeira trava 2 linhas e segura a transação aberta;
         * a segunda precisa pular essas 2 na hora (sem esperar) e pegar as outras.
         */
        @Test
        void shouldSkipRowsLockedByAnotherTransaction() throws Exception {
            UUID first = pendingCreated(Duration.ofMinutes(40));
            UUID second = pendingCreated(Duration.ofMinutes(30));
            UUID third = pendingCreated(Duration.ofMinutes(20));
            UUID fourth = pendingCreated(Duration.ofMinutes(10));

            CountDownLatch locked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<List<UUID>> replicaA = executor.submit(() -> transactionTemplate.execute(status -> {
                    List<UUID> rows = ids(transferRepository.findPendingToClaim(cutoff, Instant.now(), 2));
                    locked.countDown();
                    await(release);
                    return rows;
                }));
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

                long start = System.nanoTime();
                List<UUID> replicaB = transactionTemplate.execute(
                        status -> ids(transferRepository.findPendingToClaim(cutoff, Instant.now(), 10)));
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
    class Constraints {

        private void insert(String source, String target, String amount, String status) {
            jdbcTemplate.update("""
                    INSERT INTO transfers (id, idempotency_key, source_account_number, target_account_number,
                                           amount, status, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, now())
                    """, UUID.randomUUID(), UUID.randomUUID(), source, target, new BigDecimal(amount), status);
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

        @Test
        void shouldRejectMissingIdempotencyKey() {
            assertThatThrownBy(() -> jdbcTemplate.update("""
                    INSERT INTO transfers (id, source_account_number, target_account_number, amount, status, created_at)
                    VALUES (?, ?, ?, ?, ?, now())
                    """, UUID.randomUUID(), SOURCE, TARGET, new BigDecimal("10.00"), "PENDING"))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("idempotency_key");
        }
    }
}
