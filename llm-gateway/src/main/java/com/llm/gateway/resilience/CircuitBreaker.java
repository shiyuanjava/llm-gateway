package com.llm.gateway.resilience;

/**
 * 熔断器：连续失败到阈值后「打开」，在冷却期内快速拒绝请求，冷却后进入「半开」放行一次试探。
 *
 * <p>它防止网关对一个已经故障的供应商持续打无效请求（雪崩），是 Harness「故障假设」在运行时的落地。
 * 状态机：CLOSED →(失败达阈值)→ OPEN →(冷却到期)→ HALF_OPEN →(成功)→ CLOSED / (失败)→ OPEN。
 * 所有状态变更方法均为 {@code synchronized}，保证并发安全。
 */
public class CircuitBreaker {

    /** 熔断器状态。 */
    public enum State {
        /** 关闭：正常放行。 */
        CLOSED,
        /** 打开：快速失败，不放行。 */
        OPEN,
        /** 半开：放行一次试探。 */
        HALF_OPEN
    }

    private final String name;
    private final int failureThreshold;
    private final long openMillis;

    private State state = State.CLOSED;
    private int consecutiveFailures = 0;
    private long openedAt = 0L;

    /**
     * @param name             名称（通常为供应商名，用于日志）
     * @param failureThreshold 连续失败多少次后打开
     * @param openMillis       打开后冷却毫秒数
     */
    public CircuitBreaker(String name, int failureThreshold, long openMillis) {
        this.name = name;
        this.failureThreshold = failureThreshold;
        this.openMillis = openMillis;
    }

    /**
     * 判断当前是否允许放行；处于 OPEN 且冷却到期时自动转入 HALF_OPEN 并放行。
     *
     * @return 允许放行返回 true
     */
    public synchronized boolean allowRequest() {
        if (state == State.OPEN && clock() - openedAt >= openMillis) {
            state = State.HALF_OPEN;
        }
        return state != State.OPEN;
    }

    /**
     * 记录一次成功：重置为 CLOSED。
     */
    public synchronized void onSuccess() {
        consecutiveFailures = 0;
        state = State.CLOSED;
    }

    /**
     * 记录一次失败：累计失败数，半开下立即打开，达到阈值时打开。
     */
    public synchronized void onFailure() {
        consecutiveFailures++;
        if (state == State.HALF_OPEN || consecutiveFailures >= failureThreshold) {
            state = State.OPEN;
            openedAt = clock();
        }
    }

    /** @return 当前状态（用于观测/测试） */
    public synchronized State state() {
        return state;
    }

    /** @return 名称 */
    public String name() {
        return name;
    }

    /**
     * 当前时间（毫秒）。抽成方法便于测试覆写时间。
     *
     * @return 当前毫秒时间戳
     */
    protected long clock() {
        return System.currentTimeMillis();
    }
}
