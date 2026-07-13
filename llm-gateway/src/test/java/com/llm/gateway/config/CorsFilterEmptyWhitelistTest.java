package com.llm.gateway.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 白名单为空(生产同源部署默认)时的行为契约:普通请求原样走过滤器链(同源不受影响),
 * 跨域预检拿不到任何允许头(Spring 7 下为空 200 终止) —— 钉死「空白名单 = 零跨域」语义。
 */
@SpringBootTest(properties = {
        "gateway.admin.jwt-secret=test-secret-0123456789abcdef0123456789abcdef",
        "gateway.admin.allowed-origins="
})
@AutoConfigureMockMvc
class CorsFilterEmptyWhitelistTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void plainRequestPassesThroughToAuth() throws Exception {
        mockMvc.perform(get("/admin/api-keys"))
                .andExpect(status().isUnauthorized()) // 到达鉴权层 401,而非被 CORS 拦成 403
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void crossOriginPreflightGetsNoAllowHeaders() throws Exception {
        // Spring 7:白名单为空(config=null)时预检在 CorsFilter 终止 —— 空 200、不写任何允许头、
        // 不进后续过滤器链;浏览器因缺 Access-Control-Allow-Origin 判预检失败,「空白名单 = 零跨域」成立
        mockMvc.perform(options("/admin/api-keys")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
