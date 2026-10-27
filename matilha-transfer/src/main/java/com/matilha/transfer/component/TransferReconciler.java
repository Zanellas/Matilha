package com.matilha.transfer.component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.matilha.transfer.entity.Transfer;
import com.matilha.transfer.service.TransferService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class TransferReconciler {

    private static final int BATCH_SIZE = 20;

    private final TransferService transferService;

    @Value("${matilha.reconciler.lease}")
    private Duration lease;

    @Value("${matilha.reconciler.min-age}")
    private Duration minAge;

    @Scheduled(fixedDelayString = "${matilha.reconciler.interval}", initialDelayString = "${matilha.reconciler.interval}")
    public void reconcile() {
        Instant cutoff = Instant.now().minus(minAge);
        List<Transfer> pending = transferService.claimPending(cutoff, lease, BATCH_SIZE);
        if (pending.isEmpty()) {
            return;
        }

        log.info("Reconciling {} pending transfers", pending.size());
        for (Transfer transfer : pending) {
            try {
                Transfer result = transferService.process(transfer);
                log.info("Transfer {} reconciled as {}", result.getId(), result.getStatus());
            } catch (ObjectOptimisticLockingFailureException ex) {
                log.info("Transfer {} was finished by another request, skipping", transfer.getId());
            } catch (RuntimeException ex) {
                log.error("Failed to reconcile transfer {}", transfer.getId(), ex);
            }
        }
    }

}