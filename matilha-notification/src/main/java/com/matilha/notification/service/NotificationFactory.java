package com.matilha.notification.service;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.List;
import java.util.Locale;

import com.matilha.notification.dto.TransferEventMessageDto;
import com.matilha.notification.entity.FailureCode;
import com.matilha.notification.entity.Notification;
import com.matilha.notification.entity.NotificationType;
import com.matilha.notification.exception.UnsupportedEventException;

public final class NotificationFactory {

    private static final Locale PT_BR = Locale.of("pt", "BR");

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
                event.requestedBy(),
                NotificationType.TRANSFER_SENT,
                "Você enviou " + brl(event.amount()) + " para a conta " + event.targetAccountNumber(),
                null);
    }

    private static Notification received(TransferEventMessageDto event) {
        return new Notification(event.eventId(), event.transferId(), event.targetAccountNumber(),
                event.targetOwnerId(),
                NotificationType.TRANSFER_RECEIVED,
                "Você recebeu " + brl(event.amount()) + " da conta " + event.sourceAccountNumber(),
                null);
    }

    private static Notification failed(TransferEventMessageDto event) {
        return new Notification(event.eventId(), event.transferId(), event.sourceAccountNumber(),
                event.requestedBy(),
                NotificationType.TRANSFER_FAILED,
                "Sua transferência de " + brl(event.amount()) + " para a conta "
                        + event.targetAccountNumber() + " falhou",
                FailureCode.fromCode(event.failureCode()));
    }

    private static String brl(String amount) {
        if (amount == null || amount.isBlank()) {
            throw new UnsupportedEventException("amount must not be blank");
        }
        try {
            DecimalFormat format = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(PT_BR));
            return "R$ " + format.format(new BigDecimal(amount));
        } catch (NumberFormatException e) {
            throw new UnsupportedEventException("amount " + amount + " is not a number");
        }
    }
}
