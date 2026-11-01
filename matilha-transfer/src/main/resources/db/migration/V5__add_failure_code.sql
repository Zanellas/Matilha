ALTER TABLE transfers ADD COLUMN failure_code VARCHAR(30);

UPDATE transfers SET failure_code = 'REJECTED_BY_ACCOUNT' WHERE status = 'FAILED';

ALTER TABLE transfers ADD CONSTRAINT ck_transfers_failure_code
    CHECK (failure_code IN ('INSUFFICIENT_BALANCE', 'ACCOUNT_NOT_FOUND', 'ACCOUNT_INACTIVE',
                            'REJECTED_BY_ACCOUNT', 'INVALID_REQUEST'));

ALTER TABLE transfers ADD CONSTRAINT ck_transfers_failure_code_when_failed
    CHECK ((status = 'FAILED') = (failure_code IS NOT NULL));
