package com.matilha.notification.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.springframework.data.domain.Persistable;

import com.matilha.notification.exception.BusinessException;

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
@Table(name = "processed_events")
public class ProcessedEvent implements Persistable<UUID> {

    @Id
    private UUID eventId;

    @Column(nullable = false, updatable = false)
    @CreationTimestamp
    private Instant processedAt;

    @Transient
    @ToString.Exclude
    @Getter(AccessLevel.NONE)
    private boolean newEntity = true;

    public ProcessedEvent(UUID eventId) {
        if (eventId == null) {
            throw new BusinessException("eventId must not be null");
        }
        this.eventId = eventId;
    }

    @Override
    public UUID getId() {
        return eventId;
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

}
