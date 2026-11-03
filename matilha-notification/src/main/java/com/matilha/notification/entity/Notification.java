package com.matilha.notification.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import com.matilha.notification.exception.BusinessException;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Entity
@Getter
@ToString
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "notifications")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID eventId;

    @Column(nullable = false, updatable = false)
    private UUID transferId;

    @Column(nullable = false, updatable = false, length = 5)
    private String accountNumber;

    @Column(nullable = false, updatable = false)
    @Enumerated(EnumType.STRING)
    private NotificationType type;

    @Column(updatable = false)
    @Enumerated(EnumType.STRING)
    private FailureCode failureCode;

    @Column(nullable = false, updatable = false)
    private String message;

    @Column(nullable = false, updatable = false)
    @CreationTimestamp
    private Instant createdAt;

    public Notification(UUID eventId, UUID transferId, String accountNumber, NotificationType type, String message, FailureCode failureCode) {
        if (eventId == null || transferId == null || type == null) {
            throw new BusinessException("eventId, transferId and type must not be null");
        }
        if ((type == NotificationType.TRANSFER_FAILED) != (failureCode != null)) {
            throw new BusinessException("failureCode is required only for TRANSFER_FAILED");
        }
        this.eventId = eventId;
        this.transferId = transferId;
        this.accountNumber = requiredNotBlank(accountNumber, "accountNumber");
        this.type = type;
        this.message = requiredNotBlank(message, "message");
        this.failureCode = failureCode;
    }

    private static String requiredNotBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(field + " must not be blank");
        }
        return value;
    }

}
