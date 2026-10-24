ALTER TABLE account_movements RENAME COLUMN transfer_id TO operation_id;

ALTER TABLE account_movements ADD COLUMN operation VARCHAR(10) NOT NULL DEFAULT 'TRANSFER';
ALTER TABLE account_movements ALTER COLUMN operation DROP DEFAULT;
ALTER TABLE account_movements
    ADD CONSTRAINT ck_account_movements_operation CHECK (operation IN ('TRANSFER', 'DEPOSIT'));

ALTER TABLE account_movements
    RENAME CONSTRAINT uk_account_movements_transfer_type TO uk_account_movements_operation_type;
