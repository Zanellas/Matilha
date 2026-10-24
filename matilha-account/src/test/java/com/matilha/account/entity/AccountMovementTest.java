package com.matilha.account.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AccountMovementTest {

    private static final UUID OPERATION_ID = UUID.fromString("3f2504e0-4f89-11d3-9a0c-0305e82c3301");

    private Account account;

    @BeforeEach
    void setUp() {
        account = new Account("12345", "Ana Silva", "52998224725", "ana.silva@example.com", "11987654321");
        account.deposit(new BigDecimal("500.00"));
    }

    @Test
    void shouldCreateTransferDebitMovement() {
        account.withdraw(new BigDecimal("150.00"));

        AccountMovement debit = AccountMovement.transferDebit(account, OPERATION_ID, new BigDecimal("150.00"));

        assertThat(debit.getType()).isEqualTo(MovementType.DEBIT);
        assertThat(debit.getOperation()).isEqualTo(MovementOperation.TRANSFER);
        assertThat(debit.getAccount()).isSameAs(account);
        assertThat(debit.getOperationId()).isEqualTo(OPERATION_ID);
        assertThat(debit.getAmount()).isEqualByComparingTo("150.00");
        assertThat(debit.getBalanceAfter()).isEqualByComparingTo("350.00");
    }

    @Test
    void shouldCreateTransferCreditMovement() {
        account.deposit(new BigDecimal("150.00"));

        AccountMovement credit = AccountMovement.transferCredit(account, OPERATION_ID, new BigDecimal("150.00"));

        assertThat(credit.getType()).isEqualTo(MovementType.CREDIT);
        assertThat(credit.getOperation()).isEqualTo(MovementOperation.TRANSFER);
        assertThat(credit.getAccount()).isSameAs(account);
        assertThat(credit.getOperationId()).isEqualTo(OPERATION_ID);
        assertThat(credit.getAmount()).isEqualByComparingTo("150.00");
        assertThat(credit.getBalanceAfter()).isEqualByComparingTo("650.00");
    }

    @Test
    void shouldCreateDepositAsCreditMovement() {
        account.deposit(new BigDecimal("80.00"));

        AccountMovement deposit = AccountMovement.deposit(account, OPERATION_ID, new BigDecimal("80.00"));

        assertThat(deposit.getType()).isEqualTo(MovementType.CREDIT);
        assertThat(deposit.getOperation()).isEqualTo(MovementOperation.DEPOSIT);
        assertThat(deposit.getAccount()).isSameAs(account);
        assertThat(deposit.getOperationId()).isEqualTo(OPERATION_ID);
        assertThat(deposit.getAmount()).isEqualByComparingTo("80.00");
        assertThat(deposit.getBalanceAfter()).isEqualByComparingTo("580.00");
    }

    @Test
    void shouldCaptureBalanceAtCreationTime() {
        account.withdraw(new BigDecimal("150.00"));
        AccountMovement debit = AccountMovement.transferDebit(account, OPERATION_ID, new BigDecimal("150.00"));

        account.deposit(new BigDecimal("1000.00"));

        assertThat(debit.getBalanceAfter()).isEqualByComparingTo("350.00");
    }

    @Test
    void shouldNotExposeAccountInToString() {
        AccountMovement debit = AccountMovement.transferDebit(account, OPERATION_ID, new BigDecimal("150.00"));

        assertThat(debit.toString())
                .contains("DEBIT")
                .contains("TRANSFER")
                .doesNotContain("Ana Silva")
                .doesNotContain("52998224725");
    }
}
