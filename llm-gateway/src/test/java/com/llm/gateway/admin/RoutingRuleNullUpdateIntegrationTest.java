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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 路由规则 PUT 全量更新语义：请求体缺省(null)的 max_prompt_tokens / escalate_* 必须写回 NULL
 * (updateById 跳过 null 字段,升级阈值一旦设置便无法清除 —— 与 Pricing 同类陷阱)。
 */
@SpringBootTest(properties = {"gateway.admin.jwt-secret=" + AdminTestTokens.TEST_SECRET})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RoutingRuleNullUpdateIntegrationTest {

    private static final String ALIAS = "it-null-rule";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterAll
    void cleanup() {
        jdbcTemplate.update("DELETE FROM routing_rule WHERE alias = ?", ALIAS);
        jdbcTemplate.update("DELETE FROM routing_fallback WHERE rule_alias = ?", ALIAS);
    }

    @Test
    void putClearsEscalateAndThresholdBackToNull() throws Exception {
        // 幂等:先清历史残留再插入
        jdbcTemplate.update("DELETE FROM routing_rule WHERE alias = ?", ALIAS);
        jdbcTemplate.update("DELETE FROM routing_fallback WHERE rule_alias = ?", ALIAS);
        jdbcTemplate.update(
                """
                INSERT INTO routing_rule
                    (alias, primary_provider, primary_model, max_prompt_tokens, escalate_provider, escalate_model)
                VALUES (?, 'mock', 'mock-small', 8000, 'mock', 'mock-large')
                """,
                ALIAS);
        Long id = jdbcTemplate.queryForObject("SELECT id FROM routing_rule WHERE alias = ?", Long.class, ALIAS);

        mockMvc.perform(put("/admin/routing-rules/" + id)
                        .header("Authorization", "Bearer " + AdminTestTokens.issue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alias\":\"" + ALIAS
                                + "\",\"primaryProvider\":\"mock\",\"primaryModel\":\"mock-small\",\"fallbacks\":[]}"))
                .andExpect(status().isOk());

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT max_prompt_tokens, escalate_provider, escalate_model FROM routing_rule WHERE id = ?", id);
        assertThat(row.get("max_prompt_tokens")).isNull();
        assertThat(row.get("escalate_provider")).isNull();
        assertThat(row.get("escalate_model")).isNull();
    }
}
