ALTER TABLE notifications ADD COLUMN owner_id VARCHAR(255);

DROP INDEX ix_notifications_account_created;

CREATE INDEX ix_notifications_owner_account_created
    ON notifications (owner_id, account_number, created_at DESC);
