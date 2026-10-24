package com.matilha.account.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Entity
@Getter
@ToString
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "account_movements", uniqueConstraints = @UniqueConstraint(
        name = "uk_account_movements_operation_type",
        columnNames = { "operation_id", "type" }))
public class AccountMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false, updatable = false)
    private Account account;

    @Column(name = "operation_id", nullable = false, updatable = false)
    private UUID operationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 6)
    private MovementType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 10)
    private MovementOperation operation;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "balance_after", nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal balanceAfter;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private AccountMovement(Account account, UUID operationId, MovementType type,
            MovementOperation operation, BigDecimal amount) {
        this.account = account;
        this.operationId = operationId;
        this.type = type;
        this.operation = operation;
        this.amount = amount;
        this.balanceAfter = account.getBalance();
    }

    public static AccountMovement transferDebit(Account account, UUID transferId, BigDecimal amount) {
        return new AccountMovement(account, transferId, MovementType.DEBIT, MovementOperation.TRANSFER, amount);
    }

    public static AccountMovement transferCredit(Account account, UUID transferId, BigDecimal amount) {
        return new AccountMovement(account, transferId, MovementType.CREDIT, MovementOperation.TRANSFER, amount);
    }

    public static AccountMovement deposit(Account account, UUID depositId, BigDecimal amount) {
        return new AccountMovement(account, depositId, MovementType.CREDIT, MovementOperation.DEPOSIT, amount);
    }
}
