ALTER TABLE transfers ADD COLUMN idempotency_key UUID NOT NULL;
ALTER TABLE transfers ADD CONSTRAINT uk_transfers_idempotency_key UNIQUE (idempotency_key);
