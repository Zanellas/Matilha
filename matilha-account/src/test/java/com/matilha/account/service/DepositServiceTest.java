package com.matilha.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.matilha.account.dto.DepositRequestDto;
import com.matilha.account.dto.DepositResponseDto;
import com.matilha.account.entity.Account;
import com.matilha.account.entity.AccountMovement;
import com.matilha.account.entity.MovementOperation;
import com.matilha.account.entity.MovementType;
import com.matilha.account.exception.AccountNotFoundException;
import com.matilha.account.exception.BusinessException;
import com.matilha.account.repository.AccountMovementRepository;
import com.matilha.account.repository.AccountRepository;

@ExtendWith(MockitoExtension.class)
class DepositServiceTest {

    private static final UUID DEPOSIT_ID = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7");
    private static final String ACCOUNT_NUMBER = "12345";

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private AccountMovementRepository accountMovementRepository;

    @InjectMocks
    private DepositService depositService;

    private static Account accountWithBalance(String balance) {
        Account account = new Account(ACCOUNT_NUMBER, "Ana Silva", "52998224725",
                "ana.silva@example.com", "11987654321");
        if (new BigDecimal(balance).signum() > 0) {
            account.deposit(new BigDecimal(balance));
        }
        return account;
    }

    private static DepositRequestDto request(String amount) {
        return new DepositRequestDto(DEPOSIT_ID, ACCOUNT_NUMBER, new BigDecimal(amount));
    }

    private void givenNewDeposit() {
        when(accountMovementRepository.findByOperationIdAndOperation(DEPOSIT_ID, MovementOperation.DEPOSIT))
                .thenReturn(List.of());
    }

    private void givenLockedAccount(Account account) {
        when(accountRepository.findByAccountNumberForUpdate(ACCOUNT_NUMBER)).thenReturn(Optional.of(account));
    }

    @Nested
    class SuccessfulDeposit {

        @Test
        void shouldIncreaseBalance() {
            Account account = accountWithBalance("100.00");
            givenNewDeposit();
            givenLockedAccount(account);

            depositService.deposit(request("50.25"));

            assertThat(account.getBalance()).isEqualByComparingTo("150.25");
        }

        @Test
        void shouldRecordDepositMovement() {
            Account account = accountWithBalance("100.00");
            givenNewDeposit();
            givenLockedAccount(account);

            depositService.deposit(request("50.25"));

            ArgumentCaptor<AccountMovement> captor = ArgumentCaptor.forClass(AccountMovement.class);
            verify(accountMovementRepository).save(captor.capture());
            AccountMovement movement = captor.getValue();

            assertThat(movement.getType()).isEqualTo(MovementType.CREDIT);
            assertThat(movement.getOperation()).isEqualTo(MovementOperation.DEPOSIT);
            assertThat(movement.getOperationId()).isEqualTo(DEPOSIT_ID);
            assertThat(movement.getAccount()).isSameAs(account);
            assertThat(movement.getAmount()).isEqualByComparingTo("50.25");
            assertThat(movement.getBalanceAfter()).isEqualByComparingTo("150.25");
        }

        @Test
        void shouldReturnResultingBalance() {
            givenNewDeposit();
            givenLockedAccount(accountWithBalance("100.00"));

            DepositResponseDto response = depositService.deposit(request("50.25"));

            assertThat(response.depositId()).isEqualTo(DEPOSIT_ID);
            assertThat(response.accountNumber()).isEqualTo(ACCOUNT_NUMBER);
            assertThat(response.amount()).isEqualByComparingTo("50.25");
            assertThat(response.balanceAfter()).isEqualByComparingTo("150.25");
        }

        @Test
        void shouldAcceptDepositOnAccountWithZeroBalance() {
            Account account = accountWithBalance("0");
            givenNewDeposit();
            givenLockedAccount(account);

            depositService.deposit(request("10.00"));

            assertThat(account.getBalance()).isEqualByComparingTo("10.00");
        }

        @Test
        void shouldUseLockingQueryInsteadOfRegularFind() {
            givenNewDeposit();
            givenLockedAccount(accountWithBalance("0"));

            depositService.deposit(request("10.00"));

            verify(accountRepository, never()).findByAccountNumber(anyString());
        }
    }

    @Nested
    class Idempotency {

        @Test
        void shouldReturnPreviousResultWhenDepositWasAlreadyApplied() {
            Account account = accountWithBalance("150.25");
            when(accountMovementRepository.findByOperationIdAndOperation(DEPOSIT_ID, MovementOperation.DEPOSIT))
                    .thenReturn(List.of(AccountMovement.deposit(account, DEPOSIT_ID, new BigDecimal("50.25"))));

            DepositResponseDto response = depositService.deposit(request("50.25"));

            assertThat(response).isEqualTo(new DepositResponseDto(
                    DEPOSIT_ID, ACCOUNT_NUMBER, new BigDecimal("50.25"), new BigDecimal("150.25")));
        }

        @Test
        void shouldNotCreditAgainWhenDepositWasAlreadyApplied() {
            Account account = accountWithBalance("150.25");
            when(accountMovementRepository.findByOperationIdAndOperation(DEPOSIT_ID, MovementOperation.DEPOSIT))
                    .thenReturn(List.of(AccountMovement.deposit(account, DEPOSIT_ID, new BigDecimal("50.25"))));

            depositService.deposit(request("50.25"));

            assertThat(account.getBalance()).isEqualByComparingTo("150.25");
            verify(accountRepository, never()).findByAccountNumberForUpdate(anyString());
            verify(accountMovementRepository, never()).save(any());
        }

        @Test
        void shouldCheckIdempotencyOnlyAgainstDeposits() {
            givenNewDeposit();
            givenLockedAccount(accountWithBalance("0"));

            depositService.deposit(request("10.00"));

            verify(accountMovementRepository).findByOperationIdAndOperation(DEPOSIT_ID, MovementOperation.DEPOSIT);
        }

        @Test
        void shouldTreatSameAmountWithDifferentScaleAsSameDeposit() {
            givenAppliedDeposit();

            DepositResponseDto response = depositService.deposit(request("50.250"));

            assertThat(response.amount()).isEqualByComparingTo("50.25");
            verify(accountRepository, never()).findByAccountNumberForUpdate(anyString());
        }

        @Test
        void shouldRejectRepeatedDepositIdWithDifferentAmount() {
            givenAppliedDeposit();

            assertRejectedAsDifferentDeposit(request("50.26"));
        }

        @Test
        void shouldRejectRepeatedDepositIdWithDifferentAccount() {
            givenAppliedDeposit();

            assertRejectedAsDifferentDeposit(new DepositRequestDto(DEPOSIT_ID, "11111", new BigDecimal("50.25")));
        }

        private void givenAppliedDeposit() {
            Account account = accountWithBalance("150.25");
            when(accountMovementRepository.findByOperationIdAndOperation(DEPOSIT_ID, MovementOperation.DEPOSIT))
                    .thenReturn(List.of(AccountMovement.deposit(account, DEPOSIT_ID, new BigDecimal("50.25"))));
        }

        private void assertRejectedAsDifferentDeposit(DepositRequestDto request) {
            assertThatThrownBy(() -> depositService.deposit(request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Deposit already processed with different data");
            verify(accountRepository, never()).findByAccountNumberForUpdate(anyString());
            verify(accountMovementRepository, never()).save(any());
        }
    }

    @Nested
    class Validation {

        @Test
        void shouldThrowNotFoundWhenAccountDoesNotExist() {
            givenNewDeposit();
            when(accountRepository.findByAccountNumberForUpdate(ACCOUNT_NUMBER)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> depositService.deposit(request("10.00")))
                    .isInstanceOf(AccountNotFoundException.class)
                    .hasMessage("Account 12345 not found");
            verify(accountMovementRepository, never()).save(any());
        }

        @Test
        void shouldRejectDepositOnBlockedAccount() {
            Account account = accountWithBalance("100.00");
            account.block();
            givenNewDeposit();
            givenLockedAccount(account);

            assertThatThrownBy(() -> depositService.deposit(request("10.00")))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Account is not active");
            assertThat(account.getBalance()).isEqualByComparingTo("100.00");
            verify(accountMovementRepository, never()).save(any());
        }

        @Test
        void shouldRejectDepositOnClosedAccount() {
            Account account = accountWithBalance("0");
            account.close();
            givenNewDeposit();
            givenLockedAccount(account);

            assertThatThrownBy(() -> depositService.deposit(request("10.00")))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Account is not active");
            verify(accountMovementRepository, never()).save(any());
        }

        @Test
        void shouldRejectAmountWithMoreThanTwoDecimalPlaces() {
            givenNewDeposit();
            givenLockedAccount(accountWithBalance("0"));

            assertThatThrownBy(() -> depositService.deposit(request("10.555")))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Amount must have at most 2 decimal places");
            verify(accountMovementRepository, never()).save(any());
        }
    }
}
