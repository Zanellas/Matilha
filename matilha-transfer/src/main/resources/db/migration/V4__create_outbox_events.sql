CREATE TABLE outbox_events (
    id           UUID         PRIMARY KEY,
    aggregate_id UUID         NOT NULL,
    event_type   VARCHAR(50)  NOT NULL,
    message_key  VARCHAR(50)  NOT NULL,
    payload      JSONB        NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    published_at TIMESTAMPTZ,
    attempts     INT          NOT NULL DEFAULT 0,
    last_error   VARCHAR(500),
    CONSTRAINT ck_outbox_events_event_type CHECK (event_type IN ('TransferCompleted', 'TransferFailed')),
    CONSTRAINT ck_outbox_events_attempts_non_negative CHECK (attempts >= 0),
    CONSTRAINT uk_outbox_events_aggregate_event UNIQUE (aggregate_id, event_type)
);

CREATE INDEX idx_outbox_events_unpublished
    ON outbox_events (created_at)
    WHERE published_at IS NULL;
