package com.llm.gateway.core;

/**
 * 单次请求的上下文，贯穿整条流水线，最终用于落一行结构化访问日志。
 *
 * <p>把每次调用的关键事实（请求 ID、租户、模型、是否命中缓存、Token、成本、耗时）收敛到一个对象，
 * 便于审计与排障——这是 LLM Gateway「集中日志/可观测」的最小落地。
 */
public class GatewayContext {

    private final String requestId;
    private final String tenant;
    private final String requestedModel;
    private final long startNanos;

    private boolean cacheHit;
    private String servedModel;
    private int totalTokens;
    private double cost;
    private boolean streamed;
    private long firstTokenMillis = -1;

    /**
     * @param requestId      请求 ID
     * @param tenant         租户
     * @param requestedModel 请求的模型/别名
     * @param startNanos     起始纳秒时间戳
     */
    public GatewayContext(String requestId, String tenant, String requestedModel, long startNanos) {
        this.requestId = requestId;
        this.tenant = tenant;
        this.requestedModel = requestedModel;
        this.startNanos = startNanos;
    }

    public String requestId() {
        return requestId;
    }

    public String tenant() {
        return tenant;
    }

    public String requestedModel() {
        return requestedModel;
    }

    public boolean cacheHit() {
        return cacheHit;
    }

    public void markCacheHit() {
        this.cacheHit = true;
    }

    public void setServedModel(String servedModel) {
        this.servedModel = servedModel;
    }

    public String servedModel() {
        return servedModel;
    }

    public void setTotalTokens(int totalTokens) {
        this.totalTokens = totalTokens;
    }

    public void setCost(double cost) {
        this.cost = cost;
    }

    /** 标记本次请求走流式路径。 */
    public void markStreamed() {
        this.streamed = true;
    }

    public boolean streamed() {
        return streamed;
    }

    /** @param firstTokenMillis 首帧写出时距请求开始的毫秒数（TTFT） */
    public void setFirstTokenMillis(long firstTokenMillis) {
        this.firstTokenMillis = firstTokenMillis;
    }

    public long firstTokenMillis() {
        return firstTokenMillis;
    }

    /**
     * @param nowNanos 当前纳秒时间戳
     * @return 从起始到现在的毫秒耗时
     */
    public long elapsedMillis(long nowNanos) {
        return (nowNanos - startNanos) / 1_000_000;
    }

    /**
     * 组装结构化访问日志文本。
     *
     * @param nowNanos 当前纳秒时间戳
     * @return 日志行
     */
    public String toLogLine(long nowNanos) {
        String base = String.format(
                "reqId=%s tenant=%s requested=%s served=%s cacheHit=%s tokens=%d cost=$%.6f elapsedMs=%d",
                requestId, tenant, requestedModel, servedModel, cacheHit, totalTokens, cost, elapsedMillis(nowNanos));
        if (streamed) {
            return base + String.format(" stream=true ttftMs=%d", firstTokenMillis);
        }
        return base;
    }
}
