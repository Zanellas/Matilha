package com.matilha.transfer.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class OutboxEventTest {

    private static final UUID EVENT_ID = UUID.fromString("6f1d2c3b-4a5e-4f60-8a7b-9c0d1e2f3a4b");
    private static final UUID TRANSFER_ID = UUID.fromString("8a2b4c6d-1e3f-4a5b-8c7d-9e0f1a2b3c4d");
    private static final String TYPE = "TransferCompleted";
    private static final String KEY = "10001";
    private static final String PAYLOAD = "{\"amount\": \"250.50\"}";
    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");

    private OutboxEvent event;

    @BeforeEach
    void setUp() {
        event = new OutboxEvent(EVENT_ID, TRANSFER_ID, TYPE, KEY, PAYLOAD);
    }

    @Nested
    class Creation {

        @Test
        void shouldCreateUnpublishedEvent() {
            assertThat(event.getId()).isEqualTo(EVENT_ID);
            assertThat(event.getAggregateId()).isEqualTo(TRANSFER_ID);
            assertThat(event.getEventType()).isEqualTo(TYPE);
            assertThat(event.getMessageKey()).isEqualTo(KEY);
            assertThat(event.getPayload()).isEqualTo(PAYLOAD);
            assertThat(event.getPublishedAt()).isNull();
            assertThat(event.getAttempts()).isZero();
            assertThat(event.getLastError()).isNull();
        }

        @Test
        void shouldRejectNullId() {
            assertThatThrownBy(() -> new OutboxEvent(null, TRANSFER_ID, TYPE, KEY, PAYLOAD))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("id must not be null");
        }

        @Test
        void shouldRejectNullAggregateId() {
            assertThatThrownBy(() -> new OutboxEvent(EVENT_ID, null, TYPE, KEY, PAYLOAD))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("aggregateId must not be null");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = { " ", "\t" })
        void shouldRejectBlankEventType(String value) {
            assertThatThrownBy(() -> new OutboxEvent(EVENT_ID, TRANSFER_ID, value, KEY, PAYLOAD))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("eventType must not be blank");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = { " ", "\t" })
        void shouldRejectBlankMessageKey(String value) {
            assertThatThrownBy(() -> new OutboxEvent(EVENT_ID, TRANSFER_ID, TYPE, value, PAYLOAD))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("messageKey must not be blank");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = { " ", "\t" })
        void shouldRejectBlankPayload(String value) {
            assertThatThrownBy(() -> new OutboxEvent(EVENT_ID, TRANSFER_ID, TYPE, KEY, value))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("payload must not be blank");
        }

        @Test
        void shouldLeavePayloadOutOfToString() {
            assertThat(event.toString())
                    .contains(EVENT_ID.toString())
                    .doesNotContain("250.50");
        }
    }

    @Nested
    class MarkPublished {

        @Test
        void shouldSetPublishedAt() {
            event.markPublished(NOW);

            assertThat(event.getPublishedAt()).isEqualTo(NOW);
        }

        @Test
        void shouldRejectPublishingTwice() {
            event.markPublished(NOW);

            assertThatThrownBy(() -> event.markPublished(NOW.plusSeconds(1)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(EVENT_ID.toString());
            assertThat(event.getPublishedAt()).isEqualTo(NOW);
        }

        @Test
        void shouldRejectNullInstant() {
            assertThatThrownBy(() -> event.markPublished(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("when must not be null");
            assertThat(event.getPublishedAt()).isNull();
        }

        @Test
        void shouldKeepAttemptsFromEarlierFailures() {
            event.registerFailure("Broker unavailable");

            event.markPublished(NOW);

            assertThat(event.getAttempts()).isEqualTo(1);
            assertThat(event.getLastError()).isEqualTo("Broker unavailable");
        }
    }

    @Nested
    class RegisterFailure {

        @Test
        void shouldCountAttemptAndKeepError() {
            event.registerFailure("Broker unavailable");

            assertThat(event.getAttempts()).isEqualTo(1);
            assertThat(event.getLastError()).isEqualTo("Broker unavailable");
            assertThat(event.getPublishedAt()).isNull();
        }

        @Test
        void shouldKeepOnlyTheLastError() {
            event.registerFailure("first");
            event.registerFailure("second");
            event.registerFailure("third");

            assertThat(event.getAttempts()).isEqualTo(3);
            assertThat(event.getLastError()).isEqualTo("third");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = { " " })
        void shouldUseDefaultMessageWhenErrorIsBlank(String error) {
            event.registerFailure(error);

            assertThat(event.getAttempts()).isEqualTo(1);
            assertThat(event.getLastError()).isEqualTo("Unknown error");
        }

        @Test
        void shouldKeepErrorWithExactly500Characters() {
            String error = "x".repeat(500);

            event.registerFailure(error);

            assertThat(event.getLastError()).isEqualTo(error);
        }

        @Test
        void shouldTruncateErrorLongerThan500Characters() {
            event.registerFailure("a".repeat(500) + "b".repeat(100));

            assertThat(event.getLastError()).hasSize(500).isEqualTo("a".repeat(500));
        }
    }

    @Nested
    class IsNew {

        @Test
        void shouldBeNewWhenCreated() {
            assertThat(event.isNew()).isTrue();
        }

        @Test
        void shouldNotBeNewAfterPersistOrLoad() {
            event.markNotNew();

            assertThat(event.isNew()).isFalse();
        }
    }
}
