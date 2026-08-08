package com.llm.gateway.core;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.llm.gateway.api.dto.ChatCompletionRequest;
import com.llm.gateway.api.dto.ChatMessage;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GatewayServiceUnexpectedFailureTest {

    @Test
    void unexpectedNonStreamFailureIsMeteredAndAudited() {
        CacheService cacheService = mock(CacheService.class);
        MetricsRecorder metrics = mock(MetricsRecorder.class);
        RequestLogRepository requestLogs = mock(RequestLogRepository.class);
        GatewayService service = new GatewayService(
                mock(ApiKeyService.class),
                mock(RateLimiter.class),
                mock(QuotaService.class),
                mock(GuardrailEngine.class),
                cacheService,
                mock(ModelRouter.class),
                mock(ResilientExecutor.class),
                mock(ProviderRegistry.class),
                mock(CostCalculator.class),
                metrics,
                requestLogs,
                new ObjectMapper());
        when(cacheService.lookup(any())).thenThrow(new IllegalStateException("boom"));
        ChatCompletionRequest request =
                new ChatCompletionRequest("alias", List.of(ChatMessage.user("hello")), null, null, null, null, null);

        assertThatThrownBy(() -> service.complete(request, new Principal("tenant-a", List.of("user"), List.of("*"))))
                .isInstanceOf(IllegalStateException.class);

        verify(metrics).incError("internal");
        ArgumentCaptor<RequestLogRecord> record = ArgumentCaptor.forClass(RequestLogRecord.class);
        verify(requestLogs).save(record.capture());
        assertThat(record.getValue().status()).isEqualTo("error");
        assertThat(record.getValue().errorCode()).isEqualTo("internal_error");
    }
}
