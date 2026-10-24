package com.matilha.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.matilha.account.dto.CreateAccountRequestDto;
import com.matilha.account.entity.Account;
import com.matilha.account.entity.Status;
import com.matilha.account.exception.AccountNotFoundException;
import com.matilha.account.exception.BusinessException;
import com.matilha.account.repository.AccountRepository;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @InjectMocks
    private AccountService accountService;

    @Nested
    class Create {

        private final CreateAccountRequestDto request = new CreateAccountRequestDto("Ana Silva", "12345678900");

        @Test
        void shouldSaveNewActiveAccountAndReturnResponse() {
            when(accountRepository.existsByAccountNumber(anyString())).thenReturn(false);
            when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

            AccountResponseDto response = accountService.create(request);

            ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
            verify(accountRepository).save(captor.capture());
            Account saved = captor.getValue();

            assertThat(saved.getHolderName()).isEqualTo("Ana Silva");
            assertThat(saved.getHolderDocument()).isEqualTo("12345678900");
            assertThat(saved.getStatus()).isEqualTo(Status.ACTIVE);
            assertThat(saved.getBalance()).isEqualByComparingTo("0.00");

            assertThat(response.accountNumber()).isEqualTo(saved.getAccountNumber());
            assertThat(response.holderName()).isEqualTo("Ana Silva");
            assertThat(response.status()).isEqualTo(Status.ACTIVE);
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
            CreateAccountRequestDto invalidRequest = new CreateAccountRequestDto(" ", "12345678900");

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
            Account account = new Account("12345", "Ana Silva", "12345678900");
            when(accountRepository.findByAccountNumber("12345")).thenReturn(Optional.of(account));

            AccountResponseDto response = accountService.findByAccountNumber("12345");

            assertThat(response).isEqualTo(new AccountResponseDto("12345", "Ana Silva", Status.ACTIVE));
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
        void shouldReturnPageOfResponses() {
            Pageable pageable = PageRequest.of(0, 2);
            List<Account> accounts = List.of(
                    new Account("11111", "Ana Silva", "11111111111"),
                    new Account("22222", "Bruno Costa", "22222222222"));
            when(accountRepository.findAll(pageable)).thenReturn(new PageImpl<>(accounts, pageable, 5));

            Page<AccountResponseDto> page = accountService.findAll(pageable);

            assertThat(page.getContent()).containsExactly(
                    new AccountResponseDto("11111", "Ana Silva", Status.ACTIVE),
                    new AccountResponseDto("22222", "Bruno Costa", Status.ACTIVE));
            assertThat(page.getTotalElements()).isEqualTo(5);
            assertThat(page.getTotalPages()).isEqualTo(3);
        }

        @Test
        void shouldReturnEmptyPageWhenThereAreNoAccounts() {
            Pageable pageable = PageRequest.of(0, 20);
            when(accountRepository.findAll(pageable)).thenReturn(Page.empty(pageable));

            Page<AccountResponseDto> page = accountService.findAll(pageable);

            assertThat(page.getContent()).isEmpty();
            assertThat(page.getTotalElements()).isZero();
        }
    }
}
