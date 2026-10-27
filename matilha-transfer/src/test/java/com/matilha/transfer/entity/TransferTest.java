package com.matilha.transfer.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.matilha.transfer.exception.BusinessException;

class TransferTest {

    private static final UUID KEY = UUID.fromString("3f1c2a9e-7b4d-4c1a-9e2f-5a6b7c8d9e01");
    private static final String SOURCE = "10001";
    private static final String TARGET = "10002";
    private static final BigDecimal AMOUNT = new BigDecimal("250.50");

    private Transfer transfer;

    @BeforeEach
    void setUp() {
        transfer = new Transfer(KEY, SOURCE, TARGET, AMOUNT);
    }

    @Nested
    class Creation {

        @Test
        void shouldCreatePendingTransfer() {
            assertThat(transfer.getIdempotencyKey()).isEqualTo(KEY);
            assertThat(transfer.getSourceAccountNumber()).isEqualTo(SOURCE);
            assertThat(transfer.getTargetAccountNumber()).isEqualTo(TARGET);
            assertThat(transfer.getAmount()).isEqualByComparingTo("250.50");
            assertThat(transfer.getStatus()).isEqualTo(TransferStatus.PENDING);
            assertThat(transfer.getFailureReason()).isNull();
        }

        @Test
        void shouldLeaveIdAndVersionNullUntilPersisted() {
            assertThat(transfer.getId()).isNull();
            assertThat(transfer.getVersion()).isNull();
        }

        @Test
        void shouldRejectNullIdempotencyKey() {
            assertThatThrownBy(() -> new Transfer(null, SOURCE, TARGET, AMOUNT))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("idempotencyKey must not be null");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = { "   " })
        void shouldRejectBlankSourceAccountNumber(String source) {
            assertThatThrownBy(() -> new Transfer(KEY, source, TARGET, AMOUNT))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("sourceAccountNumber must not be blank");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = { "   " })
        void shouldRejectBlankTargetAccountNumber(String target) {
            assertThatThrownBy(() -> new Transfer(KEY, SOURCE, target, AMOUNT))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("targetAccountNumber must not be blank");
        }

        @Test
        void shouldRejectSameSourceAndTarget() {
            assertThatThrownBy(() -> new Transfer(KEY, SOURCE, SOURCE, AMOUNT))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Source and target accounts must be different");
        }
    }

    @Nested
    class Amount {

        @Test
        void shouldAcceptAmountWithTrailingZeros() {
            Transfer trailingZeros = new Transfer(KEY, SOURCE, TARGET, new BigDecimal("10.500"));

            assertThat(trailingZeros.getAmount()).isEqualByComparingTo("10.50");
        }

        @Test
        void shouldAcceptSmallestPositiveAmount() {
            Transfer smallest = new Transfer(KEY, SOURCE, TARGET, new BigDecimal("0.01"));

            assertThat(smallest.getAmount()).isEqualByComparingTo("0.01");
        }

        @Test
        void shouldRejectNullAmount() {
            assertThatThrownBy(() -> new Transfer(KEY, SOURCE, TARGET, null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Amount must be positive");
        }

        @ParameterizedTest
        @ValueSource(strings = { "0", "0.00", "-10.00" })
        void shouldRejectZeroOrNegativeAmount(String amount) {
            assertThatThrownBy(() -> new Transfer(KEY, SOURCE, TARGET, new BigDecimal(amount)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Amount must be positive");
        }

        @ParameterizedTest
        @ValueSource(strings = { "10.555", "0.001" })
        void shouldRejectAmountWithMoreThanTwoDecimalPlaces(String amount) {
            assertThatThrownBy(() -> new Transfer(KEY, SOURCE, TARGET, new BigDecimal(amount)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Amount must have at most 2 decimal places");
        }
    }

    @Nested
    class Matches {

        @Test
        void shouldMatchSameData() {
            assertThat(transfer.matches(SOURCE, TARGET, AMOUNT)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = { "250.5", "250.500" })
        void shouldMatchAmountWithDifferentScale(String amount) {
            assertThat(transfer.matches(SOURCE, TARGET, new BigDecimal(amount))).isTrue();
        }

        @Test
        void shouldNotMatchDifferentSource() {
            assertThat(transfer.matches("10003", TARGET, AMOUNT)).isFalse();
        }

        @Test
        void shouldNotMatchDifferentTarget() {
            assertThat(transfer.matches(SOURCE, "10003", AMOUNT)).isFalse();
        }

        @Test
        void shouldNotMatchDifferentAmount() {
            assertThat(transfer.matches(SOURCE, TARGET, new BigDecimal("250.51"))).isFalse();
        }

        @Test
        void shouldNotMatchSwappedAccounts() {
            assertThat(transfer.matches(TARGET, SOURCE, AMOUNT)).isFalse();
        }
    }

    @Nested
    class Claim {

        @Test
        void shouldStartUnclaimed() {
            assertThat(transfer.getClaimedUntil()).isNull();
        }

        @Test
        void shouldClaimPendingTransfer() {
            Instant until = Instant.parse("2026-10-27T12:05:00Z");

            transfer.claimUntil(until);

            assertThat(transfer.getClaimedUntil()).isEqualTo(until);
            assertThat(transfer.getStatus()).isEqualTo(TransferStatus.PENDING);
        }

        @Test
        void shouldRejectClaimingCompletedTransfer() {
            transfer.complete();

            assertThatThrownBy(() -> transfer.claimUntil(Instant.now()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Only pending transfers can be finished");
            assertThat(transfer.getClaimedUntil()).isNull();
        }

        @Test
        void shouldRejectClaimingFailedTransfer() {
            transfer.fail("Insufficient balance");

            assertThatThrownBy(() -> transfer.claimUntil(Instant.now()))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    class Complete {

        @Test
        void shouldCompletePendingTransfer() {
            transfer.complete();

            assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);
            assertThat(transfer.getFailureReason()).isNull();
        }

        @Test
        void shouldRejectCompletingCompletedTransfer() {
            transfer.complete();

            assertThatThrownBy(transfer::complete)
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Only pending transfers can be finished");
            assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        }

        @Test
        void shouldRejectCompletingFailedTransfer() {
            transfer.fail("Insufficient balance");

            assertThatThrownBy(transfer::complete)
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Only pending transfers can be finished");
            assertThat(transfer.getStatus()).isEqualTo(TransferStatus.FAILED);
        }
    }

    @Nested
    class Fail {

        @Test
        void shouldFailPendingTransferWithReason() {
            transfer.fail("Insufficient balance");

            assertThat(transfer.getStatus()).isEqualTo(TransferStatus.FAILED);
            assertThat(transfer.getFailureReason()).isEqualTo("Insufficient balance");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = { "   " })
        void shouldRejectBlankReason(String reason) {
            assertThatThrownBy(() -> transfer.fail(reason))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("reason must not be blank");
            assertThat(transfer.getStatus()).isEqualTo(TransferStatus.PENDING);
            assertThat(transfer.getFailureReason()).isNull();
        }

        @Test
        void shouldRejectFailingCompletedTransfer() {
            transfer.complete();

            assertThatThrownBy(() -> transfer.fail("Insufficient balance"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Only pending transfers can be finished");
            assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);
            assertThat(transfer.getFailureReason()).isNull();
        }

        @Test
        void shouldRejectFailingFailedTransfer() {
            transfer.fail("Insufficient balance");

            assertThatThrownBy(() -> transfer.fail("Account not found"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Only pending transfers can be finished");
            assertThat(transfer.getFailureReason()).isEqualTo("Insufficient balance");
        }
    }
}
