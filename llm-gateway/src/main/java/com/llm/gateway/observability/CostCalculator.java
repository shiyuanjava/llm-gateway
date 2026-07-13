package com.llm.gateway.observability;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.llm.gateway.api.dto.Usage;
import com.llm.gateway.config.ConfigReloadable;
import com.llm.gateway.exception.PricingNotConfiguredException;
import com.llm.gateway.persistence.repository.PricingRecord;
import com.llm.gateway.persistence.repository.PricingRepository;

/**
 * 成本计算器：根据模型单价与 Token 用量算出单次调用的费用（美元）。
 *
 * <p>单价从数据库（{@code model_pricing} 表）加载并缓存，可通过 {@link #reload()} 热刷新。
 * 定价解析两级：精确命中 → 最长前缀通配（模型名尾部 {@code *}）。
 * 请求前用 {@link #requirePricing} 做 fail-close 预检——不知道多少钱就不放行，
 * 杜绝「静默按 $0 计费」（参考 sub2api 的 fail-closed 定价解析）。
 */
@Component
public class CostCalculator implements ConfigReloadable {

    private static final Logger log = LoggerFactory.getLogger(CostCalculator.class);

    /** 定价快照：精确 map + 按前缀长度降序的通配列表，整体一次发布，reload 期间读方无中间态。 */
    private record PricingSnapshot(Map<String, PricingRecord> exact, List<PricingRecord> wildcards) {
    }

    private final PricingRepository pricingRepository;
    private volatile PricingSnapshot snapshot;

    /**
     * @param pricingRepository 计费仓储（数据库）
     */
    public CostCalculator(PricingRepository pricingRepository) {
        this.pricingRepository = pricingRepository;
        reload();
    }

    @Override
    public void reload() {
        Map<String, PricingRecord> exactMap = new HashMap<>();
        List<PricingRecord> wildcardList = new ArrayList<>();
        for (PricingRecord record : pricingRepository.findAll()) {
            if (record.model() != null && record.model().endsWith("*")) {
                wildcardList.add(record);
            } else {
                exactMap.put(record.model(), record);
            }
        }
        // 更长的通配前缀更具体，优先匹配（mock-d* 先于 mock*）
        wildcardList.sort(Comparator.comparingInt((PricingRecord r) -> r.model().length()).reversed());
        if (wildcardList.stream().anyMatch(r -> "*".equals(r.model()))) {
            log.warn("定价表存在裸 '*' 全局兜底行：任何模型都能解析到定价，fail-close 预检实际失效");
        }
        this.snapshot = new PricingSnapshot(Map.copyOf(exactMap), List.copyOf(wildcardList));
    }

    /**
     * 解析模型定价：精确命中优先，其次最长前缀通配。
     *
     * @param model 物理模型名
     * @return 定价记录，未命中为 empty
     */
    public Optional<PricingRecord> resolve(String model) {
        if (model == null) {
            return Optional.empty();
        }
        PricingSnapshot s = snapshot;
        PricingRecord hit = s.exact().get(model);
        if (hit != null) {
            return Optional.of(hit);
        }
        for (PricingRecord wildcard : s.wildcards()) {
            if (model.startsWith(wildcard.model().substring(0, wildcard.model().length() - 1))) {
                return Optional.of(wildcard);
            }
        }
        return Optional.empty();
    }

    /**
     * 计费 fail-close 预检：解析不到定价即拒绝（调上游之前调用）。
     *
     * @param model 物理模型名
     * @throws PricingNotConfiguredException 无定价
     */
    public void requirePricing(String model) {
        if (resolve(model).isEmpty()) {
            throw new PricingNotConfiguredException(model);
        }
    }

    /**
     * 计算单次调用成本：非缓存输入、缓存读、缓存写、输出四段分别计价。
     * 缓存单价未配置（null）时按 input 单价计——不会少收，且无缓存 token 时与旧公式完全一致。
     *
     * @param model 物理模型名
     * @param usage Token 用量（可空）
     * @return 成本（美元）；用量为空或无定价（防御，正常已被 fail-close 拦截）时返回 0
     */
    public double cost(String model, Usage usage) {
        if (usage == null) {
            return 0.0;
        }
        PricingRecord price = resolve(model).orElse(null);
        if (price == null) {
            return 0.0;
        }
        int cacheRead = usage.cacheReadTokens();
        int cacheCreation = usage.cacheCreationTokens();
        int nonCacheInput = Math.max(0, usage.promptTokens() - cacheRead - cacheCreation);
        double cacheReadPer1k = price.cacheReadPer1k() == null ? price.inputPer1k() : price.cacheReadPer1k();
        double cacheWritePer1k = price.cacheWritePer1k() == null ? price.inputPer1k() : price.cacheWritePer1k();
        return nonCacheInput / 1000.0 * price.inputPer1k()
                + cacheRead / 1000.0 * cacheReadPer1k
                + cacheCreation / 1000.0 * cacheWritePer1k
                + usage.completionTokens() / 1000.0 * price.outputPer1k();
    }
}
