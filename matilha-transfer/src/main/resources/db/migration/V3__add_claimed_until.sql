ALTER TABLE transfers ADD COLUMN claimed_until TIMESTAMPTZ;

CREATE INDEX idx_transfers_pending_created
    ON transfers (created_at)
    WHERE status = 'PENDING';
