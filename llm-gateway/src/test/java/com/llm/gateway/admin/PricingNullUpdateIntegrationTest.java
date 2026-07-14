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
 * Pricing PUT 全量更新语义:请求体缺省(null)的缓存单价必须写回 NULL
 * (updateById 跳过 null 字段,缓存单价一旦设置便无法清除)。
 */
@SpringBootTest(properties = {"gateway.admin.jwt-secret=" + AdminTestTokens.TEST_SECRET})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PricingNullUpdateIntegrationTest {

    private static final String MODEL = "it-nullable-model";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterAll
    void cleanup() {
        jdbcTemplate.update("DELETE FROM model_pricing WHERE model = ?", MODEL);
    }

    @Test
    void putClearsCachePricesBackToNull() throws Exception {
        // 幂等:先清历史残留再插入
        jdbcTemplate.update("DELETE FROM model_pricing WHERE model = ?", MODEL);
        jdbcTemplate.update(
                """
                INSERT INTO model_pricing (model, input_per_1k, output_per_1k, cache_read_per_1k, cache_write_per_1k)
                VALUES (?, 0.001, 0.002, 0.0005, 0.00125)
                """,
                MODEL);
        Long id = jdbcTemplate.queryForObject("SELECT id FROM model_pricing WHERE model = ?", Long.class, MODEL);

        mockMvc.perform(put("/admin/pricing/" + id)
                        .header("Authorization", "Bearer " + AdminTestTokens.issue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"" + MODEL + "\",\"inputPer1k\":0.001,\"outputPer1k\":0.002}"))
                .andExpect(status().isOk());

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT cache_read_per_1k, cache_write_per_1k FROM model_pricing WHERE id = ?", id);
        assertThat(row.get("cache_read_per_1k")).isNull();
        assertThat(row.get("cache_write_per_1k")).isNull();
    }
}
