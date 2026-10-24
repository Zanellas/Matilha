package com.matilha.account.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.matilha.account.exception.BusinessException;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
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
@Table(name = "accounts")
public class Account {

    private static final int MONEY_SCALE = 2;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(unique = true, nullable = false, updatable = false, length = 5)
    private String accountNumber;

    @Column(nullable = false)
    private String holderName;

    @ToString.Exclude
    @Column(nullable = false, updatable = false, length = 11)
    private String holderDocument;

    @ToString.Exclude
    @Column(nullable = false, length = 254)
    private String holderEmail;

    @ToString.Exclude
    @Column(nullable = false, length = 11)
    private String holderPhone;

    @Column(precision = 19, scale = MONEY_SCALE, nullable = false)
    private BigDecimal balance;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private Status status;

    @Column(nullable = false, updatable = false)
    @CreationTimestamp
    private Instant createdAt;

    @Column
    @UpdateTimestamp
    private Instant updatedAt;

    @Column
    @Version
    private Long version;

    public Account(String accountNumber, String holderName, String holderDocument, String holderEmail,
            String holderPhone) {
        this.accountNumber = requireNotBlank(accountNumber, "accountNumber");
        this.holderName = requireNotBlank(holderName, "holderName");
        this.holderDocument = normalizeDocument(holderDocument);
        this.holderEmail = normalizeEmail(holderEmail);
        this.holderPhone = normalizePhone(holderPhone);
        this.balance = BigDecimal.ZERO.setScale(MONEY_SCALE);
        this.status = Status.ACTIVE;
    }

    public void deposit(BigDecimal amount) {
        validateAmount(amount);
        requireActive();
        this.balance = this.balance.add(amount);
    }

    public void withdraw(BigDecimal amount) {
        validateAmount(amount);
        requireActive();
        if (balance.compareTo(amount) < 0) {
            throw new BusinessException("Insufficient balance");
        }
        this.balance = this.balance.subtract(amount);
    }

    public void block() {
        if (status != Status.ACTIVE) {
            throw new BusinessException("Only active accounts can be blocked");
        }
        this.status = Status.BLOCKED;
    }

    public void unblock() {
        if (status != Status.BLOCKED) {
            throw new BusinessException("Only blocked accounts can be unblocked");
        }
        this.status = Status.ACTIVE;
    }

    public void close() {
        if (status == Status.CLOSED) {
            throw new BusinessException("Account is already closed");
        }
        if (balance.signum() != 0) {
            throw new BusinessException("Account balance must be zero to close");
        }
        this.status = Status.CLOSED;
    }

    private void requireActive() {
        if (status != Status.ACTIVE) {
            throw new BusinessException("Account is not active");
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

    private static String normalizeEmail(String holderEmail) {
        return requireNotBlank(holderEmail, "holderEmail").trim().toLowerCase();
    }

    private static String normalizePhone(String holderPhone) {
        String digits = requireNotBlank(holderPhone, "holderPhone").replaceAll("\\D", "");
        if (digits.length() < 10 || digits.length() > 11) {
            throw new BusinessException("holderPhone must have 10 or 11 digits");
        }
        return digits;
    }

    private static String normalizeDocument(String document) {
        String digits = requireNotBlank(document, "holderDocument").replaceAll("\\D", "");
        if (digits.length() != 11) {
            throw new BusinessException("holderDocument must have 11 digits");
        }
        return digits;
    }

    public void updateHolderData(String holderName, String holderEmail, String holderPhone) {
        if (status == Status.CLOSED) {
            throw new BusinessException("Closed accounts cannot be updated");
        }
        String newName = holderName != null ? requireNotBlank(holderName, "holderName") : this.holderName;
        String newEmail = holderEmail != null ? normalizeEmail(holderEmail) : this.holderEmail;
        String newPhone = holderPhone != null ? normalizePhone(holderPhone) : this.holderPhone;

        this.holderName = newName;
        this.holderEmail = newEmail;
        this.holderPhone = newPhone;
    }

}
