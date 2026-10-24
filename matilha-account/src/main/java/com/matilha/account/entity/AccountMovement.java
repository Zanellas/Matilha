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
@Table(name = "account_movements", uniqueConstraints = @UniqueConstraint(name = "uk_account_movements_transfer_type", columnNames = {
        "transfer_id", "type" }))
public class AccountMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false, updatable = false)
    private Account account;

    @Column(name = "transfer_id", nullable = false, updatable = false)
    private UUID transferId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 6)
    private MovementType type;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "balance_after", nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal balanceAfter;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private AccountMovement(Account account, UUID transferId, MovementType type, BigDecimal amount) {
        this.account = account;
        this.transferId = transferId;
        this.type = type;
        this.amount = amount;
        this.balanceAfter = account.getBalance();
    }

    public static AccountMovement debit(Account account, UUID transferId, BigDecimal amount) {
        return new AccountMovement(account, transferId, MovementType.DEBIT, amount);
    }

    public static AccountMovement credit(Account account, UUID transferId, BigDecimal amount) {
        return new AccountMovement(account, transferId, MovementType.CREDIT, amount);
    }
}
