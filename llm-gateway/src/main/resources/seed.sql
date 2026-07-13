-- 种子数据：可重复执行（INSERT IGNORE，按唯一键去重）。
-- 默认路由指向 DeepSeek（与 LLM_PROVIDER/LLM_MODEL 一致），mock 作为离线兜底。

INSERT IGNORE INTO api_key (key_hash, key_prefix, tenant, roles, allowed_models) VALUES
    (SHA2('sk-demo-tenant-a', 256), 'sk-demo-tena', 'tenant-a', 'user', '*'),
    (SHA2('sk-demo-tenant-b', 256), 'sk-demo-tena', 'tenant-b', 'user', 'auto,cheap');

INSERT IGNORE INTO routing_rule (alias, primary_provider, primary_model, max_prompt_tokens, escalate_provider, escalate_model) VALUES
    ('auto',  'deepseek', 'deepseek-v4-pro', 8000, 'deepseek', 'deepseek-v4-pro'),
    ('cheap', 'mock',     'mock-small',      NULL, NULL,       NULL),
    ('smart', 'deepseek', 'deepseek-v4-pro', NULL, NULL,       NULL);

INSERT IGNORE INTO routing_fallback (rule_alias, seq, provider, model) VALUES
    ('auto',  1, 'mock',     'mock-small'),
    ('cheap', 1, 'deepseek', 'deepseek-v4-pro'),
    ('smart', 1, 'mock',     'mock-large');

INSERT IGNORE INTO model_pricing (model, input_per_1k, output_per_1k) VALUES
    ('deepseek-v4-pro', 0.00030, 0.00120),
    ('gpt-4o-mini',     0.00015, 0.00060),
    ('gpt-4o',          0.00250, 0.01000),
    ('claude-opus-4-8', 0.01500, 0.07500),
    ('mock-small',      0.00000, 0.00000),
    ('mock-large',      0.00000, 0.00000);

-- mock 系列通配定价（fail-close 下演示/测试模型经此放行）；claude 缓存单价 = 读 0.1×入、写 1.25×入
INSERT IGNORE INTO model_pricing (model, input_per_1k, output_per_1k) VALUES ('mock*', 0, 0);
UPDATE model_pricing SET cache_read_per_1k = 0.00150, cache_write_per_1k = 0.01875
WHERE model = 'claude-opus-4-8';
