package com.matilha.transfer.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.matilha.transfer.exception.BusinessException;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Entity
@Getter
@ToString
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "transfers")
public class Transfer {

    private static final int MONEY_SCALE = 2;

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false, length = 5)
    private String sourceAccountNumber;

    @Column(nullable = false, updatable = false, length = 5)
    private String targetAccountNumber;

    @Column(precision = 19, scale = MONEY_SCALE, nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private TransferStatus status;

    @Column
    private String failureReason;

    @Column(nullable = false, updatable = false)
    @CreationTimestamp
    private Instant createdAt;

    @Column
    @UpdateTimestamp
    private Instant updatedAt;

    @Column
    @Version
    private Long version;

    public Transfer(String sourceAccountNumber, String targetAccountNumber, BigDecimal amount) {
        this.sourceAccountNumber = requireNotBlank(sourceAccountNumber, "sourceAccountNumber");
        this.targetAccountNumber = requireNotBlank(targetAccountNumber, "targetAccountNumber");
        if (this.sourceAccountNumber.equals(this.targetAccountNumber)) {
            throw new BusinessException("Source and target accounts must be different");
        }
        validateAmount(amount);
        this.amount = amount;
        this.id = UUID.randomUUID();
        this.status = TransferStatus.PENDING;
    }

    public void complete() {
        requirePending();
        this.status = TransferStatus.COMPLETED;
    }

    public void fail(String reason) {
        requirePending();
        this.failureReason = requireNotBlank(reason, "reason");
        this.status = TransferStatus.FAILED;
    }

    private void requirePending() {
        if (status != TransferStatus.PENDING) {
            throw new BusinessException("Only pending transfers can be finished");
        }
    }

    private static void validateAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessException("Amount must be positive");
        }
        if (amount.stripTrailingZeros().scale() > MONEY_SCALE) {
            throw new BusinessException("Amount must have at most " + MONEY_SCALE + " decimal places");
        }
    }

    private static String requireNotBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(field + " must not be blank");
        }
        return value;
    }
}
