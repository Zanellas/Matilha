CREATE TABLE notifications (
    id             UUID PRIMARY KEY,
    event_id       UUID         NOT NULL,
    transfer_id    UUID         NOT NULL,
    account_number VARCHAR(5)   NOT NULL,
    type           VARCHAR(30)  NOT NULL,
    message        VARCHAR(255) NOT NULL,
    failure_code   VARCHAR(30),
    created_at     TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_notifications_type
        CHECK (type IN ('TRANSFER_SENT', 'TRANSFER_RECEIVED', 'TRANSFER_FAILED')),
    CONSTRAINT ck_notifications_failure_code
        CHECK (failure_code IN ('INSUFFICIENT_BALANCE', 'ACCOUNT_NOT_FOUND', 'ACCOUNT_INACTIVE',
                                'REJECTED_BY_ACCOUNT', 'INVALID_REQUEST')),
    CONSTRAINT ck_notifications_failure_code_when_failed
        CHECK ((type = 'TRANSFER_FAILED') = (failure_code IS NOT NULL))
);

CREATE INDEX ix_notifications_account_created
    ON notifications (account_number, created_at DESC);

CREATE TABLE processed_events (
    event_id     UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL
);
