package com.llm.gateway.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.llm.gateway.api.dto.ChatCompletionRequest;
import com.llm.gateway.api.dto.ChatCompletionResponse;
import com.llm.gateway.api.dto.ChatMessage;
import com.llm.gateway.api.dto.Usage;
import com.llm.gateway.auth.ApiKeyService;
import com.llm.gateway.auth.Principal;
import com.llm.gateway.cache.CacheService;
import com.llm.gateway.exception.ClientDisconnectedException;
import com.llm.gateway.guardrail.GuardrailEngine;
import com.llm.gateway.observability.CostCalculator;
import com.llm.gateway.observability.MetricsRecorder;
import com.llm.gateway.persistence.repository.RequestLogRecord;
import com.llm.gateway.persistence.repository.RequestLogRepository;
import com.llm.gateway.provider.ProviderRegistry;
import com.llm.gateway.provider.ProviderTarget;
import com.llm.gateway.ratelimit.QuotaService;
import com.llm.gateway.ratelimit.RateLimiter;
import com.llm.gateway.resilience.ResilientExecutor;
import com.llm.gateway.router.ModelRouter;
import com.llm.gateway.router.RouteDecision;

import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * 缓存回放中断(客户端断开)的审计:served_model 取缓存响应模型、cache_hit=true、
 * cost=0(回放没有上游调用,旧实现会按估算 token 误计成本且 served_model 为空)。
 */
class GatewayServiceCacheReplayTest {

    @Test
    void replayAbortPersistsCacheHitRecord() throws IOException {
        CacheService cacheService = mock(CacheService.class);
        QuotaService quotaService = mock(QuotaService.class);
        RequestLogRepository requestLogRepository = mock(RequestLogRepository.class);
        GatewayService service = new GatewayService(
                mock(ApiKeyService.class), mock(RateLimiter.class), quotaService,
                mock(GuardrailEngine.class), cacheService, mock(ModelRouter.class),
                mock(ResilientExecutor.class), mock(ProviderRegistry.class),
                mock(CostCalculator.class), mock(MetricsRecorder.class),
                requestLogRepository, new ObjectMapper());

        ChatCompletionResponse cached = new ChatCompletionResponse(
                "chatcmpl-x", "chat.completion", 1L, "mock-served-model", null, Usage.of(1, 2));
        when(cacheService.lookup(any())).thenReturn(Optional.of(cached));

        // 首帧写出即失败 → SseWriter 把 IOException 转成 ClientDisconnectedException
        HttpServletResponse servletResponse = mock(HttpServletResponse.class);
        when(servletResponse.getOutputStream()).thenThrow(new IOException("broken pipe"));

        ChatCompletionRequest request = new ChatCompletionRequest(
                "my-alias", List.of(new ChatMessage("user", "hi")), null, null, null, true, null);
        service.completeStream(request, new Principal("it-tenant", List.of("user"), List.of("*")), servletResponse);

        ArgumentCaptor<RequestLogRecord> captor = ArgumentCaptor.forClass(RequestLogRecord.class);
        verify(requestLogRepository).save(captor.capture());
        RequestLogRecord record = captor.getValue();
        assertThat(record.servedModel()).isEqualTo("mock-served-model");
        assertThat(record.cacheHit()).isTrue();
        assertThat(record.status()).isEqualTo("client_aborted");
        assertThat(record.costUsd()).isZero();
        assertThat(record.totalTokens()).isZero();
        verify(quotaService, never()).recordUsage(any(), anyLong());
    }

    /** 对照:非缓存路径的客户端断开仍走 persistPartial(估算用量 + 记配额,cache_hit=false)。 */
    @Test
    void nonCacheAbortStillPersistsPartial() {
        CacheService cacheService = mock(CacheService.class);
        QuotaService quotaService = mock(QuotaService.class);
        ModelRouter router = mock(ModelRouter.class);
        ResilientExecutor resilientExecutor = mock(ResilientExecutor.class);
        RequestLogRepository requestLogRepository = mock(RequestLogRepository.class);
        GatewayService service = new GatewayService(
                mock(ApiKeyService.class), mock(RateLimiter.class), quotaService,
                mock(GuardrailEngine.class), cacheService, router,
                resilientExecutor, mock(ProviderRegistry.class),
                mock(CostCalculator.class), mock(MetricsRecorder.class),
                requestLogRepository, new ObjectMapper());

        when(cacheService.lookup(any())).thenReturn(Optional.empty());
        // RouteDecision.chain() 恒含 primary(真实 record 无法构造空链);costCalculator 为 mock,
        // requirePricing 是 no-op,单目标真实对象即可
        when(router.route(any())).thenReturn(
                new RouteDecision(new ProviderTarget("mock", "mock-model"), List.of()));
        // 首帧未写出前上游即报客户端断开
        when(resilientExecutor.executeStream(any(), any(), any()))
                .thenThrow(new ClientDisconnectedException("客户端已断开：broken pipe", new IOException("broken pipe")));

        ChatCompletionRequest request = new ChatCompletionRequest(
                "my-alias", List.of(new ChatMessage("user", "hi")), null, null, null, true, null);
        service.completeStream(request, new Principal("it-tenant", List.of("user"), List.of("*")),
                mock(HttpServletResponse.class));

        ArgumentCaptor<RequestLogRecord> captor = ArgumentCaptor.forClass(RequestLogRecord.class);
        verify(requestLogRepository).save(captor.capture());
        RequestLogRecord record = captor.getValue();
        assertThat(record.cacheHit()).isFalse();
        assertThat(record.status()).isEqualTo("client_aborted");
        assertThat(record.servedModel()).isNull();
        verify(quotaService).recordUsage(eq("it-tenant"), anyLong());
    }
}
