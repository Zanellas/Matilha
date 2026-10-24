package com.matilha.account.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.matilha.account.exception.BusinessException;

class AccountTest {

    private Account account;

    @BeforeEach
    void setUp() {
        account = new Account("12345", "Ana Silva", "12345678900");
    }

    @Nested
    class Creation {

        @Test
        void shouldCreateActiveAccountWithZeroBalance() {
            assertThat(account.getAccountNumber()).isEqualTo("12345");
            assertThat(account.getHolderName()).isEqualTo("Ana Silva");
            assertThat(account.getHolderDocument()).isEqualTo("12345678900");
            assertThat(account.getStatus()).isEqualTo(Status.ACTIVE);
            assertThat(account.getBalance()).isEqualByComparingTo("0.00");
            assertThat(account.getBalance().scale()).isEqualTo(2);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = { "   " })
        void shouldRejectBlankAccountNumber(String accountNumber) {
            assertThatThrownBy(() -> new Account(accountNumber, "Ana Silva", "12345678900"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("accountNumber must not be blank");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = { "   " })
        void shouldRejectBlankHolderName(String holderName) {
            assertThatThrownBy(() -> new Account("12345", holderName, "12345678900"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("holderName must not be blank");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = { "   " })
        void shouldRejectBlankHolderDocument(String holderDocument) {
            assertThatThrownBy(() -> new Account("12345", "Ana Silva", holderDocument))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("holderDocument must not be blank");
        }

        @Test
        void shouldNotExposeHolderDocumentInToString() {
            assertThat(account.toString()).doesNotContain("12345678900");
        }
    }

    @Nested
    class Deposit {

        @Test
        void shouldIncreaseBalance() {
            account.deposit(new BigDecimal("100.50"));

            assertThat(account.getBalance()).isEqualByComparingTo("100.50");
        }

        @Test
        void shouldAccumulateMultipleDeposits() {
            account.deposit(new BigDecimal("100.00"));
            account.deposit(new BigDecimal("50.25"));

            assertThat(account.getBalance()).isEqualByComparingTo("150.25");
        }

        @Test
        void shouldAcceptAmountWithTrailingZeros() {
            account.deposit(new BigDecimal("10.500"));

            assertThat(account.getBalance()).isEqualByComparingTo("10.50");
        }

        @Test
        void shouldRejectNullAmount() {
            assertThatThrownBy(() -> account.deposit(null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Amount must be positive");
        }

        @ParameterizedTest
        @ValueSource(strings = { "0", "0.00", "-10.00" })
        void shouldRejectZeroOrNegativeAmount(String amount) {
            assertThatThrownBy(() -> account.deposit(new BigDecimal(amount)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Amount must be positive");
            assertThat(account.getBalance()).isEqualByComparingTo("0.00");
        }

        @Test
        void shouldRejectAmountWithMoreThanTwoDecimalPlaces() {
            assertThatThrownBy(() -> account.deposit(new BigDecimal("10.555")))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Amount must have at most 2 decimal places");
        }

        @Test
        void shouldRejectDepositOnBlockedAccount() {
            account.block();

            assertThatThrownBy(() -> account.deposit(new BigDecimal("10.00")))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Account is not active");
            assertThat(account.getBalance()).isEqualByComparingTo("0.00");
        }

        @Test
        void shouldRejectDepositOnClosedAccount() {
            account.close();

            assertThatThrownBy(() -> account.deposit(new BigDecimal("10.00")))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Account is not active");
        }
    }

    @Nested
    class Withdraw {

        @BeforeEach
        void fundAccount() {
            account.deposit(new BigDecimal("100.00"));
        }

        @Test
        void shouldDecreaseBalance() {
            account.withdraw(new BigDecimal("30.25"));

            assertThat(account.getBalance()).isEqualByComparingTo("69.75");
        }

        @Test
        void shouldAllowWithdrawingEntireBalance() {
            account.withdraw(new BigDecimal("100.00"));

            assertThat(account.getBalance()).isEqualByComparingTo("0.00");
        }

        @Test
        void shouldRejectWithdrawGreaterThanBalance() {
            assertThatThrownBy(() -> account.withdraw(new BigDecimal("100.01")))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Insufficient balance");
            assertThat(account.getBalance()).isEqualByComparingTo("100.00");
        }

        @Test
        void shouldRejectNullAmount() {
            assertThatThrownBy(() -> account.withdraw(null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Amount must be positive");
        }

        @ParameterizedTest
        @ValueSource(strings = { "0", "-10.00" })
        void shouldRejectZeroOrNegativeAmount(String amount) {
            assertThatThrownBy(() -> account.withdraw(new BigDecimal(amount)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Amount must be positive");
        }

        @Test
        void shouldRejectAmountWithMoreThanTwoDecimalPlaces() {
            assertThatThrownBy(() -> account.withdraw(new BigDecimal("1.001")))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Amount must have at most 2 decimal places");
        }

        @Test
        void shouldRejectWithdrawOnBlockedAccount() {
            account.block();

            assertThatThrownBy(() -> account.withdraw(new BigDecimal("10.00")))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Account is not active");
            assertThat(account.getBalance()).isEqualByComparingTo("100.00");
        }
    }

    @Nested
    class StatusTransitions {

        @Test
        void shouldBlockActiveAccount() {
            account.block();

            assertThat(account.getStatus()).isEqualTo(Status.BLOCKED);
        }

        @Test
        void shouldRejectBlockingAccountThatIsNotActive() {
            account.block();

            assertThatThrownBy(account::block)
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Only active accounts can be blocked");
        }

        @Test
        void shouldUnblockBlockedAccount() {
            account.block();

            account.unblock();

            assertThat(account.getStatus()).isEqualTo(Status.ACTIVE);
        }

        @Test
        void shouldRejectUnblockingAccountThatIsNotBlocked() {
            assertThatThrownBy(account::unblock)
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Only blocked accounts can be unblocked");
        }

        @Test
        void shouldCloseAccountWithZeroBalance() {
            account.close();

            assertThat(account.getStatus()).isEqualTo(Status.CLOSED);
        }

        @Test
        void shouldCloseBlockedAccountWithZeroBalance() {
            account.block();

            account.close();

            assertThat(account.getStatus()).isEqualTo(Status.CLOSED);
        }

        @Test
        void shouldRejectClosingAccountWithBalance() {
            account.deposit(new BigDecimal("0.01"));

            assertThatThrownBy(account::close)
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Account balance must be zero to close");
            assertThat(account.getStatus()).isEqualTo(Status.ACTIVE);
        }

        @Test
        void shouldRejectClosingAlreadyClosedAccount() {
            account.close();

            assertThatThrownBy(account::close)
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Account is already closed");
        }

        @Test
        void shouldRejectUnblockingClosedAccount() {
            account.close();

            assertThatThrownBy(account::unblock)
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Only blocked accounts can be unblocked");
        }
    }
}
