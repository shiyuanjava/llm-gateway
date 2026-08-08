package com.llm.gateway.ipcontrol;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.llm.gateway.admin.web.AdminApiException;
import com.llm.gateway.admin.web.PageResult;
import com.llm.gateway.persistence.entity.IpBlockEntity;
import com.llm.gateway.persistence.entity.IpBlockRuleEntity;
import com.llm.gateway.persistence.mapper.IpBlockMapper;
import com.llm.gateway.persistence.mapper.IpBlockRuleMapper;

/**
 * IP 访问控制核心服务：读取持久化规则、统计固定窗口请求数，并维护自动/手动封禁。
 *
 * <p>请求计数器和封禁查询缓存均有容量与过期上限。数据库故障时自动检测链路 fail-open，避免访问控制组件拖垮网关；
 * 已在本机触发的封禁仍会写入本地缓存并立即生效。
 */
@Service
public class IpBlockService {

    public static final String SOURCE_AUTO = "AUTO";
    public static final String SOURCE_MANUAL = "MANUAL";

    private static final Logger log = LoggerFactory.getLogger(IpBlockService.class);
    private static final long RULE_ID = 1L;
    private static final long MAX_TRACKED_IPS = 200_000;
    private static final long RULE_CACHE_NANOS = Duration.ofSeconds(5).toNanos();
    private static final int MAX_WINDOW_SECONDS = 86_400;
    private static final int MAX_BLOCK_SECONDS = 31_536_000;
    private static final int MAX_REQUESTS = 1_000_000;
    private static final int MAX_WHITELIST_ENTRIES = 500;
    private static final int MAX_REASON_LENGTH = 255;

    private final IpBlockRuleMapper ruleMapper;
    private final IpBlockMapper blockMapper;
    private final Cache<String, RequestWindow> requestWindows = Caffeine.newBuilder()
            .maximumSize(MAX_TRACKED_IPS)
            .expireAfterAccess(Duration.ofDays(2))
            .build();
    private final Cache<String, CachedBlock> blockCache = Caffeine.newBuilder()
            .maximumSize(MAX_TRACKED_IPS)
            .expireAfterWrite(Duration.ofSeconds(5))
            .build();

    private final Object ruleLock = new Object();
    private volatile RuleSnapshot cachedRule = RuleSnapshot.disabled();
    private volatile long ruleLoadedAtNanos;
    private volatile boolean ruleLoaded;

    public IpBlockService(IpBlockRuleMapper ruleMapper, IpBlockMapper blockMapper) {
        this.ruleMapper = ruleMapper;
        this.blockMapper = blockMapper;
    }

    /** 对一次外部 API 请求做封禁检查与频率计数。 */
    public BlockDecision evaluate(String rawIpAddress) {
        String ipAddress = IpAddressMatcher.normalizeAddress(rawIpAddress);
        if (ipAddress == null) {
            return BlockDecision.allowed(null);
        }

        RuleSnapshot rule = currentRule();
        CachedBlock existing = blockCache.get(ipAddress, this::loadBlock);
        if (existing != null && existing.blocked()) {
            LocalDateTime current = now();
            if (!existing.expired(current)) {
                // 白名单只豁免自动规则；管理员明确创建的手动封禁始终优先。
                if (SOURCE_MANUAL.equals(existing.source()) || !rule.isWhitelisted(ipAddress)) {
                    return existing.toDecision(ipAddress);
                }
            } else {
                CachedBlock refreshed = expireBlock(ipAddress, current);
                blockCache.put(ipAddress, refreshed);
                if (refreshed.blocked()
                        && (SOURCE_MANUAL.equals(refreshed.source()) || !rule.isWhitelisted(ipAddress))) {
                    return refreshed.toDecision(ipAddress);
                }
            }
        }

        if (rule.isWhitelisted(ipAddress)) {
            return BlockDecision.allowed(ipAddress);
        }

        if (!rule.enabled()) {
            return BlockDecision.allowed(ipAddress);
        }

        int count = requestWindows
                .get(ipAddress, ignored -> new RequestWindow())
                .increment(currentTimeMillis(), rule.windowSeconds() * 1000L);
        if (count <= rule.maxRequests()) {
            return BlockDecision.allowed(ipAddress);
        }

        LocalDateTime blockedAt = now();
        LocalDateTime blockedUntil = rule.blockSeconds() == 0 ? null : blockedAt.plusSeconds(rule.blockSeconds());
        String reason = "窗口 " + rule.windowSeconds() + " 秒内请求达到 " + count + " 次，超过阈值 " + rule.maxRequests();
        IpBlockEntity record = newBlock(ipAddress, SOURCE_AUTO, reason, count, blockedAt, blockedUntil);
        try {
            blockMapper.upsert(record);
        } catch (RuntimeException ex) {
            log.warn("写入自动 IP 封禁记录失败，继续执行本机封禁：{}", ex.getMessage());
        }

        CachedBlock block = CachedBlock.from(record);
        blockCache.put(ipAddress, block);
        requestWindows.invalidate(ipAddress);
        return block.toDecision(ipAddress);
    }

    /** 查询当前规则；表意外缺失种子行时会补一条安全的“默认关闭”规则。 */
    public IpBlockRuleEntity getRule() {
        IpBlockRuleEntity rule = ruleMapper.selectById(RULE_ID);
        if (rule == null) {
            try {
                ruleMapper.insert(defaultRule());
            } catch (DuplicateKeyException ignored) {
                // 另一实例已经补齐种子行。
            }
            rule = ruleMapper.selectById(RULE_ID);
        }
        if (rule == null) {
            rule = defaultRule();
        }
        cacheRule(rule);
        return rule;
    }

    /** 全量更新自动封禁规则，并立即清空本机旧统计窗口。 */
    public IpBlockRuleEntity updateRule(
            Boolean enabled, Integer windowSeconds, Integer maxRequests, Integer blockSeconds, String whitelist) {
        if (enabled == null) {
            throw new IpControlValidationException("必须指定是否启用自动封禁");
        }
        requireRange("统计窗口", windowSeconds, 1, MAX_WINDOW_SECONDS);
        requireRange("请求阈值", maxRequests, 1, MAX_REQUESTS);
        requireRange("封禁时长", blockSeconds, 0, MAX_BLOCK_SECONDS);
        String normalizedWhitelist = normalizeWhitelist(whitelist);

        int updated = ruleMapper.update(
                null,
                Wrappers.<IpBlockRuleEntity>update()
                        .eq("id", RULE_ID)
                        .set("enabled", enabled)
                        .set("window_seconds", windowSeconds)
                        .set("max_requests", maxRequests)
                        .set("block_seconds", blockSeconds)
                        .set("whitelist", normalizedWhitelist));
        if (updated == 0) {
            IpBlockRuleEntity rule = new IpBlockRuleEntity();
            rule.setId(RULE_ID);
            rule.setEnabled(enabled);
            rule.setWindowSeconds(windowSeconds);
            rule.setMaxRequests(maxRequests);
            rule.setBlockSeconds(blockSeconds);
            rule.setWhitelist(normalizedWhitelist);
            try {
                ruleMapper.insert(rule);
            } catch (DuplicateKeyException ex) {
                return updateRule(enabled, windowSeconds, maxRequests, blockSeconds, normalizedWhitelist);
            }
        }

        requestWindows.invalidateAll();
        return getRule();
    }

    /** 分页查询封禁记录。 */
    public PageResult<IpBlockEntity> listBlocks(String ipAddress, String source, Boolean active, long page, long size) {
        blockMapper.expireElapsed(now());
        QueryWrapper<IpBlockEntity> query = new QueryWrapper<>();
        if (StringUtils.hasText(ipAddress)) {
            query.like("ip_address", ipAddress.trim());
        }
        if (StringUtils.hasText(source)) {
            String normalizedSource = source.trim().toUpperCase();
            if (!SOURCE_AUTO.equals(normalizedSource) && !SOURCE_MANUAL.equals(normalizedSource)) {
                throw new IpControlValidationException("封禁来源只能是 AUTO 或 MANUAL");
            }
            query.eq("block_source", normalizedSource);
        }
        if (active != null) {
            query.eq("active", active);
        }
        query.orderByDesc("active").orderByDesc("updated_at").orderByDesc("id");

        long safePage = Math.max(1, page);
        long safeSize = Math.max(1, Math.min(100, size));
        Page<IpBlockEntity> result = blockMapper.selectPage(new Page<>(safePage, safeSize), query);
        return new PageResult<>(result.getRecords(), result.getTotal(), result.getCurrent(), result.getSize());
    }

    /** 管理员手动封禁；durationSeconds=0 表示永久。 */
    public IpBlockEntity manualBlock(String rawIpAddress, Long durationSeconds, String reason) {
        String ipAddress = IpAddressMatcher.normalizeAddress(rawIpAddress);
        if (ipAddress == null) {
            throw new IpControlValidationException("请输入合法的 IPv4 或 IPv6 地址");
        }
        long duration = durationSeconds == null ? 3600L : durationSeconds;
        if (duration < 0 || duration > MAX_BLOCK_SECONDS) {
            throw new IpControlValidationException("手动封禁时长必须在 0 到 " + MAX_BLOCK_SECONDS + " 秒之间");
        }
        String normalizedReason = StringUtils.hasText(reason) ? reason.trim() : "管理员手动禁用";
        if (normalizedReason.length() > MAX_REASON_LENGTH) {
            throw new IpControlValidationException("封禁原因不能超过 " + MAX_REASON_LENGTH + " 个字符");
        }

        LocalDateTime blockedAt = now();
        LocalDateTime blockedUntil = duration == 0 ? null : blockedAt.plusSeconds(duration);
        IpBlockEntity record = newBlock(ipAddress, SOURCE_MANUAL, normalizedReason, 0, blockedAt, blockedUntil);
        blockMapper.upsert(record);
        IpBlockEntity saved = findByIp(ipAddress);
        if (saved == null) {
            saved = record;
        }
        blockCache.put(ipAddress, CachedBlock.from(saved));
        requestWindows.invalidate(ipAddress);
        return saved;
    }

    /** 手动解封，保留记录用于审计。 */
    public void unblock(long id) {
        IpBlockEntity record = blockMapper.selectById(id);
        if (record == null) {
            throw AdminApiException.notFound("IP 封禁记录不存在");
        }
        int updated = blockMapper.update(
                null,
                Wrappers.<IpBlockEntity>update()
                        .eq("id", id)
                        .set("active", false)
                        .set("updated_at", now()));
        if (updated != 1) {
            throw AdminApiException.notFound("IP 封禁记录不存在");
        }
        blockCache.invalidate(record.getIpAddress());
        requestWindows.invalidate(record.getIpAddress());
    }

    protected LocalDateTime now() {
        return LocalDateTime.now();
    }

    protected long currentTimeMillis() {
        return System.currentTimeMillis();
    }

    private RuleSnapshot currentRule() {
        long currentNanos = System.nanoTime();
        if (ruleLoaded && currentNanos - ruleLoadedAtNanos < RULE_CACHE_NANOS) {
            return cachedRule;
        }
        synchronized (ruleLock) {
            currentNanos = System.nanoTime();
            if (ruleLoaded && currentNanos - ruleLoadedAtNanos < RULE_CACHE_NANOS) {
                return cachedRule;
            }
            try {
                IpBlockRuleEntity entity = ruleMapper.selectById(RULE_ID);
                RuleSnapshot loaded = entity == null ? RuleSnapshot.disabled() : RuleSnapshot.from(entity);
                if (!loaded.equals(cachedRule)) {
                    requestWindows.invalidateAll();
                }
                cachedRule = loaded;
            } catch (RuntimeException ex) {
                log.warn("读取 IP 封禁规则失败，沿用最近一次配置：{}", ex.getMessage());
            } finally {
                ruleLoadedAtNanos = currentNanos;
                ruleLoaded = true;
            }
            return cachedRule;
        }
    }

    private void cacheRule(IpBlockRuleEntity entity) {
        synchronized (ruleLock) {
            RuleSnapshot loaded = RuleSnapshot.from(entity);
            if (!loaded.equals(cachedRule)) {
                requestWindows.invalidateAll();
            }
            cachedRule = loaded;
            ruleLoadedAtNanos = System.nanoTime();
            ruleLoaded = true;
        }
    }

    private CachedBlock loadBlock(String ipAddress) {
        try {
            IpBlockEntity entity = blockMapper.selectOne(Wrappers.<IpBlockEntity>lambdaQuery()
                    .eq(IpBlockEntity::getIpAddress, ipAddress)
                    .eq(IpBlockEntity::getActive, true)
                    .last("LIMIT 1"));
            if (entity == null) {
                return CachedBlock.none();
            }
            CachedBlock block = CachedBlock.from(entity);
            LocalDateTime current = now();
            if (block.expired(current)) {
                return expireBlock(ipAddress, current);
            }
            return block;
        } catch (RuntimeException ex) {
            log.warn("查询 IP 封禁状态失败，本次请求 fail-open：{}", ex.getMessage());
            return CachedBlock.none();
        }
    }

    private CachedBlock expireBlock(String ipAddress, LocalDateTime current) {
        try {
            int expired = blockMapper.expireAddressIfElapsed(ipAddress, current);
            if (expired == 0) {
                // 旧缓存到期与管理员重新封禁可能并发发生；重新查库，不能把新封禁当成已到期。
                IpBlockEntity active = findActiveByIp(ipAddress);
                if (active != null) {
                    CachedBlock refreshed = CachedBlock.from(active);
                    if (!refreshed.expired(current)) {
                        requestWindows.invalidate(ipAddress);
                        return refreshed;
                    }
                }
            }
        } catch (RuntimeException ex) {
            log.warn("更新到期 IP 封禁状态失败，本次请求 fail-open：{}", ex.getMessage());
        }
        requestWindows.invalidate(ipAddress);
        return CachedBlock.none();
    }

    private IpBlockEntity findByIp(String ipAddress) {
        return blockMapper.selectOne(Wrappers.<IpBlockEntity>lambdaQuery()
                .eq(IpBlockEntity::getIpAddress, ipAddress)
                .last("LIMIT 1"));
    }

    private IpBlockEntity findActiveByIp(String ipAddress) {
        return blockMapper.selectOne(Wrappers.<IpBlockEntity>lambdaQuery()
                .eq(IpBlockEntity::getIpAddress, ipAddress)
                .eq(IpBlockEntity::getActive, true)
                .last("LIMIT 1"));
    }

    private IpBlockEntity newBlock(
            String ipAddress,
            String source,
            String reason,
            int triggerCount,
            LocalDateTime blockedAt,
            LocalDateTime blockedUntil) {
        IpBlockEntity record = new IpBlockEntity();
        record.setIpAddress(ipAddress);
        record.setBlockSource(source);
        record.setReason(reason);
        record.setTriggerCount(triggerCount);
        record.setBlockedAt(blockedAt);
        record.setBlockedUntil(blockedUntil);
        record.setActive(true);
        return record;
    }

    private IpBlockRuleEntity defaultRule() {
        IpBlockRuleEntity rule = new IpBlockRuleEntity();
        rule.setId(RULE_ID);
        rule.setEnabled(false);
        rule.setWindowSeconds(60);
        rule.setMaxRequests(120);
        rule.setBlockSeconds(900);
        rule.setWhitelist("");
        return rule;
    }

    private String normalizeWhitelist(String whitelist) {
        if (!StringUtils.hasText(whitelist)) {
            return "";
        }
        Map<String, IpAddressMatcher> unique = new LinkedHashMap<>();
        for (String item : whitelist.split("[,\\r\\n]+")) {
            if (!item.isBlank()) {
                IpAddressMatcher matcher = IpAddressMatcher.parse(item);
                unique.putIfAbsent(matcher.expression(), matcher);
                if (unique.size() > MAX_WHITELIST_ENTRIES) {
                    throw new IpControlValidationException("白名单最多允许 " + MAX_WHITELIST_ENTRIES + " 条");
                }
            }
        }
        return String.join("\n", unique.keySet());
    }

    private void requireRange(String name, Integer value, int min, int max) {
        if (value == null || value < min || value > max) {
            throw new IpControlValidationException(name + "必须在 " + min + " 到 " + max + " 之间");
        }
    }

    /** 请求检查结果。 */
    public record BlockDecision(
            boolean blocked, String ipAddress, String source, String reason, LocalDateTime blockedUntil) {

        static BlockDecision allowed(String ipAddress) {
            return new BlockDecision(false, ipAddress, null, null, null);
        }

        public boolean permanent() {
            return blocked && blockedUntil == null;
        }
    }

    private record RuleSnapshot(
            boolean enabled, int windowSeconds, int maxRequests, int blockSeconds, List<IpAddressMatcher> whitelist) {

        static RuleSnapshot from(IpBlockRuleEntity entity) {
            boolean enabled = Boolean.TRUE.equals(entity.getEnabled());
            int window = entity.getWindowSeconds() == null ? 60 : Math.max(1, entity.getWindowSeconds());
            int maxRequests = entity.getMaxRequests() == null ? 120 : Math.max(1, entity.getMaxRequests());
            int blockSeconds = entity.getBlockSeconds() == null ? 900 : Math.max(0, entity.getBlockSeconds());
            List<IpAddressMatcher> whitelist = parseMatchers(entity.getWhitelist());
            return new RuleSnapshot(enabled, window, maxRequests, blockSeconds, whitelist);
        }

        static RuleSnapshot disabled() {
            return new RuleSnapshot(false, 60, 120, 900, List.of());
        }

        boolean isWhitelisted(String ipAddress) {
            return whitelist.stream().anyMatch(matcher -> matcher.matches(ipAddress));
        }

        private static List<IpAddressMatcher> parseMatchers(String whitelist) {
            if (!StringUtils.hasText(whitelist)) {
                return List.of();
            }
            return Arrays.stream(whitelist.split("[,\\r\\n]+"))
                    .filter(line -> !line.isBlank())
                    .map(IpAddressMatcher::parse)
                    .toList();
        }
    }

    private record CachedBlock(boolean blocked, String source, String reason, LocalDateTime blockedUntil) {

        static CachedBlock from(IpBlockEntity entity) {
            return new CachedBlock(true, entity.getBlockSource(), entity.getReason(), entity.getBlockedUntil());
        }

        static CachedBlock none() {
            return new CachedBlock(false, null, null, null);
        }

        boolean expired(LocalDateTime current) {
            return blocked && blockedUntil != null && !blockedUntil.isAfter(current);
        }

        BlockDecision toDecision(String ipAddress) {
            return new BlockDecision(true, ipAddress, source, reason, blockedUntil);
        }
    }

    private static final class RequestWindow {

        private long startedAtMillis = Long.MIN_VALUE;
        private int count;

        synchronized int increment(long currentMillis, long windowMillis) {
            if (startedAtMillis == Long.MIN_VALUE
                    || currentMillis < startedAtMillis
                    || currentMillis - startedAtMillis >= windowMillis) {
                startedAtMillis = currentMillis;
                count = 0;
            }
            return ++count;
        }
    }
}
