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

    private static final String ACCOUNT_NUMBER = "12345";
    private static final String HOLDER_NAME = "Ana Silva";
    private static final String CPF = "52998224725";
    private static final String EMAIL = "ana.silva@example.com";
    private static final String PHONE = "11987654321";

    private Account account;

    @BeforeEach
    void setUp() {
        account = new Account(ACCOUNT_NUMBER, HOLDER_NAME, CPF, EMAIL, PHONE);
    }

    @Nested
    class Creation {

        @Test
        void shouldCreateActiveAccountWithZeroBalance() {
            assertThat(account.getAccountNumber()).isEqualTo(ACCOUNT_NUMBER);
            assertThat(account.getHolderName()).isEqualTo(HOLDER_NAME);
            assertThat(account.getHolderDocument()).isEqualTo(CPF);
            assertThat(account.getHolderEmail()).isEqualTo(EMAIL);
            assertThat(account.getHolderPhone()).isEqualTo(PHONE);
            assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
            assertThat(account.getBalance()).isEqualByComparingTo("0.00");
            assertThat(account.getBalance().scale()).isEqualTo(2);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = { "   " })
        void shouldRejectBlankAccountNumber(String accountNumber) {
            assertThatThrownBy(() -> new Account(accountNumber, HOLDER_NAME, CPF, EMAIL, PHONE))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("accountNumber must not be blank");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = { "   " })
        void shouldRejectBlankHolderName(String holderName) {
            assertThatThrownBy(() -> new Account(ACCOUNT_NUMBER, holderName, CPF, EMAIL, PHONE))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("holderName must not be blank");
        }

        @Test
        void shouldNotExposePersonalDataInToString() {
            assertThat(account.toString())
                    .doesNotContain(CPF)
                    .doesNotContain(EMAIL)
                    .doesNotContain(PHONE);
        }
    }

    @Nested
    class HolderDocument {

        @Test
        void shouldStoreFormattedCpfAsDigitsOnly() {
            Account formatted = new Account(ACCOUNT_NUMBER, HOLDER_NAME, "529.982.247-25", EMAIL, PHONE);

            assertThat(formatted.getHolderDocument()).isEqualTo("52998224725");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = { "   " })
        void shouldRejectBlankDocument(String document) {
            assertThatThrownBy(() -> new Account(ACCOUNT_NUMBER, HOLDER_NAME, document, EMAIL, PHONE))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("holderDocument must not be blank");
        }

        @ParameterizedTest
        @ValueSource(strings = { "1234567890", "123456789012", "529.982.247", "abc" })
        void shouldRejectDocumentWithoutElevenDigits(String document) {
            assertThatThrownBy(() -> new Account(ACCOUNT_NUMBER, HOLDER_NAME, document, EMAIL, PHONE))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("holderDocument must have 11 digits");
        }
    }

    @Nested
    class HolderEmail {

        @Test
        void shouldStoreEmailTrimmedAndInLowerCase() {
            Account mixedCase = new Account(ACCOUNT_NUMBER, HOLDER_NAME, CPF, "  Ana.Silva@Example.COM ", PHONE);

            assertThat(mixedCase.getHolderEmail()).isEqualTo("ana.silva@example.com");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = { "   " })
        void shouldRejectBlankEmail(String email) {
            assertThatThrownBy(() -> new Account(ACCOUNT_NUMBER, HOLDER_NAME, CPF, email, PHONE))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("holderEmail must not be blank");
        }
    }

    @Nested
    class HolderPhone {

        @Test
        void shouldStoreFormattedMobilePhoneAsDigitsOnly() {
            Account formatted = new Account(ACCOUNT_NUMBER, HOLDER_NAME, CPF, EMAIL, "(11) 98765-4321");

            assertThat(formatted.getHolderPhone()).isEqualTo("11987654321");
        }

        @Test
        void shouldAcceptLandlineWithTenDigits() {
            Account landline = new Account(ACCOUNT_NUMBER, HOLDER_NAME, CPF, EMAIL, "(11) 3456-7890");

            assertThat(landline.getHolderPhone()).isEqualTo("1134567890");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = { "   " })
        void shouldRejectBlankPhone(String phone) {
            assertThatThrownBy(() -> new Account(ACCOUNT_NUMBER, HOLDER_NAME, CPF, EMAIL, phone))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("holderPhone must not be blank");
        }

        @ParameterizedTest
        @ValueSource(strings = { "987654321", "119876543210", "(11) 9876" })
        void shouldRejectPhoneWithInvalidLength(String phone) {
            assertThatThrownBy(() -> new Account(ACCOUNT_NUMBER, HOLDER_NAME, CPF, EMAIL, phone))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("holderPhone must have 10 or 11 digits");
        }
    }

    @Nested
    class UpdateHolderData {

        @Test
        void shouldUpdateAllFieldsNormalized() {
            account.updateHolderData("Ana Souza", "  Ana.Souza@Example.COM ", "(21) 3456-7890");

            assertThat(account.getHolderName()).isEqualTo("Ana Souza");
            assertThat(account.getHolderEmail()).isEqualTo("ana.souza@example.com");
            assertThat(account.getHolderPhone()).isEqualTo("2134567890");
        }

        @Test
        void shouldKeepCurrentValuesForNullFields() {
            account.updateHolderData("Ana Souza", null, null);

            assertThat(account.getHolderName()).isEqualTo("Ana Souza");
            assertThat(account.getHolderEmail()).isEqualTo(EMAIL);
            assertThat(account.getHolderPhone()).isEqualTo(PHONE);
        }

        @Test
        void shouldChangeNothingWhenAllFieldsAreNull() {
            account.updateHolderData(null, null, null);

            assertThat(account.getHolderName()).isEqualTo(HOLDER_NAME);
            assertThat(account.getHolderEmail()).isEqualTo(EMAIL);
            assertThat(account.getHolderPhone()).isEqualTo(PHONE);
        }

        @Test
        void shouldNotChangeDocument() {
            account.updateHolderData("Ana Souza", "ana.souza@example.com", "21987654321");

            assertThat(account.getHolderDocument()).isEqualTo(CPF);
        }

        @ParameterizedTest
        @ValueSource(strings = { "", "   " })
        void shouldRejectBlankName(String holderName) {
            assertThatThrownBy(() -> account.updateHolderData(holderName, null, null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("holderName must not be blank");
        }

        @ParameterizedTest
        @ValueSource(strings = { "", "   " })
        void shouldRejectBlankEmail(String holderEmail) {
            assertThatThrownBy(() -> account.updateHolderData(null, holderEmail, null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("holderEmail must not be blank");
        }

        @Test
        void shouldRejectPhoneWithInvalidLength() {
            assertThatThrownBy(() -> account.updateHolderData(null, null, "98765-4321"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("holderPhone must have 10 or 11 digits");
        }

        @Test
        void shouldChangeNothingWhenAnyFieldIsInvalid() {
            assertThatThrownBy(() -> account.updateHolderData("Ana Souza", "ana.souza@example.com", "123"))
                    .isInstanceOf(BusinessException.class);

            assertThat(account.getHolderName()).isEqualTo(HOLDER_NAME);
            assertThat(account.getHolderEmail()).isEqualTo(EMAIL);
            assertThat(account.getHolderPhone()).isEqualTo(PHONE);
        }

        @Test
        void shouldAllowUpdatingBlockedAccount() {
            account.block();

            account.updateHolderData("Ana Souza", null, null);

            assertThat(account.getHolderName()).isEqualTo("Ana Souza");
        }

        @Test
        void shouldRejectUpdatingClosedAccount() {
            account.close();

            assertThatThrownBy(() -> account.updateHolderData("Ana Souza", null, null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Closed accounts cannot be updated");
            assertThat(account.getHolderName()).isEqualTo(HOLDER_NAME);
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

            assertThat(account.getStatus()).isEqualTo(AccountStatus.BLOCKED);
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

            assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
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

            assertThat(account.getStatus()).isEqualTo(AccountStatus.CLOSED);
        }

        @Test
        void shouldCloseBlockedAccountWithZeroBalance() {
            account.block();

            account.close();

            assertThat(account.getStatus()).isEqualTo(AccountStatus.CLOSED);
        }

        @Test
        void shouldRejectClosingAccountWithBalance() {
            account.deposit(new BigDecimal("0.01"));

            assertThatThrownBy(account::close)
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Account balance must be zero to close");
            assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
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
