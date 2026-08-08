package com.llm.gateway.core;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.llm.gateway.api.dto.ChatCompletionRequest;
import com.llm.gateway.api.dto.ChatCompletionResponse;
import com.llm.gateway.api.dto.ChatMessage;
import com.llm.gateway.api.dto.Usage;
import com.llm.gateway.auth.ApiKeyService;
import com.llm.gateway.auth.Principal;
import com.llm.gateway.cache.CacheService;
import com.llm.gateway.guardrail.GuardrailEngine;
import com.llm.gateway.observability.CostCalculator;
import com.llm.gateway.observability.MetricsRecorder;
import com.llm.gateway.persistence.repository.RequestLogRecord;
import com.llm.gateway.persistence.repository.RequestLogRepository;
import com.llm.gateway.provider.ProviderRegistry;
import com.llm.gateway.ratelimit.QuotaService;
import com.llm.gateway.ratelimit.RateLimiter;
import com.llm.gateway.resilience.ResilientExecutor;
import com.llm.gateway.router.ModelRouter;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 收尾记账的失败语义：配额记账是尽力而为的旁路，不得反噬已经成功的请求，
 * 也不得让同一个 request_id 落下第二行审计（配额真值源是 request_log 求和）。
 */
class GatewayServiceSettlementFailureTest {

    private final CacheService cacheService = mock(CacheService.class);
    private final QuotaService quotaService = mock(QuotaService.class);
    private final MetricsRecorder metrics = mock(MetricsRecorder.class);
    private final RequestLogRepository requestLogs = mock(RequestLogRepository.class);
    private final CostCalculator costCalculator = mock(CostCalculator.class);

    private final GatewayService service = new GatewayService(
            mock(ApiKeyService.class),
            mock(RateLimiter.class),
            quotaService,
            mock(GuardrailEngine.class),
            cacheService,
            mock(ModelRouter.class),
            mock(ResilientExecutor.class),
            mock(ProviderRegistry.class),
            costCalculator,
            metrics,
            requestLogs,
            new ObjectMapper());

    private final ChatCompletionRequest request =
            new ChatCompletionRequest("alias", List.of(ChatMessage.user("hello")), null, null, null, null, null);
    private final Principal principal = new Principal("tenant-a", List.of("user"), List.of("*"));

    @Test
    void quotaBookkeepingFailureDoesNotFailAnAlreadyServedRequest() {
        ChatCompletionResponse cached =
                ChatCompletionResponse.singleMessage("id-1", 1L, "gpt-4o", "hi", "stop", Usage.of(3, 4));
        when(cacheService.lookup(any(), anyString())).thenReturn(Optional.of(cached));
        doThrow(new IllegalStateException("mysql down")).when(quotaService).recordUsage(anyString(), anyLong());

        ChatCompletionResponse response = service.complete(request, principal);

        assertThat(response).isSameAs(cached);
        verify(metrics, times(0)).incError("internal");
    }

    @Test
    void quotaBookkeepingFailureDoesNotWriteASecondAuditRow() {
        ChatCompletionResponse cached =
                ChatCompletionResponse.singleMessage("id-1", 1L, "gpt-4o", "hi", "stop", Usage.of(3, 4));
        when(cacheService.lookup(any(), anyString())).thenReturn(Optional.of(cached));
        doThrow(new IllegalStateException("mysql down")).when(quotaService).recordUsage(anyString(), anyLong());

        service.complete(request, principal);

        verify(requestLogs, times(1)).save(any(RequestLogRecord.class));
    }
}
