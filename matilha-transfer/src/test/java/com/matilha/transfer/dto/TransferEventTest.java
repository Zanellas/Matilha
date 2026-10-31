package com.matilha.transfer.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import com.matilha.transfer.entity.Transfer;

class TransferEventTest {

    private static final UUID KEY = UUID.fromString("3f1c2a9e-7b4d-4c1a-9e2f-5a6b7c8d9e01");
    private static final UUID TRANSFER_ID = UUID.fromString("8a2b4c6d-1e3f-4a5b-8c7d-9e0f1a2b3c4d");
    private static final String SOURCE = "10001";
    private static final String TARGET = "10002";

    /** Simula uma transferência já salva, com o id que o Hibernate gera. */
    private static Transfer savedTransfer(String amount) {
        Transfer transfer = new Transfer(KEY, SOURCE, TARGET, new BigDecimal(amount));
        ReflectionTestUtils.setField(transfer, "id", TRANSFER_ID);
        return transfer;
    }

    private static Transfer completed(String amount) {
        Transfer transfer = savedTransfer(amount);
        transfer.complete();
        return transfer;
    }

    @Nested
    class Completed {

        @Test
        void shouldBuildCompletedEvent() {
            TransferEvent event = TransferEvent.from(completed("250.50"));

            assertThat(event.eventType()).isEqualTo(TransferEvent.COMPLETED);
            assertThat(event.eventVersion()).isEqualTo(TransferEvent.CURRENT_VERSION);
            assertThat(event.transferId()).isEqualTo(TRANSFER_ID);
            assertThat(event.sourceAccountNumber()).isEqualTo(SOURCE);
            assertThat(event.targetAccountNumber()).isEqualTo(TARGET);
            assertThat(event.amount()).isEqualTo("250.50");
            assertThat(event.failureReason()).isNull();
        }

        @Test
        void shouldUseTheTypeNamesAcceptedByTheDatabase() {
            assertThat(TransferEvent.COMPLETED).isEqualTo("TransferCompleted");
            assertThat(TransferEvent.FAILED).isEqualTo("TransferFailed");
        }
    }

    @Nested
    class Failed {

        @Test
        void shouldBuildFailedEventWithReason() {
            Transfer transfer = savedTransfer("250.50");
            transfer.fail("Insufficient balance");

            TransferEvent event = TransferEvent.from(transfer);

            assertThat(event.eventType()).isEqualTo(TransferEvent.FAILED);
            assertThat(event.failureReason()).isEqualTo("Insufficient balance");
            assertThat(event.transferId()).isEqualTo(TRANSFER_ID);
        }
    }

    @Nested
    class Pending {

        @Test
        void shouldRejectPendingTransfer() {
            Transfer transfer = savedTransfer("250.50");

            assertThatThrownBy(() -> TransferEvent.from(transfer))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(TRANSFER_ID.toString());
        }
    }

    @Nested
    class Amount {

        @ParameterizedTest
        @CsvSource({
                "250.50, 250.50",
                "250.5, 250.5",
                "10, 10",
                "0.01, 0.01",
                "1E+3, 1000",
                "12345678901234567.89, 12345678901234567.89" })
        void shouldWriteAmountWithoutScientificNotation(String amount, String expected) {
            assertThat(TransferEvent.from(completed(amount)).amount()).isEqualTo(expected);
        }
    }

    @Nested
    class Metadata {

        @Test
        void shouldGenerateNewEventIdOnEachCall() {
            Transfer transfer = completed("250.50");

            UUID first = TransferEvent.from(transfer).eventId();
            UUID second = TransferEvent.from(transfer).eventId();

            assertThat(first).isNotNull().isNotEqualTo(second);
        }

        @Test
        void shouldUseNowAsOccurredAt() {
            Instant before = Instant.now();
            TransferEvent event = TransferEvent.from(completed("250.50"));
            Instant after = Instant.now();

            assertThat(event.occurredAt()).isBetween(before, after);
        }
    }
}
