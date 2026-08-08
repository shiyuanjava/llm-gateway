package com.llm.gateway.ipcontrol;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import com.llm.gateway.admin.web.AdminApiException;
import com.llm.gateway.persistence.entity.IpBlockEntity;
import com.llm.gateway.persistence.entity.IpBlockRuleEntity;
import com.llm.gateway.persistence.mapper.IpBlockMapper;
import com.llm.gateway.persistence.mapper.IpBlockRuleMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IpBlockServiceTest {

    @Mock
    private IpBlockRuleMapper ruleMapper;

    @Mock
    private IpBlockMapper blockMapper;

    private TestIpBlockService service;
    private IpBlockRuleEntity rule;

    @BeforeEach
    void setUp() {
        rule = new IpBlockRuleEntity();
        rule.setId(1L);
        rule.setEnabled(true);
        rule.setWindowSeconds(60);
        rule.setMaxRequests(2);
        rule.setBlockSeconds(300);
        rule.setWhitelist("");
        lenient().when(ruleMapper.selectById(1L)).thenReturn(rule);
        lenient().when(blockMapper.selectOne(any())).thenReturn(null);
        service = new TestIpBlockService(ruleMapper, blockMapper);
    }

    @Test
    void blocksTheRequestAfterThreshold() {
        assertThat(service.evaluate("198.51.100.8").blocked()).isFalse();
        assertThat(service.evaluate("198.51.100.8").blocked()).isFalse();

        IpBlockService.BlockDecision decision = service.evaluate("198.51.100.8");

        assertThat(decision.blocked()).isTrue();
        assertThat(decision.source()).isEqualTo(IpBlockService.SOURCE_AUTO);
        assertThat(decision.blockedUntil()).isEqualTo(LocalDateTime.of(2026, 8, 7, 12, 5));
        verify(blockMapper).upsert(any());
    }

    @Test
    void whitelistBypassesCountingAndExistingRules() {
        rule.setWhitelist("10.0.0.0/8");

        for (int i = 0; i < 10; i++) {
            assertThat(service.evaluate("10.20.30.40").blocked()).isFalse();
        }

        verify(blockMapper, never()).upsert(any());
    }

    @Test
    void commaSeparatedWhitelistDoesNotDisableTheRule() {
        rule.setWhitelist("10.0.0.0/8, 192.0.2.10");

        for (int i = 0; i < 5; i++) {
            assertThat(service.evaluate("10.20.30.40").blocked()).isFalse();
            assertThat(service.evaluate("192.0.2.10").blocked()).isFalse();
        }
        assertThat(service.evaluate("198.51.100.31").blocked()).isFalse();
        assertThat(service.evaluate("198.51.100.31").blocked()).isFalse();
        assertThat(service.evaluate("198.51.100.31").blocked()).isTrue();
    }

    @Test
    void refreshedRuleStartsANewRequestWindow() {
        assertThat(service.evaluate("192.0.2.44").blocked()).isFalse();
        assertThat(service.evaluate("192.0.2.44").blocked()).isFalse();

        rule.setMaxRequests(1);
        service.getRule();

        assertThat(service.evaluate("192.0.2.44").blocked()).isFalse();
        assertThat(service.evaluate("192.0.2.44").blocked()).isTrue();
    }

    @Test
    void manualPermanentBlockIsReturnedFromLocalCache() {
        service.manualBlock("203.0.113.9", 0L, "abuse");

        IpBlockService.BlockDecision decision = service.evaluate("203.0.113.9");

        assertThat(decision.blocked()).isTrue();
        assertThat(decision.source()).isEqualTo(IpBlockService.SOURCE_MANUAL);
        assertThat(decision.permanent()).isTrue();
    }

    @Test
    void expiredCacheDoesNotOverrideConcurrentManualBlock() {
        IpBlockEntity expired =
                block("198.51.100.20", IpBlockService.SOURCE_AUTO, service.now().minusSeconds(1));
        IpBlockEntity replacement = block(
                "198.51.100.20", IpBlockService.SOURCE_MANUAL, service.now().plusHours(1));
        when(blockMapper.selectOne(any())).thenReturn(expired, replacement);
        when(blockMapper.expireAddressIfElapsed("198.51.100.20", service.now())).thenReturn(0);

        IpBlockService.BlockDecision decision = service.evaluate("198.51.100.20");

        assertThat(decision.blocked()).isTrue();
        assertThat(decision.source()).isEqualTo(IpBlockService.SOURCE_MANUAL);
        assertThat(decision.blockedUntil()).isEqualTo(service.now().plusHours(1));
    }

    @Test
    void unblockThrowsNotFoundWhenRecordDoesNotExist() {
        when(blockMapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.unblock(999L)).isInstanceOfSatisfying(AdminApiException.class, error -> {
            assertThat(error.getMessage()).isEqualTo("IP 封禁记录不存在");
            assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND);
        });
        verify(blockMapper, never()).update(any(), any());
    }

    @Test
    void unblockThrowsNotFoundWhenConcurrentUpdateAffectsNoRows() {
        IpBlockEntity record = block(
                "198.51.100.21", IpBlockService.SOURCE_MANUAL, service.now().plusHours(1));
        record.setId(123L);
        when(blockMapper.selectById(123L)).thenReturn(record);
        when(blockMapper.update(any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.unblock(123L)).isInstanceOfSatisfying(AdminApiException.class, error -> {
            assertThat(error.getMessage()).isEqualTo("IP 封禁记录不存在");
            assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND);
        });
        verify(blockMapper).update(any(), any());
    }

    private IpBlockEntity block(String ipAddress, String source, LocalDateTime blockedUntil) {
        IpBlockEntity entity = new IpBlockEntity();
        entity.setId(1L);
        entity.setIpAddress(ipAddress);
        entity.setBlockSource(source);
        entity.setReason("test");
        entity.setBlockedAt(service.now().minusMinutes(1));
        entity.setBlockedUntil(blockedUntil);
        entity.setActive(true);
        return entity;
    }

    private static final class TestIpBlockService extends IpBlockService {

        private final AtomicLong millis = new AtomicLong();
        private LocalDateTime dateTime = LocalDateTime.of(2026, 8, 7, 12, 0);

        TestIpBlockService(IpBlockRuleMapper ruleMapper, IpBlockMapper blockMapper) {
            super(ruleMapper, blockMapper);
        }

        @Override
        protected LocalDateTime now() {
            return dateTime;
        }

        @Override
        protected long currentTimeMillis() {
            return millis.get();
        }
    }
}
