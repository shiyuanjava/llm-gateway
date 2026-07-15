package com.llm.gateway.admin;

import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.llm.gateway.AdminTestTokens;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API Key PUT 契约：业务列(租户/角色/可用模型/启用)全量更新；
 * 请求体携带的 key 哈希/前缀必须无效(防篡改),且响应不回显 key 字段。
 */
@SpringBootTest(properties = {"gateway.admin.jwt-secret=" + AdminTestTokens.TEST_SECRET})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiKeyFullUpdateIntegrationTest {

    /** 64 位假哈希(CHAR(64) 且唯一键),仅本测试使用。 */
    private static final String HASH = "1f".repeat(32);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterAll
    void cleanup() {
        jdbcTemplate.update("DELETE FROM api_key WHERE key_hash = ?", HASH);
    }

    @Test
    void putUpdatesBusinessColumnsAndNeverTouchesKey() throws Exception {
        // 幂等:先清历史残留再插入
        jdbcTemplate.update("DELETE FROM api_key WHERE key_hash = ?", HASH);
        jdbcTemplate.update(
                """
                INSERT INTO api_key (key_hash, key_prefix, tenant, roles, allowed_models, enabled)
                VALUES (?, 'sk-it-akey', 'it-akey', 'user', '*', 1)
                """,
                HASH);
        Long id = jdbcTemplate.queryForObject("SELECT id FROM api_key WHERE key_hash = ?", Long.class, HASH);

        mockMvc.perform(
                        put("/admin/api-keys/" + id)
                                .header("Authorization", "Bearer " + AdminTestTokens.issue())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {"tenant":"it-akey-2","roles":"","allowedModels":"auto","enabled":false,
                                 "keyHash":"tampered","keyPrefix":"tampered"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.keyHash").doesNotExist())
                .andExpect(jsonPath("$.data.keyPrefix").doesNotExist());

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT key_hash, key_prefix, tenant, roles, allowed_models, enabled FROM api_key WHERE id = ?", id);
        assertThat(row.get("key_hash")).isEqualTo(HASH);
        assertThat(row.get("key_prefix")).isEqualTo("sk-it-akey");
        assertThat(row.get("tenant")).isEqualTo("it-akey-2");
        assertThat(row.get("roles")).isEqualTo("");
        assertThat(row.get("allowed_models")).isEqualTo("auto");
        assertThat(row.get("enabled")).isEqualTo(false);
    }
}
