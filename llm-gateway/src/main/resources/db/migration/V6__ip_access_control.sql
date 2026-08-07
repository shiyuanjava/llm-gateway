-- IP 访问控制：规则持久化 + 当前/最近一次封禁记录。
-- 自动频率统计使用有界内存窗口，只有达到阈值后才写库，避免每个请求都产生数据库写入。

CREATE TABLE IF NOT EXISTS ip_block_rule (
    id              BIGINT       NOT NULL COMMENT '固定主键，当前仅使用 id=1 的全局规则',
    enabled         TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否启用自动频率检测',
    window_seconds  INT          NOT NULL DEFAULT 60 COMMENT '统计窗口（秒）',
    max_requests    INT          NOT NULL DEFAULT 120 COMMENT '窗口内允许的最大请求数，下一次请求触发封禁',
    block_seconds   INT          NOT NULL DEFAULT 900 COMMENT '自动封禁时长（秒），0 表示永久',
    whitelist       TEXT         NULL COMMENT '白名单，每行一个 IP 或 CIDR，也兼容逗号分隔',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT 'IP 自动封禁全局规则';

INSERT IGNORE INTO ip_block_rule (id, enabled, window_seconds, max_requests, block_seconds, whitelist)
VALUES (1, 0, 60, 120, 900, '');

CREATE TABLE IF NOT EXISTS ip_block_record (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键，自增',
    ip_address      VARCHAR(45)  NOT NULL COMMENT '规范化后的 IPv4/IPv6 地址',
    block_source    VARCHAR(16)  NOT NULL COMMENT '来源：AUTO/MANUAL',
    reason          VARCHAR(255) NOT NULL COMMENT '封禁原因',
    trigger_count   INT          NOT NULL DEFAULT 0 COMMENT '自动封禁时触发窗口内的请求数',
    blocked_at      DATETIME     NOT NULL COMMENT '本次封禁开始时间',
    blocked_until   DATETIME     NULL COMMENT '解封时间，NULL 表示永久封禁',
    active          TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否仍处于封禁状态',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次创建时间',
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_ip_block_address (ip_address),
    KEY idx_ip_block_active (active, blocked_until),
    KEY idx_ip_block_updated (updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT 'IP 封禁记录（每个 IP 保留最近一次状态）';
