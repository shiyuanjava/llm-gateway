package com.llm.gateway.ratelimit;

import org.junit.jupiter.api.Test;

import com.llm.gateway.Fixtures;
import com.llm.gateway.exception.QuotaExceededException;
import com.llm.gateway.persistence.repository.RequestLogRepository;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuotaServiceTest {

    @Test
    void cachesDbAggregateWithinTtl() {
        RequestLogRepository repo = mock(RequestLogRepository.class);
        when(repo.sumTokensByTenant(anyString())).thenReturn(100L);
        QuotaService service = new QuotaService(repo, Fixtures.properties());

        service.checkQuota("t1");
        service.checkQuota("t1");
        service.checkQuota("t1");

        // TTL 内只应查一次库
        verify(repo, times(1)).sumTokensByTenant("t1");
    }

    @Test
    void recordUsageAccumulatesLocallyAndTripsQuota() {
        RequestLogRepository repo = mock(RequestLogRepository.class);
        when(repo.sumTokensByTenant(anyString())).thenReturn(0L);
        QuotaService service = new QuotaService(repo, Fixtures.properties(60, 300, 1000L, 5, 30, 2));

        assertThatCode(() -> service.checkQuota("t1")).doesNotThrowAnyException();
        service.recordUsage("t1", 1000);
        assertThatThrownBy(() -> service.checkQuota("t1")).isInstanceOf(QuotaExceededException.class);
        verify(repo, times(1)).sumTokensByTenant("t1");
    }
}
