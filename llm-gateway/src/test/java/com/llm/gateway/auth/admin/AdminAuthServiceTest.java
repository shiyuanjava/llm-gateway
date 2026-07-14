package com.llm.gateway.auth.admin;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.llm.gateway.config.AdminAuthProperties;
import com.llm.gateway.exception.AuthenticationException;
import com.llm.gateway.persistence.entity.AdminUserEntity;
import com.llm.gateway.persistence.mapper.AdminUserMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminAuthServiceTest {

    private static final String SECRET = "unit-test-secret-0123456789abcdef012345";

    private AdminUserMapper mapper;
    private AdminAuthService service;

    @BeforeEach
    void setUp() {
        mapper = mock(AdminUserMapper.class);
        AdminUserEntity user = new AdminUserEntity();
        user.setId(1L);
        user.setUsername("admin");
        user.setPasswordHash(new BCryptPasswordEncoder().encode("secret-pass"));
        user.setEnabled(true);
        when(mapper.selectOne(any())).thenReturn(user);
        when(mapper.selectCount(any())).thenReturn(1L);
        service = new AdminAuthService(mapper, new AdminAuthProperties(SECRET, 120, "", ""), null);
    }

    @Test
    void loginIssuesVerifiableJwt() {
        long before = System.currentTimeMillis();
        AdminAuthService.LoginResult result = service.login("admin", "secret-pass", "127.0.0.1");
        Optional<AdminPrincipal> principal = service.verify(result.token());
        assertThat(principal).isPresent();
        assertThat(principal.get().username()).isEqualTo("admin");
        // TTL 120 分钟:过期时刻应落在 [before+120min-1s, now+120min+1s]
        assertThat(result.expiresAtMillis())
                .isBetween(before + 120 * 60_000L - 1_000, System.currentTimeMillis() + 120 * 60_000L + 1_000);
    }

    @Test
    void wrongPasswordRejected() {
        assertThatThrownBy(() -> service.login("admin", "wrong", "127.0.0.1"))
                .isInstanceOf(AuthenticationException.class);
    }

    @Test
    void garbageTokenRejected() {
        assertThat(service.verify("not-a-jwt")).isEmpty();
        assertThat(service.verify(null)).isEmpty();
    }

    @Test
    void lockedAfterFiveFailures() {
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> service.login("admin", "wrong", "127.0.0.1"))
                    .isInstanceOf(AuthenticationException.class);
        }
        // 第 6 次即使密码正确也被锁定
        assertThatThrownBy(() -> service.login("admin", "secret-pass", "127.0.0.1"))
                .isInstanceOf(AdminAuthService.LoginLockedException.class);
    }

    @Test
    void shortSecretFailsFast() {
        assertThatThrownBy(() -> new AdminAuthService(mapper, new AdminAuthProperties("short", 120, "", ""), null))
                .isInstanceOf(IllegalStateException.class);
    }
}
