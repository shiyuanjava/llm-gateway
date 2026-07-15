package com.llm.gateway.observability;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.llm.gateway.api.dto.Usage;
import com.llm.gateway.exception.PricingNotConfiguredException;
import com.llm.gateway.persistence.repository.PricingRecord;
import com.llm.gateway.persistence.repository.PricingRepository;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CostCalculatorTest {

    /** 内存假仓储：精确行、带缓存单价行、两条通配行（测最长前缀优先）。 */
    private final PricingRepository repository = () -> List.of(
            new PricingRecord("gpt-4o-mini", 0.00015, 0.00060, null, null),
            new PricingRecord("claude-opus-4-8", 0.015, 0.075, 0.0015, 0.01875),
            new PricingRecord("mock*", 0.0, 0.0, null, null),
            new PricingRecord("mock-d*", 0.001, 0.002, null, null));

    private final CostCalculator calculator = new CostCalculator(repository);

    @Test
    void shouldComputeCostFromPricing() {
        // gpt-4o-mini：输入 0.00015/1K，输出 0.00060/1K；各 1000 Token => 0.00075
        assertEquals(0.00075, calculator.cost("gpt-4o-mini", Usage.of(1000, 1000)), 1e-9);
    }

    @Test
    void shouldReturnZeroForUnknownModel() {
        assertEquals(0.0, calculator.cost("unknown-model", Usage.of(1000, 1000)), 1e-9);
    }

    @Test
    void shouldReturnZeroForNullUsage() {
        assertEquals(0.0, calculator.cost("gpt-4o-mini", null), 1e-9);
    }

    @Test
    void shouldPriceCacheReadSeparately() {
        // prompt=2000 内含缓存读 1000：非缓存 1000×0.015/1k + 缓存读 1000×0.0015/1k
        assertEquals(0.015 + 0.0015, calculator.cost("claude-opus-4-8", Usage.of(2000, 0, 1000, 0)), 1e-9);
    }

    @Test
    void shouldPriceCacheWriteSeparately() {
        assertEquals(0.015 + 0.01875, calculator.cost("claude-opus-4-8", Usage.of(2000, 0, 0, 1000)), 1e-9);
    }

    @Test
    void shouldFallBackToInputPriceWhenCachePriceUnset() {
        // 未配缓存单价：缓存 token 按 input 价 → 拆分与否总价一致（不会少收）
        double flat = calculator.cost("gpt-4o-mini", Usage.of(2000, 100));
        double split = calculator.cost("gpt-4o-mini", Usage.of(2000, 100, 500, 0));
        assertEquals(flat, split, 1e-9);
    }

    @Test
    void shouldResolveWildcardByLongestPrefix() {
        // mock-dirty 命中更长的 mock-d*（0.001+0.002），而非 mock*（$0）
        assertEquals(0.003, calculator.cost("mock-dirty", Usage.of(1000, 1000)), 1e-9);
        // mock-small 无精确行 → 命中 mock*（$0）
        assertEquals(0.0, calculator.cost("mock-small", Usage.of(1000, 1000)), 1e-9);
        // 精确行优先于通配
        assertEquals(0.00075, calculator.cost("gpt-4o-mini", Usage.of(1000, 1000)), 1e-9);
    }

    @Test
    void requirePricingFailsClosedForUnpricedModel() {
        assertThrows(PricingNotConfiguredException.class, () -> calculator.requirePricing("no-such-model"));
        assertDoesNotThrow(() -> calculator.requirePricing("mock-anything"));
        assertDoesNotThrow(() -> calculator.requirePricing("gpt-4o-mini"));
    }

    @Test
    void shouldHandleNullModel() {
        assertTrue(calculator.resolve(null).isEmpty());
        assertEquals(0.0, calculator.cost(null, Usage.of(1000, 1000)), 1e-9);
    }

    @Test
    void bareWildcardActsAsGlobalCatchAll() {
        // 裸 "*" 兜底行：任何未知模型命中兜底价，requirePricing 不再拦截（reload 会 WARN）
        PricingRepository withCatchAll = () -> List.of(new PricingRecord("*", 0.01, 0.02, null, null));
        CostCalculator catchAll = new CostCalculator(withCatchAll);

        assertDoesNotThrow(() -> catchAll.requirePricing("totally-unknown"));
        assertEquals(0.03, catchAll.cost("totally-unknown", Usage.of(1000, 1000)), 1e-9);
    }

    @Test
    void reloadPicksUpNewPricing() {
        List<List<PricingRecord>> holder = new ArrayList<>();
        holder.add(List.of(new PricingRecord("gpt-4o-mini", 0.00015, 0.00060, null, null)));
        CostCalculator reloadable = new CostCalculator(() -> holder.get(0));
        assertEquals(0.00075, reloadable.cost("gpt-4o-mini", Usage.of(1000, 1000)), 1e-9);

        // 改价后 reload 热生效
        holder.set(0, List.of(new PricingRecord("gpt-4o-mini", 0.001, 0.002, null, null)));
        reloadable.reload();
        assertEquals(0.003, reloadable.cost("gpt-4o-mini", Usage.of(1000, 1000)), 1e-9);
    }
}
