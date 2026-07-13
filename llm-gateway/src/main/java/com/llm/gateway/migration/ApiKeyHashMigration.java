package com.llm.gateway.migration;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.llm.gateway.auth.ApiKeyGenerator;

/**
 * 存量明文 API Key 一次性迁移：检测 {@code api_key} 表仍存在明文列时，
 * 补建哈希/前缀列 → 逐行回填 → 删除明文列。幂等：新结构库直接跳过。
 *
 * <p>单行回填失败仅记 ERROR 并跳过（该 Key 无法再认证，管理员重建），不阻断启动。
 */
@Component("apiKeyHashMigration")
public class ApiKeyHashMigration {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyHashMigration.class);

    public ApiKeyHashMigration(JdbcTemplate jdbc) {
        migrate(jdbc);
    }

    /** 执行迁移（构造器调用，便于测试直接传入 JdbcTemplate）。 */
    void migrate(JdbcTemplate jdbc) {
        if (!columnExists(jdbc, "api_key")) {
            return; // 已是新结构
        }
        log.info("检测到 api_key 表存在明文列,开始哈希迁移");
        if (!columnExists(jdbc, "key_hash")) {
            jdbc.execute("ALTER TABLE api_key ADD COLUMN key_hash CHAR(64) NULL COMMENT 'API Key 的 SHA-256 哈希（hex），明文不落库'");
        }
        if (!columnExists(jdbc, "key_prefix")) {
            jdbc.execute("ALTER TABLE api_key ADD COLUMN key_prefix VARCHAR(16) NULL COMMENT '展示用前缀，如 sk-gw-a1b2c3'");
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, api_key FROM api_key WHERE key_hash IS NULL OR key_hash = ''");
        int migrated = 0;
        for (Map<String, Object> row : rows) {
            Object id = row.get("id");
            try {
                String plain = (String) row.get("api_key");
                jdbc.update("UPDATE api_key SET key_hash = ?, key_prefix = ? WHERE id = ?",
                        ApiKeyGenerator.sha256(plain), ApiKeyGenerator.prefixOf(plain), id);
                migrated++;
            } catch (RuntimeException e) {
                log.error("api_key id={} 迁移失败,跳过(该 Key 将无法认证,请重建):{}", id, e.getMessage());
            }
        }
        // 收尾:补 NOT NULL 与唯一索引,删除明文列(含旧唯一键)
        jdbc.execute("ALTER TABLE api_key MODIFY key_hash CHAR(64) NOT NULL COMMENT 'API Key 的 SHA-256 哈希（hex），明文不落库'");
        jdbc.execute("ALTER TABLE api_key MODIFY key_prefix VARCHAR(16) NOT NULL COMMENT '展示用前缀，如 sk-gw-a1b2c3'");
        if (!indexExists(jdbc, "uk_key_hash")) {
            jdbc.execute("ALTER TABLE api_key ADD UNIQUE KEY uk_key_hash (key_hash)");
        }
        jdbc.execute("ALTER TABLE api_key DROP COLUMN api_key");
        log.info("api_key 明文迁移完成:{} 行已哈希化,明文列已删除", migrated);
    }

    /** information_schema 探测列是否存在。 */
    private boolean columnExists(JdbcTemplate jdbc, String column) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'api_key' AND COLUMN_NAME = ?",
                Integer.class, column);
        return count != null && count > 0;
    }

    /** information_schema 探测索引是否存在。 */
    private boolean indexExists(JdbcTemplate jdbc, String index) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'api_key' AND INDEX_NAME = ?",
                Integer.class, index);
        return count != null && count > 0;
    }
}
