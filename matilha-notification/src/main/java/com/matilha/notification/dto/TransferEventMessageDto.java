package com.matilha.notification.dto;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TransferEventMessageDto(
        UUID eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        UUID transferId,
        String sourceAccountNumber,
        String targetAccountNumber,
        String amount,
        String failureCode) {

    public static final String COMPLETED = "TransferCompleted";
    public static final String FAILED = "TransferFailed";
    public static final int SUPPORTED_VERSION = 1;
}
