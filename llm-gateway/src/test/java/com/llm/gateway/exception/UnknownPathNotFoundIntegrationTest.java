package com.llm.gateway.exception;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import com.llm.gateway.AdminTestTokens;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 未知路径的响应契约：404 + OpenAI 风格错误体（not_found），
 * 而不是被兜底 handler 吃成 500（NoResourceFoundException 不是服务端错误）。
 */
@SpringBootTest(properties = {"gateway.admin.jwt-secret=" + AdminTestTokens.TEST_SECRET})
@AutoConfigureMockMvc
class UnknownPathNotFoundIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void unknownPathReturns404WithNotFoundError() throws Exception {
        mockMvc.perform(get("/no-such-path"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.type").value("not_found"));
    }
}
