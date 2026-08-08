-- Request identifiers are case-sensitive idempotency keys.
ALTER TABLE tenant_quota_event
    MODIFY COLUMN request_id VARCHAR(64)
        CHARACTER SET utf8mb4
        COLLATE utf8mb4_0900_bin
        NOT NULL
        COMMENT 'Idempotency key for a request settlement';
