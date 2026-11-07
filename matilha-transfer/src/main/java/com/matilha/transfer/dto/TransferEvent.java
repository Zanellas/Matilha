package com.matilha.transfer.dto;

import java.time.Instant;
import java.util.UUID;

import com.matilha.transfer.entity.FailureCode;
import com.matilha.transfer.entity.Transfer;

public record TransferEvent(
        UUID eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        UUID transferId,
        String sourceAccountNumber,
        String targetAccountNumber,
        String amount,
        FailureCode failureCode,
        String requestedBy,
        String targetOwnerId) {

    public static final String COMPLETED = "TransferCompleted";
    public static final String FAILED = "TransferFailed";
    public static final int CURRENT_VERSION = 1;

    public static TransferEvent from(Transfer transfer) {
        return new TransferEvent(
                UUID.randomUUID(),
                typeFor(transfer),
                CURRENT_VERSION,
                Instant.now(),
                transfer.getId(),
                transfer.getSourceAccountNumber(),
                transfer.getTargetAccountNumber(),
                transfer.getAmount().toPlainString(),
                transfer.getFailureCode(),
                transfer.getOwnerId(),
                transfer.getTargetOwnerId());
    }

    private static String typeFor(Transfer transfer) {
        return switch (transfer.getStatus()) {
            case COMPLETED -> COMPLETED;
            case FAILED -> FAILED;
            case PENDING -> throw new IllegalStateException(
                    "Pending transfer " + transfer.getId() + " has no event");
        };
    }
}