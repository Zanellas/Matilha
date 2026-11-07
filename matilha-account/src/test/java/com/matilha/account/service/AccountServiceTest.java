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
import com.matilha.account.dto.RequesterDto;
import com.matilha.account.dto.UpdateAccountRequestDto;
import com.matilha.account.entity.Account;
import com.matilha.account.entity.AccountStatus;
import com.matilha.account.exception.AccountNotFoundException;
import com.matilha.account.exception.BusinessException;
import com.matilha.account.repository.AccountRepository;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    private static final String ANA = "00000000-0000-0000-0003-000000000001";
    private static final String BRUNO = "00000000-0000-0000-0003-000000000002";
    private static final String SUPORTE = "00000000-0000-0000-0003-000000000003";

    private static final RequesterDto ANA_CUSTOMER = new RequesterDto(ANA, false);
    private static final RequesterDto BRUNO_CUSTOMER = new RequesterDto(BRUNO, false);
    private static final RequesterDto SUPORTE_ADMIN = new RequesterDto(SUPORTE, true);
    private static final String EMAIL = "ana.silva@example.com";
    private static final String PHONE = "11987654321";
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    @Mock
    private AccountRepository accountRepository;

    @InjectMocks
    private AccountService accountService;

    private static Account newAccount(String accountNumber, String holderName) {
        return new Account(ANA, accountNumber, holderName, "52998224725", EMAIL, PHONE);
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

            AccountResponseDto response = accountService.create(request, ANA_CUSTOMER);

            ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
            verify(accountRepository).save(captor.capture());
            Account saved = captor.getValue();

            assertThat(saved.getOwnerId()).isEqualTo(ANA);
            assertThat(saved.getHolderName()).isEqualTo("Ana Silva");
            assertThat(saved.getStatus()).isEqualTo(AccountStatus.ACTIVE);
            assertThat(saved.getBalance()).isEqualByComparingTo("0.00");

            assertThat(response).isEqualTo(responseFor(saved.getAccountNumber(), "Ana Silva", AccountStatus.ACTIVE));
        }

        @Test
        void shouldSaveContactDataNormalized() {
            when(accountRepository.existsByAccountNumber(anyString())).thenReturn(false);
            when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

            accountService.create(request, ANA_CUSTOMER);

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

            AccountResponseDto response = accountService.create(request, ANA_CUSTOMER);

            assertThat(response.accountNumber()).matches("[1-9]\\d{4}");
        }

        @Test
        void shouldRetryWhenGeneratedAccountNumberAlreadyExists() {
            when(accountRepository.existsByAccountNumber(anyString())).thenReturn(true, true, false);
            when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

            AccountResponseDto response = accountService.create(request, ANA_CUSTOMER);

            ArgumentCaptor<String> numberCaptor = ArgumentCaptor.forClass(String.class);
            verify(accountRepository, times(3)).existsByAccountNumber(numberCaptor.capture());
            assertThat(response.accountNumber()).isEqualTo(numberCaptor.getAllValues().getLast());
        }

        @Test
        void shouldNotSaveWhenRequestIsInvalid() {
            when(accountRepository.existsByAccountNumber(anyString())).thenReturn(false);
            CreateAccountRequestDto invalidRequest = new CreateAccountRequestDto(" ", "52998224725", EMAIL, PHONE);

            assertThatThrownBy(() -> accountService.create(invalidRequest, ANA_CUSTOMER))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("holderName must not be blank");
            verify(accountRepository, never()).save(any());
        }

        @Test
        void shouldNotSaveWithoutOwner() {
            when(accountRepository.existsByAccountNumber(anyString())).thenReturn(false);

            assertThatThrownBy(() -> accountService.create(request, new RequesterDto(null, false)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("ownerId must not be blank");
            verify(accountRepository, never()).save(any());
        }
    }

    /**
     * O dono lê a própria conta e o admin lê qualquer uma. A conta de outra pessoa é procurada já filtrada pelo dono,
     * então cai na mesma AccountNotFoundException de uma conta inexistente: "como se não existisse".
     */
    @Nested
    class FindByAccountNumber {

        @Test
        void shouldReturnOwnAccount() {
            when(accountRepository.findByAccountNumberAndOwnerId("12345", ANA))
                    .thenReturn(Optional.of(newAccount("12345", "Ana Silva")));

            AccountResponseDto response = accountService.findByAccountNumber("12345", ANA_CUSTOMER);

            assertThat(response).isEqualTo(responseFor("12345", "Ana Silva", AccountStatus.ACTIVE));
            verify(accountRepository, never()).findByAccountNumber(anyString());
        }

        @Test
        void shouldThrowNotFoundForAccountOfAnotherUser() {
            when(accountRepository.findByAccountNumberAndOwnerId("12345", BRUNO)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> accountService.findByAccountNumber("12345", BRUNO_CUSTOMER))
                    .isInstanceOf(AccountNotFoundException.class)
                    .hasMessage("Account 12345 not found");
        }

        @Test
        void shouldThrowNotFoundWhenAccountDoesNotExist() {
            when(accountRepository.findByAccountNumberAndOwnerId("99999", ANA)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> accountService.findByAccountNumber("99999", ANA_CUSTOMER))
                    .isInstanceOf(AccountNotFoundException.class)
                    .hasMessage("Account 99999 not found");
        }

        @Test
        void shouldLetAdminReadAnyAccount() {
            when(accountRepository.findByAccountNumber("12345"))
                    .thenReturn(Optional.of(newAccount("12345", "Ana Silva")));

            AccountResponseDto response = accountService.findByAccountNumber("12345", SUPORTE_ADMIN);

            assertThat(response.accountNumber()).isEqualTo("12345");
            verify(accountRepository, never()).findByAccountNumberAndOwnerId(anyString(), anyString());
        }

        @Test
        void shouldThrowNotFoundForAdminWhenAccountDoesNotExist() {
            when(accountRepository.findByAccountNumber("99999")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> accountService.findByAccountNumber("99999", SUPORTE_ADMIN))
                    .isInstanceOf(AccountNotFoundException.class)
                    .hasMessage("Account 99999 not found");
        }
    }

    @Nested
    class FindAll {

        @Test
        void shouldReturnOnlyOwnAccountsForCustomer() {
            Pageable pageable = PageRequest.of(0, 2);
            List<Account> accounts = List.of(newAccount("10001", "Ana Silva"));
            when(accountRepository.findByOwnerId(ANA, pageable)).thenReturn(new PageImpl<>(accounts, pageable, 1));

            Page<AccountSummaryDto> page = accountService.findAll(pageable, ANA_CUSTOMER);

            assertThat(page.getContent())
                    .containsExactly(new AccountSummaryDto("10001", "Ana Silva", AccountStatus.ACTIVE));
            verify(accountRepository, never()).findAll(any(Pageable.class));
        }

        @Test
        void shouldReturnAllAccountsForAdmin() {
            Pageable pageable = PageRequest.of(0, 2);
            Account blocked = newAccount("22222", "Bruno Costa");
            blocked.block();
            List<Account> accounts = List.of(newAccount("11111", "Ana Silva"), blocked);
            when(accountRepository.findAll(pageable)).thenReturn(new PageImpl<>(accounts, pageable, 5));

            Page<AccountSummaryDto> page = accountService.findAll(pageable, SUPORTE_ADMIN);

            assertThat(page.getContent()).containsExactly(
                    new AccountSummaryDto("11111", "Ana Silva", AccountStatus.ACTIVE),
                    new AccountSummaryDto("22222", "Bruno Costa", AccountStatus.BLOCKED));
            assertThat(page.getTotalElements()).isEqualTo(5);
            assertThat(page.getTotalPages()).isEqualTo(3);
            verify(accountRepository, never()).findByOwnerId(anyString(), any(Pageable.class));
        }

        @Test
        void shouldReturnEmptyPageWhenCustomerHasNoAccounts() {
            Pageable pageable = PageRequest.of(0, 20);
            when(accountRepository.findByOwnerId(BRUNO, pageable)).thenReturn(Page.empty(pageable));

            Page<AccountSummaryDto> page = accountService.findAll(pageable, BRUNO_CUSTOMER);

            assertThat(page.getContent()).isEmpty();
            assertThat(page.getTotalElements()).isZero();
        }
    }

    @Nested
    class Update {

        @Test
        void shouldUpdateHolderDataAndReturnResponse() {
            Account account = newAccount("12345", "Ana Silva");
            when(accountRepository.findByAccountNumberAndOwnerId("12345", ANA)).thenReturn(Optional.of(account));

            AccountResponseDto response = accountService.update("12345",
                    new UpdateAccountRequestDto("Ana Souza", "Ana.Souza@Example.com", "(21) 98765-4321"), ANA_CUSTOMER);

            assertThat(account.getHolderName()).isEqualTo("Ana Souza");
            assertThat(account.getHolderEmail()).isEqualTo("ana.souza@example.com");
            assertThat(account.getHolderPhone()).isEqualTo("21987654321");
            assertThat(response).isEqualTo(new AccountResponseDto(
                    "12345", "Ana Souza", "21987654321", "ana.souza@example.com", AccountStatus.ACTIVE, ZERO));
        }

        @Test
        void shouldUpdateOnlyProvidedFields() {
            Account account = newAccount("12345", "Ana Silva");
            when(accountRepository.findByAccountNumberAndOwnerId("12345", ANA)).thenReturn(Optional.of(account));

            accountService.update("12345", new UpdateAccountRequestDto(null, "ana.souza@example.com", null), ANA_CUSTOMER);

            assertThat(account.getHolderName()).isEqualTo("Ana Silva");
            assertThat(account.getHolderEmail()).isEqualTo("ana.souza@example.com");
            assertThat(account.getHolderPhone()).isEqualTo(PHONE);
        }

        @Test
        void shouldNotCallSaveBecauseEntityIsManaged() {
            when(accountRepository.findByAccountNumberAndOwnerId("12345", ANA))
                    .thenReturn(Optional.of(newAccount("12345", "Ana Silva")));

            accountService.update("12345", new UpdateAccountRequestDto("Ana Souza", null, null), ANA_CUSTOMER);

            verify(accountRepository, never()).save(any());
        }

        @Test
        void shouldThrowNotFoundWhenAccountDoesNotExist() {
            when(accountRepository.findByAccountNumberAndOwnerId("99999", ANA)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> accountService.update("99999", new UpdateAccountRequestDto("Ana", null, null),
                    ANA_CUSTOMER))
                    .isInstanceOf(AccountNotFoundException.class)
                    .hasMessage("Account 99999 not found");
        }

        /** O caso que a imagem antiga deixou passar no teste manual: a Ana alterando a conta do Bruno. */
        @Test
        void shouldThrowNotFoundWhenUpdatingAccountOfAnotherUser() {
            when(accountRepository.findByAccountNumberAndOwnerId("10002", ANA)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> accountService.update("10002", new UpdateAccountRequestDto("Invasora", null, null),
                    ANA_CUSTOMER))
                    .isInstanceOf(AccountNotFoundException.class)
                    .hasMessage("Account 10002 not found");
            verify(accountRepository, never()).findByAccountNumber(anyString());
        }

        /** O admin lê qualquer conta, mas não altera dados do titular: ele não é dono de nenhuma. */
        @Test
        void shouldNotLetAdminUpdateHolderData() {
            when(accountRepository.findByAccountNumberAndOwnerId("10001", SUPORTE)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> accountService.update("10001", new UpdateAccountRequestDto("Outro", null, null),
                    SUPORTE_ADMIN))
                    .isInstanceOf(AccountNotFoundException.class);
            verify(accountRepository, never()).findByAccountNumber(anyString());
        }

        @Test
        void shouldPropagateBusinessExceptionWhenAccountIsClosed() {
            Account account = newAccount("12345", "Ana Silva");
            account.close();
            when(accountRepository.findByAccountNumberAndOwnerId("12345", ANA)).thenReturn(Optional.of(account));

            assertThatThrownBy(() -> accountService.update("12345", new UpdateAccountRequestDto("Ana", null, null),
                    ANA_CUSTOMER))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Closed accounts cannot be updated");
        }
    }

    @Nested
    class Close {

        @Test
        void shouldCloseAccountWithZeroBalance() {
            Account account = newAccount("12345", "Ana Silva");
            when(accountRepository.findByAccountNumberAndOwnerId("12345", ANA)).thenReturn(Optional.of(account));

            accountService.close("12345", ANA_CUSTOMER);

            assertThat(account.getStatus()).isEqualTo(AccountStatus.CLOSED);
        }

        @Test
        void shouldNotDeleteAccountFromDatabase() {
            when(accountRepository.findByAccountNumberAndOwnerId("12345", ANA))
                    .thenReturn(Optional.of(newAccount("12345", "Ana Silva")));

            accountService.close("12345", ANA_CUSTOMER);

            verify(accountRepository, never()).delete(any());
            verify(accountRepository, never()).deleteById(any());
        }

        @Test
        void shouldThrowNotFoundWhenAccountDoesNotExist() {
            when(accountRepository.findByAccountNumberAndOwnerId("99999", ANA)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> accountService.close("99999", ANA_CUSTOMER))
                    .isInstanceOf(AccountNotFoundException.class)
                    .hasMessage("Account 99999 not found");
        }

        @Test
        void shouldThrowNotFoundWhenClosingAccountOfAnotherUser() {
            when(accountRepository.findByAccountNumberAndOwnerId("10002", ANA)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> accountService.close("10002", ANA_CUSTOMER))
                    .isInstanceOf(AccountNotFoundException.class)
                    .hasMessage("Account 10002 not found");
        }

        @Test
        void shouldRejectClosingAccountWithBalance() {
            Account account = newAccount("12345", "Ana Silva");
            account.deposit(new BigDecimal("10.00"));
            when(accountRepository.findByAccountNumberAndOwnerId("12345", ANA)).thenReturn(Optional.of(account));

            assertThatThrownBy(() -> accountService.close("12345", ANA_CUSTOMER))
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
