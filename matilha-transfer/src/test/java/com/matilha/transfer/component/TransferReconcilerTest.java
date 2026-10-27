package com.matilha.transfer.component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import com.matilha.transfer.entity.Transfer;
import com.matilha.transfer.service.TransferService;

@ExtendWith(MockitoExtension.class)
class TransferReconcilerTest {

    private static final Duration MIN_AGE = Duration.ofSeconds(30);
    private static final Duration LEASE = Duration.ofMinutes(5);

    @Mock
    private TransferService transferService;

    @InjectMocks
    private TransferReconciler reconciler;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(reconciler, "minAge", MIN_AGE);
        ReflectionTestUtils.setField(reconciler, "lease", LEASE);
    }

    private static Transfer pendingTransfer() {
        Transfer transfer = new Transfer(UUID.randomUUID(), "10001", "10002", new BigDecimal("10.00"));
        ReflectionTestUtils.setField(transfer, "id", UUID.randomUUID());
        return transfer;
    }

    @Test
    void shouldClaimOnlyTransfersOlderThanMinAgeWithLeaseAndBatchSize() {
        when(transferService.claimPending(any(), any(), anyInt())).thenReturn(List.of());
        Instant before = Instant.now();

        reconciler.reconcile();

        Instant after = Instant.now();
        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(transferService).claimPending(cutoff.capture(), eq(LEASE), eq(20));
        assertThat(cutoff.getValue()).isBetween(before.minus(MIN_AGE), after.minus(MIN_AGE));
    }

    @Test
    void shouldDoNothingWhenThereIsNothingToReconcile() {
        when(transferService.claimPending(any(), any(), anyInt())).thenReturn(List.of());

        reconciler.reconcile();

        verify(transferService, never()).process(any());
    }

    @Test
    void shouldProcessEveryClaimedTransfer() {
        Transfer first = pendingTransfer();
        Transfer second = pendingTransfer();
        when(transferService.claimPending(any(), any(), anyInt())).thenReturn(List.of(first, second));
        when(transferService.process(any())).thenAnswer(invocation -> invocation.getArgument(0));

        reconciler.reconcile();

        verify(transferService).process(first);
        verify(transferService).process(second);
    }

    @Test
    void shouldKeepGoingWhenOneTransferFails() {
        Transfer broken = pendingTransfer();
        Transfer healthy = pendingTransfer();
        when(transferService.claimPending(any(), any(), anyInt())).thenReturn(List.of(broken, healthy));
        when(transferService.process(broken)).thenThrow(new IllegalStateException("database down"));
        when(transferService.process(healthy)).thenReturn(healthy);

        reconciler.reconcile();

        verify(transferService).process(healthy);
    }

    @Test
    void shouldSkipTransferFinishedByAnotherRequest() {
        Transfer raced = pendingTransfer();
        Transfer next = pendingTransfer();
        when(transferService.claimPending(any(), any(), anyInt())).thenReturn(List.of(raced, next));
        when(transferService.process(raced))
                .thenThrow(new ObjectOptimisticLockingFailureException(Transfer.class, raced.getId()));
        when(transferService.process(next)).thenReturn(next);

        reconciler.reconcile();

        verify(transferService).process(next);
    }
}
