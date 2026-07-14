package com.llm.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CORS 收敛与 401 CORS 头:CorsFilter 注册在鉴权过滤器之前,
 * 未登录 401(过滤器直写响应)也必须带 Access-Control-Allow-Origin,预检不需要登录。
 */
@SpringBootTest(
        properties = {
            "gateway.admin.jwt-secret=test-secret-0123456789abcdef0123456789abcdef",
            "gateway.admin.allowed-origins=http://localhost:5173"
        })
@AutoConfigureMockMvc
class CorsFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void unauthorized401CarriesCorsHeaders() throws Exception {
        mockMvc.perform(get("/admin/api-keys").header("Origin", "http://localhost:5173"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void preflightHandledBeforeAuth() throws Exception {
        mockMvc.perform(options("/admin/api-keys")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }

    @Test
    void disallowedOriginRejected() throws Exception {
        mockMvc.perform(get("/admin/api-keys").header("Origin", "http://evil.example"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
