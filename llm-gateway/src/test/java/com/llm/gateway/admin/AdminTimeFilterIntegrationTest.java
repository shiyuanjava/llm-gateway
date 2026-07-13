package com.llm.gateway.admin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

/**
 * 审计日志与请求日志的时间范围筛选（from/to，ISO-8601 日期时间，对 created_at 闭区间）。
 */
@SpringBootTest(properties = {
        "gateway.admin.jwt-secret=" + AdminTestTokens.TEST_SECRET
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminTimeFilterIntegrationTest {

    private static final String MARK = "it-timefilter";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    void seed() {
        // 先清理可能的历史残留（上次运行中断时 @AfterAll 未执行），保证重跑幂等
        jdbcTemplate.update("DELETE FROM admin_audit_log WHERE username = ?", MARK);
        jdbcTemplate.update("DELETE FROM request_log WHERE tenant = ?", MARK);
        jdbcTemplate.update("""
                INSERT INTO admin_audit_log (username, action, resource, status, created_at)
                VALUES (?, 'LOGIN_OK', 'auth/login', 200, '2026-01-01 10:00:00'),
                       (?, 'LOGIN_OK', 'auth/login', 200, '2026-06-01 10:00:00')
                """, MARK, MARK);
        jdbcTemplate.update("""
                INSERT INTO request_log (request_id, tenant, requested_model, status, created_at)
                VALUES ('it-tf-1', ?, 'mock-model', 'success', '2026-01-01 10:00:00'),
                       ('it-tf-2', ?, 'mock-model', 'success', '2026-06-01 10:00:00')
                """, MARK, MARK);
    }

    @AfterAll
    void cleanup() {
        jdbcTemplate.update("DELETE FROM admin_audit_log WHERE username = ?", MARK);
        jdbcTemplate.update("DELETE FROM request_log WHERE tenant = ?", MARK);
    }

    @Test
    void auditLogsFilteredByRange() throws Exception {
        mockMvc.perform(get("/admin/audit-logs")
                        .header("Authorization", "Bearer " + AdminTestTokens.issue())
                        .param("username", MARK)
                        .param("from", "2026-05-01T00:00:00")
                        .param("to", "2026-12-31T23:59:59"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void auditLogsFromOnly() throws Exception {
        mockMvc.perform(get("/admin/audit-logs")
                        .header("Authorization", "Bearer " + AdminTestTokens.issue())
                        .param("username", MARK)
                        .param("from", "2026-05-01T00:00:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void auditLogsNoRangeReturnsAll() throws Exception {
        mockMvc.perform(get("/admin/audit-logs")
                        .header("Authorization", "Bearer " + AdminTestTokens.issue())
                        .param("username", MARK))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2));
    }

    @Test
    void requestLogsFilteredByRange() throws Exception {
        mockMvc.perform(get("/admin/logs")
                        .header("Authorization", "Bearer " + AdminTestTokens.issue())
                        .param("tenant", MARK)
                        .param("from", "2026-05-01T00:00:00")
                        .param("to", "2026-12-31T23:59:59"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void requestLogsToOnly() throws Exception {
        mockMvc.perform(get("/admin/logs")
                        .header("Authorization", "Bearer " + AdminTestTokens.issue())
                        .param("tenant", MARK)
                        .param("to", "2026-01-01T10:00:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
    }
}
