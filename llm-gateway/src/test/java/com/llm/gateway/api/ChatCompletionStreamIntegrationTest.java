package com.llm.gateway.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
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
import org.springframework.test.web.servlet.MvcResult;

import com.llm.gateway.auth.ApiKeyService;

@SpringBootTest(properties = {
        "gateway.admin.jwt-secret=test-secret-0123456789abcdef0123456789abcdef"
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChatCompletionStreamIntegrationTest {

    private static final String TEST_KEY = "sk-it-stream-0000000000000001";
    private static final String TENANT = "it-stream";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ApiKeyService apiKeyService;

    @BeforeAll
    void insertTestKey() {
        jdbcTemplate.update("""
                INSERT IGNORE INTO api_key (key_hash, key_prefix, tenant, roles, allowed_models, enabled)
                VALUES (SHA2(?, 256), ?, ?, 'user', '*', 1)
                """, TEST_KEY, TEST_KEY.substring(0, 12), TENANT);
        apiKeyService.reload();
    }

    @AfterAll
    void cleanup() {
        jdbcTemplate.update("DELETE FROM api_key WHERE tenant = ?", TENANT);
        jdbcTemplate.update("DELETE FROM request_log WHERE tenant = ?", TENANT);
        apiKeyService.reload();
    }

    /** 每次用唯一 content 保证缓存不干扰(除专测缓存的用例)。 */
    private String body(String model, String content, String extra) {
        return "{\"model\":\"" + model + "\",\"messages\":[{\"role\":\"user\",\"content\":\""
                + content + "\"}],\"stream\":true" + extra + "}";
    }

    private String postSse(String json) throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer " + TEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andReturn();
        assertTrue(result.getResponse().getContentType().startsWith("text/event-stream"),
                "应为 SSE,实际: " + result.getResponse().getContentType());
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void streamEmitsChunksAndDoneWithoutUsageByDefault() throws Exception {
        String sse = postSse(body("mock-stream-it", "hi-" + UUID.randomUUID(), ""));
        assertTrue(sse.contains("chat.completion.chunk"));
        assertTrue(sse.contains("[mock:mock-stream-it]"));
        assertTrue(sse.trim().endsWith("data: [DONE]"));
        assertFalse(sse.contains("\"prompt_tokens\""), "未要求 include_usage 不应有 usage 帧");
    }

    @Test
    void includeUsageEmitsUsageChunkBeforeDone() throws Exception {
        String sse = postSse(body("mock-stream-it", "usage-" + UUID.randomUUID(),
                ",\"stream_options\":{\"include_usage\":true}"));
        int usagePos = sse.indexOf("\"prompt_tokens\"");
        int donePos = sse.indexOf("data: [DONE]");
        assertTrue(usagePos > 0, "应有 usage 帧");
        assertTrue(usagePos < donePos, "usage 帧应在 [DONE] 之前");
    }

    @Test
    void secondIdenticalRequestReplaysFromCache() throws Exception {
        String json = body("mock-stream-it", "cache-" + UUID.randomUUID(), "");
        postSse(json);
        postSse(json);
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM request_log WHERE tenant = ? ORDER BY id DESC LIMIT 1",
                String.class, TENANT);
        assertEquals("cache_hit", status, "第二次同请求应命中缓存回放");
    }

    @Test
    void guardrailTruncatesMidStreamWithErrorFrame() throws Exception {
        // mock-dirty 模型输出含敏感词「制造炸弹」(入站消息干净,专测出站增量截断)
        String sse = postSse(body("mock-dirty-it", "clean-" + UUID.randomUUID(), ""));
        assertTrue(sse.contains("\"code\":\"content_filtered\""), "应写出截断错误帧");
        assertFalse(sse.contains("data: [DONE]"), "截断的流不应有 [DONE]");
        assertFalse(sse.contains("不应到达客户端"), "敏感词所在帧及之后内容不得写出");
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM request_log WHERE tenant = ? ORDER BY id DESC LIMIT 1",
                String.class, TENANT);
        assertEquals("guardrail_truncated", status);
    }

    @Test
    void preStreamErrorsStayJson() throws Exception {
        // 无 Authorization → 过滤器 401 JSON(流前错误不应变成 SSE)
        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("mock-stream-it", "x", "")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void nonStreamPathUnchanged() throws Exception {
        mockMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer " + TEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"mock-plain-it\",\"messages\":[{\"role\":\"user\",\"content\":\"ns-"
                                + UUID.randomUUID() + "\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.object").value("chat.completion"))
                .andExpect(jsonPath("$.choices[0].message.content").exists());
    }
}
