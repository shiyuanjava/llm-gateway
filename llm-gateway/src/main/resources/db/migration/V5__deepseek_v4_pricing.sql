-- DeepSeek V4 pricing (USD per 1K tokens).
--
-- DeepSeek publishes prices per 1M tokens.  The gateway stores per-1K
-- prices, so the published values are divided by 1000:
--
--   flash: input miss 0.14/M, cache hit 0.0028/M, output 0.28/M
--   pro:   input miss 0.435/M, cache hit 0.003625/M, output 0.87/M
--
-- DeepSeek reports cache misses as ordinary input tokens and does not expose
-- a separately billable cache-write class.  cache_write_per_1k therefore
-- remains NULL; the calculator only uses it when cache-creation tokens exist.
-- The conditional UPDATE also corrects the earlier V2 demo price for
-- databases that already ran it, while preserving administrator overrides.

INSERT IGNORE INTO model_pricing
    (model, input_per_1k, output_per_1k, cache_read_per_1k, cache_write_per_1k)
VALUES
    ('deepseek-v4-flash', 0.000140, 0.000280, 0.000002800, NULL),
    ('deepseek-v4-pro',   0.000435, 0.000870, 0.000003625, NULL);

UPDATE model_pricing
SET input_per_1k = 0.000435,
    output_per_1k = 0.000870,
    cache_read_per_1k = 0.000003625,
    cache_write_per_1k = NULL
WHERE model = 'deepseek-v4-pro'
  AND input_per_1k = 0.000300
  AND output_per_1k = 0.001200
  AND cache_read_per_1k IS NULL
  AND cache_write_per_1k IS NULL;
