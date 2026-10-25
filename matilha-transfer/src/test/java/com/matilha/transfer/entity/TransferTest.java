package com.matilha.transfer.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.matilha.transfer.exception.BusinessException;

class TransferTest {

    private static final String SOURCE = "10001";
    private static final String TARGET = "10002";
    private static final BigDecimal AMOUNT = new BigDecimal("250.50");

    private Transfer transfer;

    @BeforeEach
    void setUp() {
        transfer = new Transfer(SOURCE, TARGET, AMOUNT);
    }

    @Nested
    class Creation {

        @Test
        void shouldCreatePendingTransfer() {
            assertThat(transfer.getSourceAccountNumber()).isEqualTo(SOURCE);
            assertThat(transfer.getTargetAccountNumber()).isEqualTo(TARGET);
            assertThat(transfer.getAmount()).isEqualByComparingTo("250.50");
            assertThat(transfer.getStatus()).isEqualTo(TransferStatus.PENDING);
            assertThat(transfer.getFailureReason()).isNull();
        }

        @Test
        void shouldGenerateIdOnCreation() {
            assertThat(transfer.getId()).isNotNull();
        }

        @Test
        void shouldGenerateDifferentIdForEachTransfer() {
            Transfer other = new Transfer(SOURCE, TARGET, AMOUNT);

            assertThat(other.getId()).isNotEqualTo(transfer.getId());
        }

        @Test
        void shouldLeaveVersionNullSoItIsTreatedAsNew() {
            assertThat(transfer.getVersion()).isNull();
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = { "   " })
        void shouldRejectBlankSourceAccountNumber(String source) {
            assertThatThrownBy(() -> new Transfer(source, TARGET, AMOUNT))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("sourceAccountNumber must not be blank");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = { "   " })
        void shouldRejectBlankTargetAccountNumber(String target) {
            assertThatThrownBy(() -> new Transfer(SOURCE, target, AMOUNT))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("targetAccountNumber must not be blank");
        }

        @Test
        void shouldRejectSameSourceAndTarget() {
            assertThatThrownBy(() -> new Transfer(SOURCE, SOURCE, AMOUNT))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Source and target accounts must be different");
        }
    }

    @Nested
    class Amount {

        @Test
        void shouldAcceptAmountWithTrailingZeros() {
            Transfer trailingZeros = new Transfer(SOURCE, TARGET, new BigDecimal("10.500"));

            assertThat(trailingZeros.getAmount()).isEqualByComparingTo("10.50");
        }

        @Test
        void shouldAcceptSmallestPositiveAmount() {
            Transfer smallest = new Transfer(SOURCE, TARGET, new BigDecimal("0.01"));

            assertThat(smallest.getAmount()).isEqualByComparingTo("0.01");
        }

        @Test
        void shouldRejectNullAmount() {
            assertThatThrownBy(() -> new Transfer(SOURCE, TARGET, null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Amount must be positive");
        }

        @ParameterizedTest
        @ValueSource(strings = { "0", "0.00", "-10.00" })
        void shouldRejectZeroOrNegativeAmount(String amount) {
            assertThatThrownBy(() -> new Transfer(SOURCE, TARGET, new BigDecimal(amount)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Amount must be positive");
        }

        @ParameterizedTest
        @ValueSource(strings = { "10.555", "0.001" })
        void shouldRejectAmountWithMoreThanTwoDecimalPlaces(String amount) {
            assertThatThrownBy(() -> new Transfer(SOURCE, TARGET, new BigDecimal(amount)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Amount must have at most 2 decimal places");
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
