package com.matilha.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.matilha.account.dto.AccountResponseDto;
import com.matilha.account.dto.AccountSummaryDto;
import com.matilha.account.dto.CreateAccountRequestDto;
import com.matilha.account.dto.UpdateAccountRequestDto;
import com.matilha.account.entity.Account;
import com.matilha.account.entity.AccountStatus;
import com.matilha.account.exception.AccountNotFoundException;
import com.matilha.account.exception.BusinessException;
import com.matilha.account.repository.AccountRepository;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    private static final String EMAIL = "ana.silva@example.com";
    private static final String PHONE = "11987654321";
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    @Mock
    private AccountRepository accountRepository;

    @InjectMocks
    private AccountService accountService;

    private static Account newAccount(String accountNumber, String holderName) {
        return new Account(accountNumber, holderName, "52998224725", EMAIL, PHONE);
    }

    private static AccountResponseDto responseFor(String accountNumber, String holderName, AccountStatus status) {
        return new AccountResponseDto(accountNumber, holderName, PHONE, EMAIL, status, ZERO);
    }

    @Nested
    class Create {

        private final CreateAccountRequestDto request = new CreateAccountRequestDto(
                "Ana Silva", "529.982.247-25", "Ana.Silva@Example.com", "(11) 98765-4321");

        @Test
        void shouldSaveNewActiveAccountAndReturnResponse() {
            when(accountRepository.existsByAccountNumber(anyString())).thenReturn(false);
            when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

            AccountResponseDto response = accountService.create(request);

            ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
            verify(accountRepository).save(captor.capture());
            Account saved = captor.getValue();

            assertThat(saved.getHolderName()).isEqualTo("Ana Silva");
            assertThat(saved.getStatus()).isEqualTo(AccountStatus.ACTIVE);
            assertThat(saved.getBalance()).isEqualByComparingTo("0.00");

            assertThat(response).isEqualTo(responseFor(saved.getAccountNumber(), "Ana Silva", AccountStatus.ACTIVE));
        }

        @Test
        void shouldSaveContactDataNormalized() {
            when(accountRepository.existsByAccountNumber(anyString())).thenReturn(false);
            when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

            accountService.create(request);

            ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
            verify(accountRepository).save(captor.capture());
            Account saved = captor.getValue();

            assertThat(saved.getHolderDocument()).isEqualTo("52998224725");
            assertThat(saved.getHolderEmail()).isEqualTo(EMAIL);
            assertThat(saved.getHolderPhone()).isEqualTo(PHONE);
        }

        @Test
        void shouldGenerateFiveDigitAccountNumberNotStartingWithZero() {
            when(accountRepository.existsByAccountNumber(anyString())).thenReturn(false);
            when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

            AccountResponseDto response = accountService.create(request);

            assertThat(response.accountNumber()).matches("[1-9]\\d{4}");
        }

        @Test
        void shouldRetryWhenGeneratedAccountNumberAlreadyExists() {
            when(accountRepository.existsByAccountNumber(anyString())).thenReturn(true, true, false);
            when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

            AccountResponseDto response = accountService.create(request);

            ArgumentCaptor<String> numberCaptor = ArgumentCaptor.forClass(String.class);
            verify(accountRepository, times(3)).existsByAccountNumber(numberCaptor.capture());
            assertThat(response.accountNumber()).isEqualTo(numberCaptor.getAllValues().getLast());
        }

        @Test
        void shouldNotSaveWhenRequestIsInvalid() {
            when(accountRepository.existsByAccountNumber(anyString())).thenReturn(false);
            CreateAccountRequestDto invalidRequest = new CreateAccountRequestDto(" ", "52998224725", EMAIL, PHONE);

            assertThatThrownBy(() -> accountService.create(invalidRequest))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("holderName must not be blank");
            verify(accountRepository, never()).save(any());
        }
    }

    @Nested
    class FindByAccountNumber {

        @Test
        void shouldReturnAccountWhenFound() {
            when(accountRepository.findByAccountNumber("12345"))
                    .thenReturn(Optional.of(newAccount("12345", "Ana Silva")));

            AccountResponseDto response = accountService.findByAccountNumber("12345");

            assertThat(response).isEqualTo(responseFor("12345", "Ana Silva", AccountStatus.ACTIVE));
        }

        @Test
        void shouldThrowNotFoundWhenAccountDoesNotExist() {
            when(accountRepository.findByAccountNumber("99999")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> accountService.findByAccountNumber("99999"))
                    .isInstanceOf(AccountNotFoundException.class)
                    .hasMessage("Account 99999 not found");
        }
    }

    @Nested
    class FindAll {

        @Test
        void shouldReturnPageOfSummaries() {
            Pageable pageable = PageRequest.of(0, 2);
            Account blocked = newAccount("22222", "Bruno Costa");
            blocked.block();
            List<Account> accounts = List.of(newAccount("11111", "Ana Silva"), blocked);
            when(accountRepository.findAll(pageable)).thenReturn(new PageImpl<>(accounts, pageable, 5));

            Page<AccountSummaryDto> page = accountService.findAll(pageable);

            assertThat(page.getContent()).containsExactly(
                    new AccountSummaryDto("11111", "Ana Silva", AccountStatus.ACTIVE),
                    new AccountSummaryDto("22222", "Bruno Costa", AccountStatus.BLOCKED));
            assertThat(page.getTotalElements()).isEqualTo(5);
            assertThat(page.getTotalPages()).isEqualTo(3);
        }

        @Test
        void shouldReturnEmptyPageWhenThereAreNoAccounts() {
            Pageable pageable = PageRequest.of(0, 20);
            when(accountRepository.findAll(pageable)).thenReturn(Page.empty(pageable));

            Page<AccountSummaryDto> page = accountService.findAll(pageable);

            assertThat(page.getContent()).isEmpty();
            assertThat(page.getTotalElements()).isZero();
        }
    }

    @Nested
    class Update {

        @Test
        void shouldUpdateHolderDataAndReturnResponse() {
            Account account = newAccount("12345", "Ana Silva");
            when(accountRepository.findByAccountNumber("12345")).thenReturn(Optional.of(account));

            AccountResponseDto response = accountService.update("12345",
                    new UpdateAccountRequestDto("Ana Souza", "Ana.Souza@Example.com", "(21) 98765-4321"));

            assertThat(account.getHolderName()).isEqualTo("Ana Souza");
            assertThat(account.getHolderEmail()).isEqualTo("ana.souza@example.com");
            assertThat(account.getHolderPhone()).isEqualTo("21987654321");
            assertThat(response).isEqualTo(new AccountResponseDto(
                    "12345", "Ana Souza", "21987654321", "ana.souza@example.com", AccountStatus.ACTIVE, ZERO));
        }

        @Test
        void shouldUpdateOnlyProvidedFields() {
            Account account = newAccount("12345", "Ana Silva");
            when(accountRepository.findByAccountNumber("12345")).thenReturn(Optional.of(account));

            accountService.update("12345", new UpdateAccountRequestDto(null, "ana.souza@example.com", null));

            assertThat(account.getHolderName()).isEqualTo("Ana Silva");
            assertThat(account.getHolderEmail()).isEqualTo("ana.souza@example.com");
            assertThat(account.getHolderPhone()).isEqualTo(PHONE);
        }

        @Test
        void shouldNotCallSaveBecauseEntityIsManaged() {
            when(accountRepository.findByAccountNumber("12345"))
                    .thenReturn(Optional.of(newAccount("12345", "Ana Silva")));

            accountService.update("12345", new UpdateAccountRequestDto("Ana Souza", null, null));

            verify(accountRepository, never()).save(any());
        }

        @Test
        void shouldThrowNotFoundWhenAccountDoesNotExist() {
            when(accountRepository.findByAccountNumber("99999")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> accountService.update("99999", new UpdateAccountRequestDto("Ana", null, null)))
                    .isInstanceOf(AccountNotFoundException.class)
                    .hasMessage("Account 99999 not found");
        }

        @Test
        void shouldPropagateBusinessExceptionWhenAccountIsClosed() {
            Account account = newAccount("12345", "Ana Silva");
            account.close();
            when(accountRepository.findByAccountNumber("12345")).thenReturn(Optional.of(account));

            assertThatThrownBy(() -> accountService.update("12345", new UpdateAccountRequestDto("Ana", null, null)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Closed accounts cannot be updated");
        }
    }

    @Nested
    class Close {

        @Test
        void shouldCloseAccountWithZeroBalance() {
            Account account = newAccount("12345", "Ana Silva");
            when(accountRepository.findByAccountNumber("12345")).thenReturn(Optional.of(account));

            accountService.close("12345");

            assertThat(account.getStatus()).isEqualTo(AccountStatus.CLOSED);
        }

        @Test
        void shouldNotDeleteAccountFromDatabase() {
            when(accountRepository.findByAccountNumber("12345"))
                    .thenReturn(Optional.of(newAccount("12345", "Ana Silva")));

            accountService.close("12345");

            verify(accountRepository, never()).delete(any());
            verify(accountRepository, never()).deleteById(any());
        }

        @Test
        void shouldThrowNotFoundWhenAccountDoesNotExist() {
            when(accountRepository.findByAccountNumber("99999")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> accountService.close("99999"))
                    .isInstanceOf(AccountNotFoundException.class)
                    .hasMessage("Account 99999 not found");
        }

        @Test
        void shouldRejectClosingAccountWithBalance() {
            Account account = newAccount("12345", "Ana Silva");
            account.deposit(new BigDecimal("10.00"));
            when(accountRepository.findByAccountNumber("12345")).thenReturn(Optional.of(account));

            assertThatThrownBy(() -> accountService.close("12345"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Account balance must be zero to close");
            assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        }
    }

    @Nested
    class Block {

        @Test
        void shouldBlockActiveAccountAndReturnResponse() {
            Account account = newAccount("12345", "Ana Silva");
            when(accountRepository.findByAccountNumber("12345")).thenReturn(Optional.of(account));

            AccountResponseDto response = accountService.block("12345");

            assertThat(account.getStatus()).isEqualTo(AccountStatus.BLOCKED);
            assertThat(response).isEqualTo(responseFor("12345", "Ana Silva", AccountStatus.BLOCKED));
        }

        @Test
        void shouldRejectBlockingAccountThatIsNotActive() {
            Account account = newAccount("12345", "Ana Silva");
            account.block();
            when(accountRepository.findByAccountNumber("12345")).thenReturn(Optional.of(account));

            assertThatThrownBy(() -> accountService.block("12345"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Only active accounts can be blocked");
        }

        @Test
        void shouldThrowNotFoundWhenAccountDoesNotExist() {
            when(accountRepository.findByAccountNumber("99999")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> accountService.block("99999"))
                    .isInstanceOf(AccountNotFoundException.class)
                    .hasMessage("Account 99999 not found");
        }
    }

    @Nested
    class Unblock {

        @Test
        void shouldUnblockBlockedAccountAndReturnResponse() {
            Account account = newAccount("12345", "Ana Silva");
            account.block();
            when(accountRepository.findByAccountNumber("12345")).thenReturn(Optional.of(account));

            AccountResponseDto response = accountService.unblock("12345");

            assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
            assertThat(response).isEqualTo(responseFor("12345", "Ana Silva", AccountStatus.ACTIVE));
        }

        @Test
        void shouldRejectUnblockingAccountThatIsNotBlocked() {
            when(accountRepository.findByAccountNumber("12345"))
                    .thenReturn(Optional.of(newAccount("12345", "Ana Silva")));

            assertThatThrownBy(() -> accountService.unblock("12345"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Only blocked accounts can be unblocked");
        }

        @Test
        void shouldThrowNotFoundWhenAccountDoesNotExist() {
            when(accountRepository.findByAccountNumber("99999")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> accountService.unblock("99999"))
                    .isInstanceOf(AccountNotFoundException.class)
                    .hasMessage("Account 99999 not found");
        }
    }
}
