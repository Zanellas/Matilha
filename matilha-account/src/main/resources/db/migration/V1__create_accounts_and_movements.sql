CREATE TABLE accounts (
    id              UUID PRIMARY KEY,
    account_number  VARCHAR(5)     NOT NULL,
    holder_name     VARCHAR(255)   NOT NULL,
    holder_document VARCHAR(11)    NOT NULL,
    holder_email    VARCHAR(254)   NOT NULL,
    holder_phone    VARCHAR(11)    NOT NULL,
    balance         NUMERIC(19, 2) NOT NULL,
    status          VARCHAR(20)    NOT NULL,
    created_at      TIMESTAMPTZ    NOT NULL,
    updated_at      TIMESTAMPTZ,
    version         BIGINT,
    CONSTRAINT uk_accounts_account_number UNIQUE (account_number),
    CONSTRAINT ck_accounts_status CHECK (status IN ('ACTIVE', 'BLOCKED', 'CLOSED')),
    CONSTRAINT ck_accounts_balance_not_negative CHECK (balance >= 0)
);

CREATE TABLE account_movements (
    id            UUID PRIMARY KEY,
    account_id    UUID           NOT NULL,
    transfer_id   UUID           NOT NULL,
    type          VARCHAR(6)     NOT NULL,
    amount        NUMERIC(19, 2) NOT NULL,
    balance_after NUMERIC(19, 2) NOT NULL,
    created_at    TIMESTAMPTZ    NOT NULL,
    CONSTRAINT fk_account_movements_account FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT uk_account_movements_transfer_type UNIQUE (transfer_id, type),
    CONSTRAINT ck_account_movements_type CHECK (type IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_account_movements_amount_positive CHECK (amount > 0)
);

CREATE INDEX idx_account_movements_account_created ON account_movements (account_id, created_at DESC);
