package com.matilha.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
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
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.matilha.account.dto.TransferRequestDto;
import com.matilha.account.dto.TransferResponseDto;
import com.matilha.account.entity.Account;
import com.matilha.account.entity.AccountMovement;
import com.matilha.account.entity.MovementOperation;
import com.matilha.account.entity.MovementType;
import com.matilha.account.exception.AccountNotFoundException;
import com.matilha.account.exception.BusinessException;
import com.matilha.account.repository.AccountMovementRepository;
import com.matilha.account.repository.AccountRepository;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    private static final UUID TRANSFER_ID = UUID.fromString("3f2504e0-4f89-11d3-9a0c-0305e82c3301");
    private static final String SOURCE = "12345";
    private static final String TARGET = "67890";

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private AccountMovementRepository accountMovementRepository;

    @InjectMocks
    private TransferService transferService;

    private static Account accountWithBalance(String accountNumber, String balance) {
        Account account = new Account(accountNumber, "Holder " + accountNumber, "52998224725",
                "holder@example.com", "11987654321");
        if (new BigDecimal(balance).signum() > 0) {
            account.deposit(new BigDecimal(balance));
        }
        return account;
    }

    private static TransferRequestDto request(String source, String target, String amount) {
        return new TransferRequestDto(TRANSFER_ID, source, target, new BigDecimal(amount));
    }

    private void givenNewTransfer() {
        when(accountMovementRepository.findByOperationIdAndOperation(TRANSFER_ID, MovementOperation.TRANSFER)).thenReturn(List.of());
    }

    private void givenLockedAccounts(Account... accounts) {
        for (Account account : accounts) {
            when(accountRepository.findByAccountNumberForUpdate(account.getAccountNumber()))
                    .thenReturn(Optional.of(account));
        }
    }

    @SuppressWarnings("unchecked")
    private List<AccountMovement> savedMovements() {
        ArgumentCaptor<List<AccountMovement>> captor = ArgumentCaptor.forClass(List.class);
        verify(accountMovementRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    @Nested
    class SuccessfulTransfer {

        @Test
        void shouldMoveMoneyBetweenAccounts() {
            Account source = accountWithBalance(SOURCE, "500.00");
            Account target = accountWithBalance(TARGET, "100.00");
            givenNewTransfer();
            givenLockedAccounts(source, target);

            transferService.transfer(request(SOURCE, TARGET, "150.00"));

            assertThat(source.getBalance()).isEqualByComparingTo("350.00");
            assertThat(target.getBalance()).isEqualByComparingTo("250.00");
        }

        @Test
        void shouldRecordDebitAndCreditMovements() {
            Account source = accountWithBalance(SOURCE, "500.00");
            Account target = accountWithBalance(TARGET, "100.00");
            givenNewTransfer();
            givenLockedAccounts(source, target);

            transferService.transfer(request(SOURCE, TARGET, "150.00"));

            List<AccountMovement> movements = savedMovements();
            assertThat(movements).hasSize(2);

            AccountMovement debit = movements.get(0);
            assertThat(debit.getType()).isEqualTo(MovementType.DEBIT);
            assertThat(debit.getOperation()).isEqualTo(MovementOperation.TRANSFER);
            assertThat(debit.getAccount()).isSameAs(source);
            assertThat(debit.getOperationId()).isEqualTo(TRANSFER_ID);
            assertThat(debit.getAmount()).isEqualByComparingTo("150.00");
            assertThat(debit.getBalanceAfter()).isEqualByComparingTo("350.00");

            AccountMovement credit = movements.get(1);
            assertThat(credit.getType()).isEqualTo(MovementType.CREDIT);
            assertThat(credit.getOperation()).isEqualTo(MovementOperation.TRANSFER);
            assertThat(credit.getAccount()).isSameAs(target);
            assertThat(credit.getOperationId()).isEqualTo(TRANSFER_ID);
            assertThat(credit.getAmount()).isEqualByComparingTo("150.00");
            assertThat(credit.getBalanceAfter()).isEqualByComparingTo("250.00");
        }

        @Test
        void shouldReturnResultingBalances() {
            givenNewTransfer();
            givenLockedAccounts(accountWithBalance(SOURCE, "500.00"), accountWithBalance(TARGET, "100.00"));

            TransferResponseDto response = transferService.transfer(request(SOURCE, TARGET, "150.00"));

            assertThat(response.transferId()).isEqualTo(TRANSFER_ID);
            assertThat(response.sourceAccountNumber()).isEqualTo(SOURCE);
            assertThat(response.sourceBalanceAfter()).isEqualByComparingTo("350.00");
            assertThat(response.targetAccountNumber()).isEqualTo(TARGET);
            assertThat(response.targetBalanceAfter()).isEqualByComparingTo("250.00");
            assertThat(response.amount()).isEqualByComparingTo("150.00");
        }

        @Test
        void shouldAllowTransferringEntireBalance() {
            Account source = accountWithBalance(SOURCE, "150.00");
            givenNewTransfer();
            givenLockedAccounts(source, accountWithBalance(TARGET, "0"));

            transferService.transfer(request(SOURCE, TARGET, "150.00"));

            assertThat(source.getBalance()).isEqualByComparingTo("0.00");
        }

        @Test
        void shouldUseLockingQueryInsteadOfRegularFind() {
            givenNewTransfer();
            givenLockedAccounts(accountWithBalance(SOURCE, "500.00"), accountWithBalance(TARGET, "0"));

            transferService.transfer(request(SOURCE, TARGET, "150.00"));

            verify(accountRepository, never()).findByAccountNumber(anyString());
        }
    }

    @Nested
    class LockOrder {

        @Test
        void shouldLockSourceFirstWhenSourceHasLowerNumber() {
            givenNewTransfer();
            givenLockedAccounts(accountWithBalance(SOURCE, "500.00"), accountWithBalance(TARGET, "0"));

            transferService.transfer(request(SOURCE, TARGET, "10.00"));

            InOrder order = inOrder(accountRepository);
            order.verify(accountRepository).findByAccountNumberForUpdate(SOURCE);
            order.verify(accountRepository).findByAccountNumberForUpdate(TARGET);
        }

        @Test
        void shouldLockTargetFirstWhenTargetHasLowerNumber() {
            givenNewTransfer();
            givenLockedAccounts(accountWithBalance(TARGET, "500.00"), accountWithBalance(SOURCE, "0"));

            transferService.transfer(request(TARGET, SOURCE, "10.00"));

            InOrder order = inOrder(accountRepository);
            order.verify(accountRepository).findByAccountNumberForUpdate(SOURCE);
            order.verify(accountRepository).findByAccountNumberForUpdate(TARGET);
        }
    }

    @Nested
    class Idempotency {

        @Test
        void shouldReturnPreviousResultWhenTransferWasAlreadyApplied() {
            Account source = accountWithBalance(SOURCE, "350.00");
            Account target = accountWithBalance(TARGET, "250.00");
            BigDecimal amount = new BigDecimal("150.00");
            when(accountMovementRepository.findByOperationIdAndOperation(TRANSFER_ID, MovementOperation.TRANSFER)).thenReturn(List.of(
                    AccountMovement.transferDebit(source, TRANSFER_ID, amount),
                    AccountMovement.transferCredit(target, TRANSFER_ID, amount)));

            TransferResponseDto response = transferService.transfer(request(SOURCE, TARGET, "150.00"));

            assertThat(response).isEqualTo(new TransferResponseDto(TRANSFER_ID,
                    SOURCE, new BigDecimal("350.00"), TARGET, new BigDecimal("250.00"), amount));
        }

        @Test
        void shouldNotMoveMoneyAgainWhenTransferWasAlreadyApplied() {
            Account source = accountWithBalance(SOURCE, "350.00");
            Account target = accountWithBalance(TARGET, "250.00");
            BigDecimal amount = new BigDecimal("150.00");
            when(accountMovementRepository.findByOperationIdAndOperation(TRANSFER_ID, MovementOperation.TRANSFER)).thenReturn(List.of(
                    AccountMovement.transferCredit(target, TRANSFER_ID, amount),
                    AccountMovement.transferDebit(source, TRANSFER_ID, amount)));

            transferService.transfer(request(SOURCE, TARGET, "150.00"));

            assertThat(source.getBalance()).isEqualByComparingTo("350.00");
            assertThat(target.getBalance()).isEqualByComparingTo("250.00");
            verify(accountRepository, never()).findByAccountNumberForUpdate(anyString());
            verify(accountMovementRepository, never()).saveAll(anyIterable());
        }
    }

    @Nested
    class Validation {

        @Test
        void shouldRejectSameSourceAndTarget() {
            givenNewTransfer();

            assertThatThrownBy(() -> transferService.transfer(request(SOURCE, SOURCE, "10.00")))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Source and target accounts must be different");
            verify(accountRepository, never()).findByAccountNumberForUpdate(anyString());
        }

        @Test
        void shouldThrowNotFoundWhenSourceDoesNotExist() {
            givenNewTransfer();
            when(accountRepository.findByAccountNumberForUpdate(SOURCE)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> transferService.transfer(request(SOURCE, TARGET, "10.00")))
                    .isInstanceOf(AccountNotFoundException.class)
                    .hasMessage("Account 12345 not found");
            verify(accountMovementRepository, never()).saveAll(anyIterable());
        }

        @Test
        void shouldThrowNotFoundWhenTargetDoesNotExist() {
            givenNewTransfer();
            givenLockedAccounts(accountWithBalance(SOURCE, "500.00"));
            when(accountRepository.findByAccountNumberForUpdate(TARGET)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> transferService.transfer(request(SOURCE, TARGET, "10.00")))
                    .isInstanceOf(AccountNotFoundException.class)
                    .hasMessage("Account 67890 not found");
            verify(accountMovementRepository, never()).saveAll(anyIterable());
        }

        @Test
        void shouldRejectInsufficientBalance() {
            Account source = accountWithBalance(SOURCE, "100.00");
            Account target = accountWithBalance(TARGET, "0");
            givenNewTransfer();
            givenLockedAccounts(source, target);

            assertThatThrownBy(() -> transferService.transfer(request(SOURCE, TARGET, "100.01")))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Insufficient balance");
            assertThat(source.getBalance()).isEqualByComparingTo("100.00");
            assertThat(target.getBalance()).isEqualByComparingTo("0.00");
            verify(accountMovementRepository, never()).saveAll(anyIterable());
        }

        @Test
        void shouldRejectWhenSourceIsBlocked() {
            Account source = accountWithBalance(SOURCE, "500.00");
            source.block();
            givenNewTransfer();
            givenLockedAccounts(source, accountWithBalance(TARGET, "0"));

            assertThatThrownBy(() -> transferService.transfer(request(SOURCE, TARGET, "10.00")))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Account is not active");
            verify(accountMovementRepository, never()).saveAll(anyIterable());
        }

        @Test
        void shouldRejectWhenTargetIsBlocked() {
            Account target = accountWithBalance(TARGET, "0");
            target.block();
            givenNewTransfer();
            givenLockedAccounts(accountWithBalance(SOURCE, "500.00"), target);

            assertThatThrownBy(() -> transferService.transfer(request(SOURCE, TARGET, "10.00")))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Account is not active");
            verify(accountMovementRepository, never()).saveAll(anyIterable());
        }

        @Test
        void shouldRejectWhenTargetIsClosed() {
            Account target = accountWithBalance(TARGET, "0");
            target.close();
            givenNewTransfer();
            givenLockedAccounts(accountWithBalance(SOURCE, "500.00"), target);

            assertThatThrownBy(() -> transferService.transfer(request(SOURCE, TARGET, "10.00")))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Account is not active");
            verify(accountMovementRepository, never()).saveAll(anyIterable());
        }

        @Test
        void shouldRejectAmountWithMoreThanTwoDecimalPlaces() {
            givenNewTransfer();
            givenLockedAccounts(accountWithBalance(SOURCE, "500.00"), accountWithBalance(TARGET, "0"));

            assertThatThrownBy(() -> transferService.transfer(request(SOURCE, TARGET, "10.555")))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Amount must have at most 2 decimal places");
            verify(accountMovementRepository, never()).saveAll(any());
        }
    }
}
