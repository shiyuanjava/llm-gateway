package com.llm.gateway.admin;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.llm.gateway.AdminTestTokens;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 租户统计口径：cost=上游真实成本（缓存命中行不计），tokens=全量（配额口径），另计缓存命中次数。
 */
@SpringBootTest(properties = {"gateway.admin.jwt-secret=" + AdminTestTokens.TEST_SECRET})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LogStatsIntegrationTest {

    private static final String TENANT = "it-stats";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    void seed() {
        // 幂等：先清历史残留
        jdbcTemplate.update("DELETE FROM request_log WHERE tenant = ?", TENANT);
        jdbcTemplate.update(
                """
                INSERT INTO request_log
                    (request_id, tenant, requested_model, served_model, total_tokens, cost_usd, cache_hit, status)
                VALUES ('it-st-1', ?, 'mock-model', 'mock-model', 100, 0.5, 0, 'success'),
                       ('it-st-2', ?, 'mock-model', 'mock-model', 100, 0.3, 1, 'cache_hit')
                """,
                TENANT,
                TENANT);
    }

    @AfterAll
    void cleanup() {
        jdbcTemplate.update("DELETE FROM request_log WHERE tenant = ?", TENANT);
    }

    @Test
    void statsExcludeCacheHitCostAndCountCacheHits() throws Exception {
        mockMvc.perform(get("/admin/logs/stats").header("Authorization", "Bearer " + AdminTestTokens.issue()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.tenant=='it-stats')].requests", contains(2)))
                .andExpect(jsonPath("$.data[?(@.tenant=='it-stats')].tokens", contains(200)))
                .andExpect(jsonPath("$.data[?(@.tenant=='it-stats')].cost", contains(0.5)))
                .andExpect(jsonPath("$.data[?(@.tenant=='it-stats')].cacheHits", contains(1)));
    }
}
