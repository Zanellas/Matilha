package com.matilha.notification.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.matilha.notification.exception.BusinessException;

/**
 * As regras do construtor espelham as da V1. Um dado que o banco recusaria precisa ser recusado aqui,
 * como BusinessException: assim o evento vai direto para a DLT, em vez de virar uma
 * DataIntegrityViolationException que o consumidor tentaria de novo para sempre.
 */
class NotificationTest {

    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID TRANSFER_ID = UUID.randomUUID();
    private static final String OWNER = "00000000-0000-0000-0003-000000000001";
    private static final String MESSAGE = "Você enviou 250.50 para a conta 10002";

    private static Notification sent(String accountNumber) {
        return new Notification(EVENT_ID, TRANSFER_ID, accountNumber, OWNER, NotificationType.TRANSFER_SENT, MESSAGE,
                null);
    }

    @Test
    void shouldCreateValidNotification() {
        Notification notification = sent("10001");

        assertThat(notification.getEventId()).isEqualTo(EVENT_ID);
        assertThat(notification.getTransferId()).isEqualTo(TRANSFER_ID);
        assertThat(notification.getAccountNumber()).isEqualTo("10001");
        assertThat(notification.getOwnerId()).isEqualTo(OWNER);
        assertThat(notification.getType()).isEqualTo(NotificationType.TRANSFER_SENT);
        assertThat(notification.getMessage()).isEqualTo(MESSAGE);
        assertThat(notification.getFailureCode()).isNull();
    }

    /**
     * Ao contrário do account e do transfer, o dono pode faltar: um evento antigo, ou um crédito numa conta
     * sem dono, não é erro de ninguém. Recusar aqui mandaria um evento válido para a DLT.
     */
    @Test
    void shouldAcceptNotificationWithoutOwner() {
        Notification notification = new Notification(EVENT_ID, TRANSFER_ID, "10003", null,
                NotificationType.TRANSFER_RECEIVED, MESSAGE, null);

        assertThat(notification.getOwnerId()).isNull();
        assertThat(notification.getAccountNumber()).isEqualTo("10003");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "   " })
    void shouldRejectBlankAccountNumber(String accountNumber) {
        assertThatThrownBy(() -> sent(accountNumber))
                .isInstanceOf(BusinessException.class);
    }

    /** A coluna é VARCHAR(5), igual ao transfer. */
    @ParameterizedTest
    @ValueSource(strings = { "1000", "100001" })
    void shouldRejectAccountNumberWithoutFiveCharacters(String accountNumber) {
        assertThatThrownBy(() -> sent(accountNumber))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("accountNumber");
    }

    @Test
    void shouldRequireCodeForFailed() {
        assertThatThrownBy(() -> new Notification(EVENT_ID, TRANSFER_ID, "10001", OWNER,
                NotificationType.TRANSFER_FAILED, MESSAGE, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void shouldRejectCodeForSent() {
        assertThatThrownBy(() -> new Notification(EVENT_ID, TRANSFER_ID, "10001", OWNER,
                NotificationType.TRANSFER_SENT, MESSAGE, FailureCode.INSUFFICIENT_BALANCE))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void shouldRejectMissingIds() {
        assertThatThrownBy(() -> new Notification(null, TRANSFER_ID, "10001", OWNER,
                NotificationType.TRANSFER_SENT, MESSAGE, null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new Notification(EVENT_ID, null, "10001", OWNER,
                NotificationType.TRANSFER_SENT, MESSAGE, null))
                .isInstanceOf(BusinessException.class);
    }
}
