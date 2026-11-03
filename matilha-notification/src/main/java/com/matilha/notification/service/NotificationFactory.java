package com.matilha.notification.service;

import java.util.List;

import com.matilha.notification.dto.TransferEventMessageDto;
import com.matilha.notification.entity.FailureCode;
import com.matilha.notification.entity.Notification;
import com.matilha.notification.entity.NotificationType;
import com.matilha.notification.exception.UnsupportedEventException;

public final class NotificationFactory {

    private NotificationFactory() {

    }

    public static List<Notification> from(TransferEventMessageDto event) {
        if (event.eventVersion() > TransferEventMessageDto.SUPPORTED_VERSION) {
            throw new UnsupportedEventException("eventVersion " + event.eventVersion() + " is not supported (max "
                    + TransferEventMessageDto.SUPPORTED_VERSION + ")");
        }
        return switch (event.eventType()) {
            case TransferEventMessageDto.COMPLETED -> List.of(sent(event), received(event));
            case TransferEventMessageDto.FAILED -> List.of(failed(event));
            case null, default ->
                throw new UnsupportedEventException("eventType " + event.eventType() + " is not supported");
        };
    }

    private static Notification sent(TransferEventMessageDto event) {
        return new Notification(event.eventId(), event.transferId(), event.sourceAccountNumber(),
                NotificationType.TRANSFER_SENT,
                "Você enviou " + event.amount() + " para a conta " + event.targetAccountNumber(),
                null);
    }

    private static Notification received(TransferEventMessageDto event) {
        return new Notification(event.eventId(), event.transferId(), event.targetAccountNumber(),
                NotificationType.TRANSFER_RECEIVED,
                "Você recebeu " + event.amount() + " da conta " + event.sourceAccountNumber(),
                null);
    }

    private static Notification failed(TransferEventMessageDto event) {
        return new Notification(event.eventId(), event.transferId(), event.sourceAccountNumber(),
                NotificationType.TRANSFER_FAILED,
                "Sua transferência de " + event.amount() + " para a conta "
                        + event.targetAccountNumber() + " falhou",
                FailureCode.fromCode(event.failureCode()));
    }
}
