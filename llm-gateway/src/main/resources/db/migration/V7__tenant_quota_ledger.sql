-- Persistent, idempotent token quota ledger.
-- The event table is the source of truth for one settlement per request_id;
-- the usage table is an atomically maintained tenant-level aggregate.

CREATE TABLE IF NOT EXISTS tenant_quota_usage (
    tenant          VARCHAR(64) NOT NULL COMMENT 'Tenant identifier',
    settled_tokens  BIGINT      NOT NULL DEFAULT 0 COMMENT 'Cumulative settled tokens',
    updated_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant),
    CONSTRAINT chk_quota_usage_non_negative CHECK (settled_tokens >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT 'Persistent tenant token quota usage';

CREATE TABLE IF NOT EXISTS tenant_quota_event (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    request_id  VARCHAR(64) NOT NULL COMMENT 'Idempotency key for a request settlement',
    tenant      VARCHAR(64) NOT NULL COMMENT 'Tenant identifier',
    tokens      BIGINT      NOT NULL COMMENT 'Tokens settled by this event',
    created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_quota_event_request (request_id),
    KEY idx_quota_event_tenant_created (tenant, created_at),
    CONSTRAINT chk_quota_event_non_negative CHECK (tokens >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT 'Idempotent per-request token quota events';
