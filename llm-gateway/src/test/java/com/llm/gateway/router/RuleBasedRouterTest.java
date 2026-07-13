package com.llm.gateway.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.llm.gateway.Fixtures;
import com.llm.gateway.api.dto.ChatCompletionRequest;
import com.llm.gateway.api.dto.ChatMessage;
import com.llm.gateway.persistence.repository.RoutingRuleRecord;
import com.llm.gateway.persistence.repository.RoutingRuleRepository;
import com.llm.gateway.provider.ProviderTarget;

class RuleBasedRouterTest {

    /** 内存假仓储：返回与 seed.sql 类似的两条规则。 */
    private final RoutingRuleRepository repository = () -> List.of(
            new RoutingRuleRecord("auto",
                    new ProviderTarget("mock", "mock-small"),
                    List.of(new ProviderTarget("openai", "gpt-4o-mini")),
                    50, new ProviderTarget("anthropic", "claude-opus-4-8")),
            new RoutingRuleRecord("smart",
                    new ProviderTarget("anthropic", "claude-opus-4-8"),
                    List.of(new ProviderTarget("openai", "gpt-4o")),
                    null, null));

    private final RuleBasedRouter router = new RuleBasedRouter(repository, Fixtures.properties());

    @Test
    void shouldRouteAliasToPrimaryAndFallbacks() {
        RouteDecision decision = router.route(request("auto", "你好"));

        assertEquals(new ProviderTarget("mock", "mock-small"), decision.primary());
        assertTrue(decision.fallbacks().contains(new ProviderTarget("openai", "gpt-4o-mini")));
    }

    @Test
    void shouldEscalateToLargeModelWhenPromptExceedsThreshold() {
        // "word " 重复 120 次：BPE 下每个 " word" 约 1 token，稳定超过 50 阈值
        RouteDecision decision = router.route(request("auto", "word ".repeat(120)));

        assertEquals(new ProviderTarget("anthropic", "claude-opus-4-8"), decision.primary());
        assertTrue(decision.fallbacks().contains(new ProviderTarget("mock", "mock-small")));
    }

    @Test
    void shouldRouteDefaultAliasToEnvLlm() {
        RouteDecision decision = router.route(request("default", "hi"));

        assertEquals(new ProviderTarget("deepseek", "deepseek-v4-pro"), decision.primary());
    }

    @Test
    void shouldRoutePhysicalModelByPrefix() {
        assertEquals(new ProviderTarget("deepseek", "deepseek-v4-pro"),
                router.route(request("deepseek-v4-pro", "hi")).primary());
        assertEquals(new ProviderTarget("openai", "gpt-4o"),
                router.route(request("gpt-4o", "hi")).primary());
        assertEquals(new ProviderTarget("anthropic", "claude-haiku-4-5"),
                router.route(request("claude-haiku-4-5", "hi")).primary());
    }

    @Test
    void shouldFallBackToDefaultModelWhenUnknown() {
        assertEquals(new ProviderTarget("deepseek", "deepseek-v4-pro"),
                router.route(request("totally-unknown", "hi")).primary());
    }

    private ChatCompletionRequest request(String model, String content) {
        return new ChatCompletionRequest(model, List.of(ChatMessage.user(content)), null, null, null, null, null);
    }
}
