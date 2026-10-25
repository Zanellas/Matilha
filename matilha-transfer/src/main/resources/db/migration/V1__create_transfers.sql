CREATE TABLE transfers (
    id                    UUID PRIMARY KEY,
    source_account_number VARCHAR(5)     NOT NULL,
    target_account_number VARCHAR(5)     NOT NULL,
    amount                NUMERIC(19, 2) NOT NULL,
    status                VARCHAR(20)    NOT NULL,
    failure_reason        VARCHAR(255),
    created_at            TIMESTAMPTZ    NOT NULL,
    updated_at            TIMESTAMPTZ,
    version               BIGINT,
    CONSTRAINT ck_transfers_status CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_transfers_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_transfers_different_accounts CHECK (source_account_number <> target_account_number)
);