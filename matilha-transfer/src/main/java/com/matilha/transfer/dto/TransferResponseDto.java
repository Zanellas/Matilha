package com.matilha.transfer.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.matilha.transfer.entity.Transfer;
import com.matilha.transfer.entity.TransferStatus;

public record TransferResponseDto(
        UUID id,
        TransferStatus status,
        String sourceAccountNumber,
        String targetAccountNumber,
        BigDecimal amount,
        String failureReason,
        Instant createdAt) {

    public static TransferResponseDto from(Transfer transfer) {
        return new TransferResponseDto(
                transfer.getId(),
                transfer.getStatus(),
                transfer.getSourceAccountNumber(),
                transfer.getTargetAccountNumber(),
                transfer.getAmount(),
                transfer.getFailureReason(),
                transfer.getCreatedAt());
    }
}
