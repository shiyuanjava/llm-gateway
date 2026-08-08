package com.llm.gateway.ratelimit;

import org.junit.jupiter.api.Test;

import com.llm.gateway.persistence.entity.TenantQuotaEventEntity;
import com.llm.gateway.persistence.mapper.TenantQuotaEventMapper;
import com.llm.gateway.persistence.mapper.TenantQuotaUsageMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuotaLedgerServiceTest {

    private final TenantQuotaEventMapper eventMapper = mock(TenantQuotaEventMapper.class);
    private final TenantQuotaUsageMapper usageMapper = mock(TenantQuotaUsageMapper.class);
    private final QuotaLedgerService service = new QuotaLedgerService(eventMapper, usageMapper);

    @Test
    void firstSettlementAppliesUsage() {
        when(eventMapper.insertIgnore("req-1", "tenant-a", 42)).thenReturn(1);

        assertThat(service.settle("req-1", "tenant-a", 42)).isEqualTo(QuotaLedgerService.SettlementResult.APPLIED);
        verify(usageMapper).addSettledTokens("tenant-a", 42);
    }

    @Test
    void acceptsMaximumIdentifierLengthAndZeroTokens() {
        String requestId = "r".repeat(64);
        String tenant = "t".repeat(64);
        when(eventMapper.insertIgnore(requestId, tenant, 0)).thenReturn(1);

        assertThat(service.settle(requestId, tenant, 0)).isEqualTo(QuotaLedgerService.SettlementResult.APPLIED);
        verify(usageMapper).addSettledTokens(tenant, 0);
    }

    @Test
    void duplicateSettlementDoesNotApplyUsageTwice() {
        when(eventMapper.insertIgnore("req-1", "tenant-a", 42)).thenReturn(0);
        TenantQuotaEventEntity existing = event("req-1", "tenant-a", 42L);
        when(eventMapper.selectByRequestId("req-1")).thenReturn(existing);

        assertThat(service.settle("req-1", "tenant-a", 42)).isEqualTo(QuotaLedgerService.SettlementResult.DUPLICATE);
        verify(usageMapper, never()).addSettledTokens(anyString(), anyLong());
    }

    @Test
    void conflictingTenantIsRejected() {
        when(eventMapper.insertIgnore("req-1", "tenant-b", 42)).thenReturn(0);
        when(eventMapper.selectByRequestId("req-1")).thenReturn(event("req-1", "tenant-a", 42L));

        assertThatThrownBy(() -> service.settle("req-1", "tenant-b", 42))
                .isInstanceOf(QuotaLedgerService.QuotaLedgerConflictException.class);
        verify(usageMapper, never()).addSettledTokens(anyString(), anyLong());
    }

    @Test
    void conflictingTokenCountIsRejected() {
        when(eventMapper.insertIgnore("req-1", "tenant-a", 43)).thenReturn(0);
        when(eventMapper.selectByRequestId("req-1")).thenReturn(event("req-1", "tenant-a", 42L));

        assertThatThrownBy(() -> service.settle("req-1", "tenant-a", 43))
                .isInstanceOf(QuotaLedgerService.QuotaLedgerConflictException.class);
        verify(usageMapper, never()).addSettledTokens(anyString(), anyLong());
    }

    @Test
    void missingEventAfterIgnoredInsertIsRejectedAsConflict() {
        when(eventMapper.insertIgnore("req-1", "tenant-a", 42)).thenReturn(0);
        when(eventMapper.selectByRequestId("req-1")).thenReturn(null);

        assertThatThrownBy(() -> service.settle("req-1", "tenant-a", 42))
                .isInstanceOf(QuotaLedgerService.QuotaLedgerConflictException.class);
    }

    @Test
    void rejectsInvalidSettlementArguments() {
        assertThatThrownBy(() -> service.settle(null, "tenant-a", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.settle("", "tenant-a", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.settle(" ", "tenant-a", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.settle("x".repeat(65), "tenant-a", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.settle("req", null, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.settle("req", "", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.settle("req", "\t", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.settle("req", "x".repeat(65), 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.settle("req", "tenant-a", -1)).isInstanceOf(IllegalArgumentException.class);
        verify(eventMapper, never()).insertIgnore(anyString(), anyString(), anyLong());
    }

    @Test
    void settledTokensReturnsZeroWhenUsageDoesNotExist() {
        when(usageMapper.selectSettledTokens("tenant-a")).thenReturn(null);

        assertThat(service.settledTokens("tenant-a")).isZero();
    }

    @Test
    void settledTokensReturnsPersistedAggregate() {
        when(usageMapper.selectSettledTokens("tenant-a")).thenReturn(42L);

        assertThat(service.settledTokens("tenant-a")).isEqualTo(42L);
    }

    private static TenantQuotaEventEntity event(String requestId, String tenant, long tokens) {
        TenantQuotaEventEntity event = new TenantQuotaEventEntity();
        event.setRequestId(requestId);
        event.setTenant(tenant);
        event.setTokens(tokens);
        return event;
    }
}
