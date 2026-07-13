package com.llm.gateway.auth.admin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "gateway.admin.jwt-secret=test-secret-0123456789abcdef0123456789abcdef",
        "gateway.admin.bootstrap-username=it-admin",
        "gateway.admin.bootstrap-password=it-admin-pass"
})
@AutoConfigureMockMvc
class AdminJwtFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void adminEndpointsRejectAnonymous() throws Exception {
        mockMvc.perform(get("/admin/api-keys"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void loginPathIsExemptAndBadCredentialsRejected() throws Exception {
        mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nobody\",\"password\":\"nope\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validTokenPasses() throws Exception {
        String token = issueToken();
        mockMvc.perform(get("/admin/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    private String issueToken() {
        // 不依赖数据库中的账号:直接用测试密钥签发一个合法 JWT,
        // 过滤器只验签不查库,足以覆盖「合法 token → 放行 → @RequestAttribute 注入」链路。
        javax.crypto.SecretKey key = io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                "test-secret-0123456789abcdef0123456789abcdef"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        long now = System.currentTimeMillis();
        return io.jsonwebtoken.Jwts.builder()
                .subject("it-admin")
                .issuedAt(new java.util.Date(now))
                .expiration(new java.util.Date(now + 60_000))
                .signWith(key, io.jsonwebtoken.Jwts.SIG.HS256)
                .compact();
    }
}
