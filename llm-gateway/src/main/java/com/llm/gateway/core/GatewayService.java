package com.llm.gateway.core;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import com.llm.gateway.api.dto.ChatCompletionChunk;
import com.llm.gateway.api.dto.ChatCompletionRequest;
import com.llm.gateway.api.dto.ChatCompletionResponse;
import com.llm.gateway.api.dto.Usage;
import com.llm.gateway.auth.ApiKeyService;
import com.llm.gateway.auth.Principal;
import com.llm.gateway.cache.CacheService;
import com.llm.gateway.core.streaming.SseWriter;
import com.llm.gateway.core.streaming.StreamAggregator;
import com.llm.gateway.exception.ClientDisconnectedException;
import com.llm.gateway.exception.GatewayException;
import com.llm.gateway.exception.GuardrailException;
import com.llm.gateway.guardrail.GuardrailEngine;
import com.llm.gateway.observability.CostCalculator;
import com.llm.gateway.observability.MetricsRecorder;
import com.llm.gateway.observability.TraceIdFilter;
import com.llm.gateway.persistence.repository.RequestLogRecord;
import com.llm.gateway.persistence.repository.RequestLogRepository;
import com.llm.gateway.provider.ProviderRegistry;
import com.llm.gateway.provider.ProviderTarget;
import com.llm.gateway.provider.TokenEstimator;
import com.llm.gateway.ratelimit.QuotaService;
import com.llm.gateway.ratelimit.RateLimiter;
import com.llm.gateway.resilience.ResilientExecutor;
import com.llm.gateway.router.ModelRouter;
import com.llm.gateway.router.RouteDecision;

import tools.jackson.databind.ObjectMapper;

/**
 * 网关核心编排服务：把鉴权、限流、配额、护栏、缓存、路由、容错、计费、观测按
 * <strong>确定性顺序</strong>串成一条流水线，并把每次请求的审计/用量记录写入数据库。
 *
 * <p>这条固定流程是 Harness「L3 流程层」的核心。该类只负责编排，每一步的具体逻辑都委托给
 * 对应的单一职责组件，便于独立理解、替换与测试。
 */
@Service
public class GatewayService {

    private static final Logger log = LoggerFactory.getLogger(GatewayService.class);

    private final ApiKeyService apiKeyService;
    private final RateLimiter rateLimiter;
    private final QuotaService quotaService;
    private final GuardrailEngine guardrailEngine;
    private final CacheService cacheService;
    private final ModelRouter router;
    private final ResilientExecutor resilientExecutor;
    private final ProviderRegistry providerRegistry;
    private final CostCalculator costCalculator;
    private final MetricsRecorder metrics;
    private final RequestLogRepository requestLogRepository;
    private final ObjectMapper objectMapper;

    /**
     * 注入流水线各阶段的协作组件。
     */
    public GatewayService(
            ApiKeyService apiKeyService,
            RateLimiter rateLimiter,
            QuotaService quotaService,
            GuardrailEngine guardrailEngine,
            CacheService cacheService,
            ModelRouter router,
            ResilientExecutor resilientExecutor,
            ProviderRegistry providerRegistry,
            CostCalculator costCalculator,
            MetricsRecorder metrics,
            RequestLogRepository requestLogRepository,
            ObjectMapper objectMapper) {
        this.apiKeyService = apiKeyService;
        this.rateLimiter = rateLimiter;
        this.quotaService = quotaService;
        this.guardrailEngine = guardrailEngine;
        this.cacheService = cacheService;
        this.router = router;
        this.resilientExecutor = resilientExecutor;
        this.providerRegistry = providerRegistry;
        this.costCalculator = costCalculator;
        this.metrics = metrics;
        this.requestLogRepository = requestLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 处理一次对话补全请求，执行完整的网关流水线。
     *
     * @param request   已通过 Bean 校验的统一请求
     * @param principal 鉴权得到的主体
     * @return 统一响应
     */
    public ChatCompletionResponse complete(ChatCompletionRequest request, Principal principal) {
        GatewayContext context =
                new GatewayContext(newRequestId(), principal.tenant(), request.model(), System.nanoTime());
        metrics.incInbound(); // 入站总计数:含随后被 401/429/配额拒绝的,作错误率的真实分母
        try {
            // 1. 授权：该租户能否访问目标模型
            apiKeyService.authorize(principal, request.model());
            // 2. 限流：控制请求速率
            rateLimiter.acquire(principal.tenant());
            // 3. 配额：控制 Token 总量（数据库聚合）
            quotaService.checkQuota(principal.tenant());
            // 4. 入站护栏：内容安全
            guardrailEngine.checkInput(request);
            // incRequest = 通过前置检查（授权/限流/配额/护栏）的请求；错误率分母用它时注意 401/429 类失败不在内
            metrics.incRequest(principal.tenant(), request.model());

            // 5. 缓存：命中即直接返回
            Optional<ChatCompletionResponse> cached = cacheService.lookup(request);
            if (cached.isPresent()) {
                return finish(context, cached.get(), true);
            }

            // 6. 路由：选首选 + 降级链
            RouteDecision decision = router.route(request);
            // 6.5 计费 fail-close：无定价即拒绝，请求不打上游
            requireChainPricing(decision);
            // 7. 容错执行：重试 + 熔断 + Fallback
            ChatCompletionResponse response = resilientExecutor.execute(
                    decision,
                    target -> providerRegistry.get(target.provider()).chat(request.withModel(target.model())));

            // 8. 出站护栏：回复内容安全
            guardrailEngine.checkOutput(response);
            // 9. 写缓存
            cacheService.store(request, response);
            // 10. 计费 + 指标 + 落库审计 + 访问日志
            return finish(context, response, false);
        } catch (GatewayException e) {
            metrics.incError(e.code());
            persistError(context, e);
            throw e;
        }
    }

    /**
     * 处理一次<strong>流式</strong>对话补全：前置检查与非流式一致；命中缓存则把完整响应回放成 SSE；
     * 否则逐帧「聚合（含增量护栏）→ 写出」，流完组装完整响应复用缓存与 {@link #finish} 计费落库。
     * 响应头懒提交：首帧前的失败原样上抛，仍由全局异常处理器返回 JSON 错误。
     *
     * @param request         已通过 Bean 校验的统一请求（stream=true）
     * @param principal       鉴权得到的主体
     * @param servletResponse Servlet 响应（SSE 直写，运行在虚拟线程上）
     */
    public void completeStream(
            ChatCompletionRequest request, Principal principal, HttpServletResponse servletResponse) {
        GatewayContext context =
                new GatewayContext(newRequestId(), principal.tenant(), request.model(), System.nanoTime());
        context.markStreamed();
        metrics.incInbound(); // 入站总计数:含随后被 401/429/配额拒绝的,作错误率的真实分母
        SseWriter writer = new SseWriter(servletResponse, objectMapper);
        // 可重放契约：首帧前失败会重试/换目标重新调用 invoker，聚合器必须按次尝试重建，
        // 否则重试会把上一次已累计的增量重复计入（见 StreamInvoker javadoc）
        AtomicReference<StreamAggregator> aggregatorRef = new AtomicReference<>(new StreamAggregator(guardrailEngine));
        try {
            // 1-4. 前置检查与非流式一致：授权 → 限流 → 配额 → 入站护栏
            apiKeyService.authorize(principal, request.model());
            rateLimiter.acquire(principal.tenant());
            quotaService.checkQuota(principal.tenant());
            guardrailEngine.checkInput(request);
            metrics.incRequest(principal.tenant(), request.model());
            metrics.incStreamRequest();

            // 5. 缓存：命中即把完整响应回放成 SSE
            Optional<ChatCompletionResponse> cached = cacheService.lookup(request);
            if (cached.isPresent()) {
                // 回放前先记下模型与命中标记:回放中断时审计不丢 served_model、不误计上游成本
                context.setServedModel(cached.get().model());
                context.markCacheHit();
                replay(writer, cached.get(), request.wantsUsageChunk());
                recordTtft(context, writer);
                finish(context, cached.get(), true);
                return;
            }

            // 6-7. 路由 + 流式容错执行：逐帧「聚合（含增量出站护栏）→ 写出」
            RouteDecision decision = router.route(request);
            // 计费 fail-close：发生在首帧之前，依懒提交设计仍返回 JSON 错误
            requireChainPricing(decision);
            Usage usage = resilientExecutor.executeStream(
                    decision,
                    target -> {
                        if (!writer.started()) {
                            aggregatorRef.set(new StreamAggregator(guardrailEngine)); // 每次尝试重置聚合状态
                        }
                        StreamAggregator aggregator = aggregatorRef.get();
                        return providerRegistry
                                .get(target.provider())
                                .chatStream(request.withModel(target.model()), chunk -> {
                                    aggregator.accept(chunk); // 含增量出站护栏，命中即抛，该帧不写出
                                    writer.write(chunk);
                                });
                    },
                    writer::started);
            recordTtft(context, writer);

            // 8-10. 组装完整响应 → 写缓存 → [按需] usage 帧 → [DONE] → 计费落库
            StreamAggregator aggregator = aggregatorRef.get();
            if (usage == null) {
                usage = Usage.of(
                        TokenEstimator.estimate(aggregator.model(), request.messages()),
                        TokenEstimator.estimate(aggregator.model(), aggregator.text()));
            }
            ChatCompletionResponse assembled = aggregator.buildResponse(usage);
            cacheService.store(request, assembled);
            if (request.wantsUsageChunk()) {
                writer.write(
                        ChatCompletionChunk.usageOnly(assembled.id(), assembled.created(), assembled.model(), usage));
            }
            writer.done();
            finish(context, assembled, false);
        } catch (ClientDisconnectedException e) {
            log.info("[gateway] 客户端中途断开 reqId={} tenant={}", context.requestId(), context.tenant());
            if (context.cacheHit()) {
                persistCacheReplayAborted(context);
            } else {
                persistPartial(request, context, aggregatorRef.get(), "client_aborted", null);
            }
        } catch (GuardrailException e) {
            metrics.incError(e.code());
            if (!writer.started()) {
                persistError(context, e);
                throw e; // 首帧未发出：走全局处理器返回 JSON
            }
            tryWriteError(writer, e.code(), e.getMessage());
            persistPartial(request, context, aggregatorRef.get(), "guardrail_truncated", e.code());
        } catch (GatewayException e) {
            metrics.incError(e.code());
            if (!writer.started()) {
                persistError(context, e);
                throw e;
            }
            tryWriteError(writer, e.code(), e.getMessage());
            persistPartial(request, context, aggregatorRef.get(), "error", e.code());
        } catch (RuntimeException e) {
            metrics.incError("internal");
            if (!writer.started()) {
                throw e; // 交给全局兜底
            }
            log.warn("[gateway] 流式请求内部错误 reqId={}：{}", context.requestId(), e.getMessage(), e);
            tryWriteError(writer, "internal_error", "网关内部错误");
            persistPartial(request, context, aggregatorRef.get(), "error", "internal");
        }
    }

    /** 计费 fail-close：链上任一目标无定价即拒绝，请求不打上游（参考 sub2api 的 fail-closed 定价）。 */
    private void requireChainPricing(RouteDecision decision) {
        for (ProviderTarget target : decision.chain()) {
            costCalculator.requirePricing(target.model());
        }
    }

    /** 缓存命中的 SSE 回放：首帧 → 全文内容帧 → 结束帧 → [按需] usage 帧 → [DONE]。 */
    private void replay(SseWriter writer, ChatCompletionResponse cached, boolean wantsUsage) {
        String id = cached.id();
        long created = cached.created();
        String model = cached.model();
        String finishReason = cached.choices() == null
                        || cached.choices().isEmpty()
                        || cached.choices().get(0).finishReason() == null
                ? "stop"
                : cached.choices().get(0).finishReason();
        writer.write(ChatCompletionChunk.first(id, created, model));
        writer.write(ChatCompletionChunk.content(id, created, model, cached.firstContent()));
        writer.write(ChatCompletionChunk.finish(id, created, model, finishReason));
        if (wantsUsage && cached.usage() != null) {
            writer.write(ChatCompletionChunk.usageOnly(id, created, model, cached.usage()));
        }
        writer.done();
    }

    /** 首帧已写出且尚未记录时，记录 TTFT（上下文 + 指标）。 */
    private void recordTtft(GatewayContext context, SseWriter writer) {
        if (writer.started() && context.firstTokenMillis() < 0) {
            long ttft = context.elapsedMillis(writer.firstFrameNanos()); // 首帧时刻 − 请求起始
            context.setFirstTokenMillis(ttft);
            metrics.recordTtft(ttft);
        }
    }

    /** 中途终止（断开/截断/流中失败）的落库：用估算用量尽力计费，状态区分终止原因。 */
    private void persistPartial(
            ChatCompletionRequest request,
            GatewayContext context,
            StreamAggregator aggregator,
            String status,
            String errorCode) {
        try {
            String servedModel = aggregator.model() != null ? aggregator.model() : context.servedModel();
            int promptTokens = TokenEstimator.estimate(servedModel, request.messages());
            int completionTokens = TokenEstimator.estimate(servedModel, aggregator.text());
            Usage usage = Usage.of(promptTokens, completionTokens);
            // 缓存命中路径(回放后 finish 阶段失败落到此)没有上游调用,成本恒 0;cache_hit 列透传上下文
            double cost = servedModel == null || context.cacheHit() ? 0.0 : costCalculator.cost(servedModel, usage);
            long latencyMs = context.elapsedMillis(System.nanoTime());
            requestLogRepository.save(new RequestLogRecord(
                    context.requestId(),
                    context.tenant(),
                    context.requestedModel(),
                    servedModel,
                    promptTokens,
                    completionTokens,
                    usage.totalTokens(),
                    0,
                    0,
                    cost,
                    context.cacheHit(),
                    status,
                    errorCode,
                    latencyMs));
            quotaService.recordUsage(context.tenant(), usage.totalTokens());
        } catch (RuntimeException ex) {
            log.warn("写入流式中断审计记录时出错：{}", ex.getMessage());
        }
    }

    /**
     * 缓存回放中断的落库:没有上游调用,不计成本、不记配额;模型取回放的缓存响应模型。
     * 内容可能已几乎送全仍不记配额——缓存命中中断量小,从简;限流仍兜底。
     */
    private void persistCacheReplayAborted(GatewayContext context) {
        try {
            long latencyMs = context.elapsedMillis(System.nanoTime());
            requestLogRepository.save(new RequestLogRecord(
                    context.requestId(),
                    context.tenant(),
                    context.requestedModel(),
                    context.servedModel(),
                    0,
                    0,
                    0,
                    0,
                    0,
                    0.0,
                    true,
                    "client_aborted",
                    null,
                    latencyMs));
        } catch (RuntimeException ex) {
            log.warn("写入缓存回放中断审计记录时出错：{}", ex.getMessage());
        }
    }

    /** 尽力写出错误帧：客户端可能也已断开，失败仅忽略。 */
    private void tryWriteError(SseWriter writer, String code, String message) {
        try {
            writer.writeError(code, message);
        } catch (ClientDisconnectedException ignored) {
            // 对端已消失，无需处理
        }
    }

    /**
     * 成功/缓存命中收尾：计费、指标、落库、访问日志。
     *
     * @param context  请求上下文
     * @param response 响应
     * @param cacheHit 是否缓存命中
     * @return 响应
     */
    private ChatCompletionResponse finish(GatewayContext context, ChatCompletionResponse response, boolean cacheHit) {
        Usage usage = response.usage();
        int promptTokens = usage == null ? 0 : usage.promptTokens();
        int completionTokens = usage == null ? 0 : usage.completionTokens();
        int totalTokens = usage == null ? 0 : usage.totalTokens();
        double cost = costCalculator.cost(response.model(), usage);

        context.setServedModel(response.model());
        context.setTotalTokens(totalTokens);
        context.setCost(cost);
        if (cacheHit) {
            context.markCacheHit();
            metrics.incCacheHit();
        } else {
            metrics.incTokens(response.model(), totalTokens);
            metrics.recordCost(response.model(), cost);
        }

        long now = System.nanoTime();
        long latencyMs = context.elapsedMillis(now);
        metrics.recordLatency(latencyMs);

        requestLogRepository.save(new RequestLogRecord(
                context.requestId(),
                context.tenant(),
                context.requestedModel(),
                response.model(),
                promptTokens,
                completionTokens,
                totalTokens,
                usage == null ? 0 : usage.cacheReadTokens(),
                usage == null ? 0 : usage.cacheCreationTokens(),
                cost,
                cacheHit,
                cacheHit ? "cache_hit" : "success",
                null,
                latencyMs));

        quotaService.recordUsage(context.tenant(), totalTokens);

        log.info("[gateway] {}", context.toLogLine(now));
        return response;
    }

    /**
     * 失败收尾：把错误也落一条审计记录。
     *
     * @param context 请求上下文
     * @param e       网关异常
     */
    private void persistError(GatewayContext context, GatewayException e) {
        long now = System.nanoTime();
        try {
            requestLogRepository.save(new RequestLogRecord(
                    context.requestId(),
                    context.tenant(),
                    context.requestedModel(),
                    null,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0.0,
                    false,
                    "error",
                    e.code(),
                    context.elapsedMillis(now)));
        } catch (RuntimeException ex) {
            // 落审计失败不应掩盖原始错误
            log.warn("写入失败审计记录时出错：{}", ex.getMessage());
        }
    }

    /** 复用 TraceIdFilter 写入 MDC 的 traceId 作为请求 ID(应用日志/响应头/request_log 同 ID);无过滤器场景(单测)自生成。 */
    private static String newRequestId() {
        String traceId = MDC.get(TraceIdFilter.MDC_KEY);
        return traceId != null ? traceId : TraceIdFilter.newTraceId();
    }
}
