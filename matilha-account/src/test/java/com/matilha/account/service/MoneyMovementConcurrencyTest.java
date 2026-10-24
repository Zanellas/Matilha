package com.matilha.account.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.matilha.account.dto.DepositRequestDto;
import com.matilha.account.dto.TransferRequestDto;
import com.matilha.account.entity.Account;
import com.matilha.account.exception.BusinessException;
import com.matilha.account.repository.AccountMovementRepository;
import com.matilha.account.repository.AccountRepository;

/**
 * Roda contra um PostgreSQL real (Flyway + locks de verdade), por isso precisa do Docker.
 */
@SpringBootTest
@Testcontainers
class MoneyMovementConcurrencyTest {

    private static final int THREADS = 8;
    private static final String SOURCE = "10001";
    private static final String TARGET = "10002";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @Autowired
    private TransferService transferService;

    @Autowired
    private DepositService depositService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AccountMovementRepository accountMovementRepository;

    @BeforeEach
    void cleanDatabase() {
        accountMovementRepository.deleteAll();
        accountRepository.deleteAll();
    }

    private void givenAccount(String accountNumber, String balance) {
        Account account = new Account(accountNumber, "Holder " + accountNumber, "52998224725",
                "holder" + accountNumber + "@example.com", "11987654321");
        if (new BigDecimal(balance).signum() > 0) {
            account.deposit(new BigDecimal(balance));
        }
        accountRepository.save(account);
    }

    private BigDecimal balanceOf(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber).orElseThrow().getBalance();
    }

    private static TransferRequestDto transfer(UUID transferId, String source, String target, String amount) {
        return new TransferRequestDto(transferId, source, target, new BigDecimal(amount));
    }

    /**
     * Dispara {@code times} tarefas ao mesmo tempo e devolve as exceções lançadas.
     * O timeout faz o teste falhar em vez de travar se houver deadlock.
     */
    private static List<Throwable> runConcurrently(int times, IntConsumer task) throws Exception {
        List<Throwable> failures = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(THREADS)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < times; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    start.await();
                    task.accept(index);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                try {
                    future.get(30, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    failures.add(e.getCause());
                }
            }
        }
        return failures;
    }

    @Test
    void concurrentTransfersShouldNeverOverdrawSource() throws Exception {
        givenAccount(SOURCE, "100.00");
        givenAccount(TARGET, "0");

        List<Throwable> failures = runConcurrently(20,
                i -> transferService.transfer(transfer(UUID.randomUUID(), SOURCE, TARGET, "10.00")));

        assertThat(failures).hasSize(10)
                .allSatisfy(failure -> assertThat(failure)
                        .isInstanceOf(BusinessException.class)
                        .hasMessage("Insufficient balance"));
        assertThat(balanceOf(SOURCE)).isEqualByComparingTo("0.00");
        assertThat(balanceOf(TARGET)).isEqualByComparingTo("100.00");
        assertThat(accountMovementRepository.count()).isEqualTo(20);
    }

    @Test
    void transfersInOppositeDirectionsShouldNotDeadlock() throws Exception {
        givenAccount(SOURCE, "1000.00");
        givenAccount(TARGET, "1000.00");

        List<Throwable> failures = runConcurrently(20, i -> {
            if (i % 2 == 0) {
                transferService.transfer(transfer(UUID.randomUUID(), SOURCE, TARGET, "10.00"));
            } else {
                transferService.transfer(transfer(UUID.randomUUID(), TARGET, SOURCE, "10.00"));
            }
        });

        assertThat(failures).isEmpty();
        assertThat(balanceOf(SOURCE)).isEqualByComparingTo("1000.00");
        assertThat(balanceOf(TARGET)).isEqualByComparingTo("1000.00");
        assertThat(accountMovementRepository.count()).isEqualTo(40);
    }

    @Test
    void sameTransferSentConcurrentlyShouldMoveMoneyOnlyOnce() throws Exception {
        givenAccount(SOURCE, "100.00");
        givenAccount(TARGET, "0");
        UUID transferId = UUID.randomUUID();

        List<Throwable> failures = runConcurrently(10,
                i -> transferService.transfer(transfer(transferId, SOURCE, TARGET, "10.00")));

        // Quem perde a corrida esbarra na constraint única (operation_id, type) e vira 409 na API.
        assertThat(failures).allSatisfy(failure -> assertThat(failure)
                .isInstanceOf(DataIntegrityViolationException.class));
        assertThat(balanceOf(SOURCE)).isEqualByComparingTo("90.00");
        assertThat(balanceOf(TARGET)).isEqualByComparingTo("10.00");
        assertThat(accountMovementRepository.count()).isEqualTo(2);
    }

    @Test
    void concurrentDepositsShouldNotLoseUpdates() throws Exception {
        givenAccount(TARGET, "0");

        List<Throwable> failures = runConcurrently(20,
                i -> depositService.deposit(new DepositRequestDto(UUID.randomUUID(), TARGET, new BigDecimal("5.00"))));

        assertThat(failures).isEmpty();
        assertThat(balanceOf(TARGET)).isEqualByComparingTo("100.00");
        assertThat(accountMovementRepository.count()).isEqualTo(20);
    }
}
