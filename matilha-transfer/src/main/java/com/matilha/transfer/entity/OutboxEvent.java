package com.matilha.transfer.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Entity
@Getter
@ToString
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "outbox_events")
public class OutboxEvent implements Persistable<UUID> {

    private static final int MAX_ERROR_LENGTH = 500;

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID aggregateId;

    @Column(nullable = false, updatable = false, length = 50)
    private String eventType;

    @Column(nullable = false, updatable = false, length = 50)
    private String messageKey;

    @ToString.Exclude
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    private String payload;

    @Column(nullable = false, updatable = false)
    @CreationTimestamp
    private Instant createdAt;

    @Column
    private Instant publishedAt;

    @Column(nullable = false)
    private int attempts;

    @Column(length = MAX_ERROR_LENGTH)
    private String lastError;

    @Transient
    @ToString.Exclude
    @Getter(AccessLevel.NONE)
    private boolean newEntity = true;

    public OutboxEvent(UUID id, UUID aggregateId, String eventType, String messageKey, String payload) {
        this.id = requireNonNull(id, "id");
        this.aggregateId = requireNonNull(aggregateId, "aggregateId");
        this.eventType = requireNotBlank(eventType, "eventType");
        this.messageKey = requireNotBlank(messageKey, "messageKey");
        this.payload = requireNotBlank(payload, "payload");
    }

    public void markPublished(Instant when) {
        if (publishedAt != null) {
            throw new IllegalStateException("Event " + id + " was already published");
        }
        this.publishedAt = requireNonNull(when, "when");
    }

    public void registerFailure(String error) {
        this.attempts++;
        this.lastError = truncate(error == null || error.isBlank() ? "Unknown error" : error);
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.newEntity = false;
    }

    private static String truncate(String value) {
        return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
    }

    private static <T> T requireNonNull(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        return value;
    }

    private static String requireNotBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
