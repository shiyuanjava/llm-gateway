-- LLM Gateway 数据库 schema（MySQL 8）。
-- 把「配置」（api_key / routing_rule / routing_fallback / model_pricing）与
-- 「记录」（request_log）从 application.yaml 迁移到数据库。
-- 每个字段均带 COMMENT 说明。

CREATE TABLE IF NOT EXISTS api_key (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键，自增',
    key_hash       CHAR(64)     NOT NULL                COMMENT 'API Key 的 SHA-256 哈希（hex），明文不落库',
    key_prefix     VARCHAR(16)  NOT NULL                COMMENT '展示用前缀，如 sk-gw-a1b2c3',
    tenant         VARCHAR(64)  NOT NULL                COMMENT '租户标识，用于限流/配额/成本归因',
    roles          VARCHAR(255) NOT NULL DEFAULT ''     COMMENT '角色列表，逗号分隔（RBAC）',
    allowed_models VARCHAR(512) NOT NULL DEFAULT '*'    COMMENT '可访问的模型/别名，逗号分隔，* 表示全部',
    enabled        TINYINT(1)   NOT NULL DEFAULT 1      COMMENT '是否启用：1 启用，0 停用',
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_key_hash (key_hash)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT 'API Key（哈希存储）与租户/角色/可用模型';

CREATE TABLE IF NOT EXISTS routing_rule (
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键，自增',
    alias             VARCHAR(64)  NOT NULL                COMMENT '逻辑模型别名（如 auto/cheap/smart），业务请求填这个',
    primary_provider  VARCHAR(64)  NOT NULL                COMMENT '首选供应商名（对应 gateway.providers 的 key）',
    primary_model     VARCHAR(128) NOT NULL                COMMENT '首选物理模型名',
    max_prompt_tokens INT          NULL                    COMMENT '触发升级到大模型的提示词 Token 阈值，可空',
    escalate_provider VARCHAR(64)  NULL                    COMMENT '超过阈值时改用的供应商，可空',
    escalate_model    VARCHAR(128) NULL                    COMMENT '超过阈值时改用的物理模型，可空',
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_alias (alias)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '路由规则（别名->首选/升级）';

CREATE TABLE IF NOT EXISTS routing_fallback (
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键，自增',
    rule_alias VARCHAR(64)  NOT NULL                COMMENT '所属路由规则的别名（关联 routing_rule.alias）',
    seq        INT          NOT NULL                COMMENT '降级顺序，从小到大依次尝试',
    provider   VARCHAR(64)  NOT NULL                COMMENT '降级目标供应商名',
    model      VARCHAR(128) NOT NULL                COMMENT '降级目标物理模型名',
    PRIMARY KEY (id),
    KEY idx_rule_alias (rule_alias)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '路由降级链';

CREATE TABLE IF NOT EXISTS model_pricing (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键，自增',
    model         VARCHAR(128) NOT NULL                COMMENT '物理模型名（支持尾部 * 通配）',
    input_per_1k  DOUBLE       NOT NULL DEFAULT 0      COMMENT '输入（prompt）每 1K Token 单价（美元）',
    output_per_1k DOUBLE       NOT NULL DEFAULT 0      COMMENT '输出（completion）每 1K Token 单价（美元）',
    cache_read_per_1k  DOUBLE   NULL     COMMENT '缓存读每 1K Token 单价（美元），NULL=未配置按 input 单价计',
    cache_write_per_1k DOUBLE   NULL     COMMENT '缓存写每 1K Token 单价（美元），NULL=未配置按 input 单价计',
    PRIMARY KEY (id),
    UNIQUE KEY uk_model (model)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '模型计费单价（每 1K Token）';

CREATE TABLE IF NOT EXISTS request_log (
    id                BIGINT         NOT NULL AUTO_INCREMENT COMMENT '主键，自增',
    request_id        VARCHAR(64)    NOT NULL               COMMENT '请求 ID（每次调用唯一，便于排障）',
    tenant            VARCHAR(64)    NOT NULL               COMMENT '租户标识',
    requested_model   VARCHAR(128)   NOT NULL               COMMENT '请求的模型/别名',
    served_model      VARCHAR(128)   NULL                   COMMENT '实际产出响应的物理模型（失败时为空）',
    prompt_tokens     INT            NOT NULL DEFAULT 0     COMMENT '输入 Token 数',
    completion_tokens INT            NOT NULL DEFAULT 0     COMMENT '输出 Token 数',
    cache_read_tokens     INT        NOT NULL DEFAULT 0 COMMENT '缓存读 Token 数（prompt_tokens 的内部拆分）',
    cache_creation_tokens INT        NOT NULL DEFAULT 0 COMMENT '缓存写 Token 数（prompt_tokens 的内部拆分）',
    total_tokens      INT            NOT NULL DEFAULT 0     COMMENT '总 Token 数（配额按租户聚合此列）',
    cost_usd          DECIMAL(12, 6) NOT NULL DEFAULT 0     COMMENT '本次调用成本（美元）',
    cache_hit         TINYINT(1)     NOT NULL DEFAULT 0     COMMENT '是否命中缓存：1 命中，0 未命中',
    status            VARCHAR(32)    NOT NULL               COMMENT '状态：success/cache_hit/error/client_aborted/guardrail_truncated',
    error_code        VARCHAR(64)    NULL                   COMMENT '错误码（失败时）',
    latency_ms        BIGINT         NOT NULL DEFAULT 0     COMMENT '端到端耗时（毫秒）',
    created_at        TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_tenant (tenant),
    KEY idx_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '请求审计与用量/成本记录';

CREATE TABLE IF NOT EXISTS admin_user (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键，自增',
    username      VARCHAR(64)  NOT NULL                COMMENT '管理员用户名',
    password_hash VARCHAR(100) NOT NULL                COMMENT 'BCrypt 密码哈希',
    enabled       TINYINT(1)   NOT NULL DEFAULT 1      COMMENT '是否启用：1 启用，0 停用',
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '管理员账号';

CREATE TABLE IF NOT EXISTS admin_audit_log (
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键，自增',
    username   VARCHAR(64)  NOT NULL                COMMENT '操作者用户名（登录失败时为尝试的用户名）',
    action     VARCHAR(32)  NOT NULL                COMMENT 'LOGIN_OK/LOGIN_FAIL/LOGIN_LOCKED/CREATE/UPDATE/DELETE/RELOAD',
    resource   VARCHAR(255) NOT NULL                COMMENT '操作资源，如 api-keys/3、auth/login',
    detail     TEXT         NULL                    COMMENT '请求体摘要（脱敏后）',
    client_ip  VARCHAR(45)  NULL                    COMMENT '来源 IP',
    status     INT          NOT NULL                COMMENT 'HTTP 响应码',
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发生时间',
    PRIMARY KEY (id),
    KEY idx_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '管理面审计日志（登录与写操作）';
