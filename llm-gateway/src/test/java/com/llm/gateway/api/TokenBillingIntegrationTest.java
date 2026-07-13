package com.llm.gateway.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.llm.gateway.auth.ApiKeyService;
import com.llm.gateway.config.ConfigRefreshService;

@SpringBootTest(properties = {
        "gateway.admin.jwt-secret=test-secret-0123456789abcdef0123456789abcdef"
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TokenBillingIntegrationTest {

    private static final String TEST_KEY = "sk-it-billing-0000000000000001";
    private static final String TENANT = "it-billing";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ApiKeyService apiKeyService;
    @Autowired
    private ConfigRefreshService refreshService;

    @BeforeAll
    void setUp() {
        jdbcTemplate.update("""
                INSERT IGNORE INTO api_key (key_hash, key_prefix, tenant, roles, allowed_models, enabled)
                VALUES (SHA2(?, 256), ?, ?, 'user', '*', 1)
                """, TEST_KEY, TEST_KEY.substring(0, 12), TENANT);
        // 兜底保证通配行存在（seed 已含；幂等）
        jdbcTemplate.update(
                "INSERT IGNORE INTO model_pricing (model, input_per_1k, output_per_1k) VALUES ('mock*', 0, 0)");
        // 无定价的路由目标：别名 it-unpriced → mock 供应商的 zz-unpriced-model（不匹配任何定价行）
        jdbcTemplate.update("""
                INSERT IGNORE INTO routing_rule (alias, primary_provider, primary_model)
                VALUES ('it-unpriced', 'mock', 'zz-unpriced-model')
                """);
        refreshService.reloadAll();
        apiKeyService.reload();
    }

    @AfterAll
    void cleanup() {
        jdbcTemplate.update("DELETE FROM api_key WHERE tenant = ?", TENANT);
        jdbcTemplate.update("DELETE FROM request_log WHERE tenant = ?", TENANT);
        jdbcTemplate.update("DELETE FROM routing_rule WHERE alias = 'it-unpriced'");
        refreshService.reloadAll();
        apiKeyService.reload();
    }

    /** 每次唯一 content，避免缓存命中干扰。 */
    private String body(String model, boolean stream) {
        return "{\"model\":\"" + model + "\",\"messages\":[{\"role\":\"user\",\"content\":\"b-"
                + UUID.randomUUID() + "\"}]" + (stream ? ",\"stream\":true" : "") + "}";
    }

    @Test
    void unpricedModelRejectedBeforeUpstreamNonStream() throws Exception {
        mockMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer " + TEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("it-unpriced", false)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("pricing_not_configured"));
    }

    @Test
    void unpricedModelRejectedAsJsonEvenWhenStreaming() throws Exception {
        // fail-close 发生在首帧之前 → 懒提交保证仍是 JSON 错误而非 SSE
        mockMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer " + TEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("it-unpriced", true)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error.code").value("pricing_not_configured"));
    }

    @Test
    void rejectionIsAuditedWithErrorCode() throws Exception {
        mockMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer " + TEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("it-unpriced", false)))
                .andExpect(status().isUnprocessableEntity());
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT error_code, served_model, total_tokens FROM request_log WHERE tenant = ? AND status = 'error' ORDER BY id DESC LIMIT 1",
                TENANT);
        assertEquals("pricing_not_configured", row.get("error_code"));
        assertNull(row.get("served_model"), "拒绝发生在调上游之前，served_model 应为空");
        assertEquals(0, ((Number) row.get("total_tokens")).intValue());
    }

    @Test
    void wildcardPricedMockModelPassesAndPersistsCacheColumns() throws Exception {
        mockMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer " + TEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("mock-billing-it", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usage.total_tokens").isNumber())
                .andExpect(jsonPath("$.usage.prompt_tokens_details").doesNotExist());
        Integer cacheRead = jdbcTemplate.queryForObject(
                "SELECT cache_read_tokens FROM request_log WHERE tenant = ? AND status = 'success' ORDER BY id DESC LIMIT 1",
                Integer.class, TENANT);
        assertEquals(0, cacheRead, "mock 无缓存，缓存列应落 0 而非 NULL");
    }
}
