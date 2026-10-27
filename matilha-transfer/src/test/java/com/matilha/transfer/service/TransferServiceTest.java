package com.matilha.transfer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import com.matilha.transfer.client.AccountClient;
import com.matilha.transfer.dto.AccountTransferRequestDto;
import com.matilha.transfer.dto.CreateTransferRequestDto;
import com.matilha.transfer.dto.TransferResponseDto;
import com.matilha.transfer.entity.Transfer;
import com.matilha.transfer.entity.TransferStatus;
import com.matilha.transfer.exception.AccountTransferRejectedException;
import com.matilha.transfer.exception.AccountUnavailableException;
import com.matilha.transfer.exception.BusinessException;
import com.matilha.transfer.exception.TransferNotFoundException;
import com.matilha.transfer.repository.TransferRepository;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    private static final UUID KEY = UUID.fromString("3f1c2a9e-7b4d-4c1a-9e2f-5a6b7c8d9e01");
    private static final UUID TRANSFER_ID = UUID.fromString("8a2b4c6d-1e3f-4a5b-8c7d-9e0f1a2b3c4d");
    private static final String SOURCE = "10001";
    private static final String TARGET = "10002";
    private static final CreateTransferRequestDto REQUEST =
            new CreateTransferRequestDto(SOURCE, TARGET, new BigDecimal("250.50"));

    @Mock
    private TransferRepository transferRepository;

    @Mock
    private AccountClient accountClient;

    @InjectMocks
    private TransferService transferService;

    /** Status da transferência no momento de cada save, na ordem em que aconteceram. */
    private final List<TransferStatus> savedStatuses = new ArrayList<>();

    /**
     * Faz o mock do save se comportar como o Hibernate: preenche o id (que é @GeneratedValue)
     * e devolve a entidade.
     */
    private void saveBehavesLikeDatabase() {
        when(transferRepository.save(any(Transfer.class))).thenAnswer(invocation -> {
            Transfer transfer = invocation.getArgument(0);
            if (transfer.getId() == null) {
                ReflectionTestUtils.setField(transfer, "id", TRANSFER_ID);
            }
            savedStatuses.add(transfer.getStatus());
            return transfer;
        });
    }

    @Nested
    class HappyPath {

        @BeforeEach
        void setUp() {
            saveBehavesLikeDatabase();
        }

        @Test
        void shouldCompleteTransferWhenAccountAccepts() {
            TransferResponseDto response = transferService.create(KEY, REQUEST);

            assertThat(response.id()).isEqualTo(TRANSFER_ID);
            assertThat(response.status()).isEqualTo(TransferStatus.COMPLETED);
            assertThat(response.sourceAccountNumber()).isEqualTo(SOURCE);
            assertThat(response.targetAccountNumber()).isEqualTo(TARGET);
            assertThat(response.amount()).isEqualByComparingTo("250.50");
            assertThat(response.failureReason()).isNull();
        }

        @Test
        void shouldSavePendingBeforeCallingAccountAndCompletedAfter() {
            transferService.create(KEY, REQUEST);

            InOrder order = inOrder(transferRepository, accountClient);
            order.verify(transferRepository).save(any(Transfer.class));
            order.verify(accountClient).transfer(any(AccountTransferRequestDto.class));
            order.verify(transferRepository).save(any(Transfer.class));
            assertThat(savedStatuses).containsExactly(TransferStatus.PENDING, TransferStatus.COMPLETED);
        }

        @Test
        void shouldSendTransferIdAndDataToAccount() {
            transferService.create(KEY, REQUEST);

            ArgumentCaptor<AccountTransferRequestDto> captor = ArgumentCaptor.forClass(AccountTransferRequestDto.class);
            verify(accountClient).transfer(captor.capture());
            AccountTransferRequestDto sent = captor.getValue();
            assertThat(sent.transferId()).isEqualTo(TRANSFER_ID);
            assertThat(sent.sourceAccountNumber()).isEqualTo(SOURCE);
            assertThat(sent.targetAccountNumber()).isEqualTo(TARGET);
            assertThat(sent.amount()).isEqualByComparingTo("250.50");
        }

        @Test
        void shouldNotSendIdempotencyKeyAsTransferId() {
            transferService.create(KEY, REQUEST);

            ArgumentCaptor<AccountTransferRequestDto> captor = ArgumentCaptor.forClass(AccountTransferRequestDto.class);
            verify(accountClient).transfer(captor.capture());
            assertThat(captor.getValue().transferId()).isNotEqualTo(KEY);
        }
    }

    @Nested
    class AccountRejects {

        @BeforeEach
        void setUp() {
            saveBehavesLikeDatabase();
            when(accountClient.transfer(any())).thenThrow(new AccountTransferRejectedException("Insufficient balance"));
        }

        @Test
        void shouldFailTransferWithReason() {
            TransferResponseDto response = transferService.create(KEY, REQUEST);

            assertThat(response.status()).isEqualTo(TransferStatus.FAILED);
            assertThat(response.failureReason()).isEqualTo("Insufficient balance");
        }

        @Test
        void shouldSaveFailedStatus() {
            transferService.create(KEY, REQUEST);

            assertThat(savedStatuses).containsExactly(TransferStatus.PENDING, TransferStatus.FAILED);
        }
    }

    @Nested
    class AccountUnavailable {

        @BeforeEach
        void setUp() {
            saveBehavesLikeDatabase();
            when(accountClient.transfer(any())).thenThrow(
                    new AccountUnavailableException("Account service unavailable", new RuntimeException("timeout")));
        }

        @Test
        void shouldKeepTransferPendingWithoutThrowing() {
            TransferResponseDto response = transferService.create(KEY, REQUEST);

            assertThat(response.id()).isEqualTo(TRANSFER_ID);
            assertThat(response.status()).isEqualTo(TransferStatus.PENDING);
            assertThat(response.failureReason()).isNull();
        }

        @Test
        void shouldNeverMarkAsFailed() {
            transferService.create(KEY, REQUEST);

            assertThat(savedStatuses).containsOnly(TransferStatus.PENDING);
        }
    }

    @Nested
    class UnexpectedError {

        @Test
        void shouldPropagateAndLeaveTransferPending() {
            saveBehavesLikeDatabase();
            when(accountClient.transfer(any())).thenThrow(new IllegalStateException("bug"));

            assertThatThrownBy(() -> transferService.create(KEY, REQUEST))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(savedStatuses).containsExactly(TransferStatus.PENDING);
        }
    }

    @Nested
    class Replay {

        private Transfer existing(TransferStatus status) {
            Transfer transfer = new Transfer(KEY, SOURCE, TARGET, new BigDecimal("250.50"));
            ReflectionTestUtils.setField(transfer, "id", TRANSFER_ID);
            if (status == TransferStatus.COMPLETED) {
                transfer.complete();
            } else if (status == TransferStatus.FAILED) {
                transfer.fail("Insufficient balance");
            }
            return transfer;
        }

        @ParameterizedTest
        @EnumSource(TransferStatus.class)
        void shouldReturnExistingTransferInItsCurrentState(TransferStatus status) {
            when(transferRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.of(existing(status)));

            TransferResponseDto response = transferService.create(KEY, REQUEST);

            assertThat(response.id()).isEqualTo(TRANSFER_ID);
            assertThat(response.status()).isEqualTo(status);
        }

        @Test
        void shouldNotCallAccountNorSaveAgain() {
            when(transferRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.of(existing(TransferStatus.COMPLETED)));

            transferService.create(KEY, REQUEST);

            verify(accountClient, never()).transfer(any());
            verify(transferRepository, never()).save(any());
        }

        @Test
        void shouldKeepFailureReasonOfFailedTransfer() {
            when(transferRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.of(existing(TransferStatus.FAILED)));

            TransferResponseDto response = transferService.create(KEY, REQUEST);

            assertThat(response.failureReason()).isEqualTo("Insufficient balance");
        }

        @ParameterizedTest
        @ValueSource(strings = { "250.5", "250.500" })
        void shouldTreatSameAmountWithDifferentScaleAsSameRequest(String amount) {
            when(transferRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.of(existing(TransferStatus.COMPLETED)));

            TransferResponseDto response = transferService.create(KEY,
                    new CreateTransferRequestDto(SOURCE, TARGET, new BigDecimal(amount)));

            assertThat(response.id()).isEqualTo(TRANSFER_ID);
        }

        @Test
        void shouldRejectSameKeyWithDifferentAmount() {
            when(transferRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.of(existing(TransferStatus.COMPLETED)));
            CreateTransferRequestDto different = new CreateTransferRequestDto(SOURCE, TARGET, new BigDecimal("999.00"));

            assertThatThrownBy(() -> transferService.create(KEY, different))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Idempotency key already used with different data");
            verify(accountClient, never()).transfer(any());
            verify(transferRepository, never()).save(any());
        }

        @Test
        void shouldRejectSameKeyWithDifferentAccounts() {
            when(transferRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.of(existing(TransferStatus.COMPLETED)));
            CreateTransferRequestDto different = new CreateTransferRequestDto(SOURCE, "10003", new BigDecimal("250.50"));

            assertThatThrownBy(() -> transferService.create(KEY, different))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Idempotency key already used with different data");
        }
    }

    @Nested
    class Race {

        private Transfer winner(String target) {
            Transfer transfer = new Transfer(KEY, SOURCE, target, new BigDecimal("250.50"));
            ReflectionTestUtils.setField(transfer, "id", TRANSFER_ID);
            return transfer;
        }

        @Test
        void shouldReplayWinnerWhenSaveLosesTheRace() {
            when(transferRepository.findByIdempotencyKey(KEY))
                    .thenReturn(Optional.empty(), Optional.of(winner(TARGET)));
            when(transferRepository.save(any(Transfer.class)))
                    .thenThrow(new DataIntegrityViolationException("uk_transfers_idempotency_key"));

            TransferResponseDto response = transferService.create(KEY, REQUEST);

            assertThat(response.id()).isEqualTo(TRANSFER_ID);
            assertThat(response.status()).isEqualTo(TransferStatus.PENDING);
            verify(accountClient, never()).transfer(any());
        }

        @Test
        void shouldRejectWhenWinnerHasDifferentData() {
            when(transferRepository.findByIdempotencyKey(KEY))
                    .thenReturn(Optional.empty(), Optional.of(winner("10003")));
            when(transferRepository.save(any(Transfer.class)))
                    .thenThrow(new DataIntegrityViolationException("uk_transfers_idempotency_key"));

            assertThatThrownBy(() -> transferService.create(KEY, REQUEST))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Idempotency key already used with different data");
            verify(accountClient, never()).transfer(any());
        }

        @Test
        void shouldRethrowViolationThatIsNotAboutTheKey() {
            DataIntegrityViolationException otherViolation = new DataIntegrityViolationException("ck_transfers_status");
            when(transferRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
            when(transferRepository.save(any(Transfer.class))).thenThrow(otherViolation);

            assertThatThrownBy(() -> transferService.create(KEY, REQUEST)).isSameAs(otherViolation);
            verify(accountClient, never()).transfer(any());
        }
    }

    @Nested
    class FindById {

        @Test
        void shouldReturnTransferAsDto() {
            Transfer transfer = new Transfer(KEY, SOURCE, TARGET, new BigDecimal("250.50"));
            ReflectionTestUtils.setField(transfer, "id", TRANSFER_ID);
            when(transferRepository.findById(TRANSFER_ID)).thenReturn(Optional.of(transfer));

            TransferResponseDto response = transferService.findById(TRANSFER_ID);

            assertThat(response.id()).isEqualTo(TRANSFER_ID);
            assertThat(response.status()).isEqualTo(TransferStatus.PENDING);
        }

        @Test
        void shouldThrowWhenNotFound() {
            when(transferRepository.findById(TRANSFER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> transferService.findById(TRANSFER_ID))
                    .isInstanceOf(TransferNotFoundException.class)
                    .hasMessage("Transfer not found: " + TRANSFER_ID);
        }
    }

    @Nested
    class InvalidData {

        @Test
        void shouldRejectSameAccountsWithoutSavingOrCallingAccount() {
            CreateTransferRequestDto sameAccounts = new CreateTransferRequestDto(SOURCE, SOURCE, new BigDecimal("10.00"));

            assertThatThrownBy(() -> transferService.create(KEY, sameAccounts))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Source and target accounts must be different");
            verify(transferRepository, never()).save(any());
            verify(accountClient, never()).transfer(any());
        }
    }
}
