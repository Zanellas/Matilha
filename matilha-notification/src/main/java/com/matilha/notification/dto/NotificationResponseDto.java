package com.matilha.notification.dto;

import java.time.Instant;
import java.util.UUID;

import com.matilha.notification.entity.FailureCode;
import com.matilha.notification.entity.Notification;
import com.matilha.notification.entity.NotificationType;

public record NotificationResponseDto(
        UUID id,
        UUID transferId,
        String accountNumber,
        NotificationType type,
        String message,
        FailureCode failureCode,
        Instant createdAt) {

    public static NotificationResponseDto from(Notification notification) {
        return new NotificationResponseDto(
                notification.getId(),
                notification.getTransferId(),
                notification.getAccountNumber(),
                notification.getType(),
                notification.getMessage(),
                notification.getFailureCode(),
                notification.getCreatedAt());

    }
}
