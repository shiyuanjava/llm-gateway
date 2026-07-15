package com.llm.gateway.persistence.repository.impl;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

import com.llm.gateway.persistence.repository.RequestLogRecord;
import com.llm.gateway.persistence.repository.RequestLogRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 异步审计写入：save 只入队不阻塞，由后台线程落库；单条失败不影响其他记录；关闭时清空队列。
 */
class AsyncRequestLogWriterTest {

    /** 最多等 5s 直到条件成立，避免引入 Awaitility 依赖。 */
    private static void await(BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("等待条件超时");
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("等待被中断", e);
            }
        }
    }

    private static RequestLogRecord record(String requestId) {
        return new RequestLogRecord("req-" + requestId, "t", "m", "m", 1, 1, 2, 0, 0, 0.0, false, "success", null, 1L);
    }

    /** 记录所有 save 调用的假实现。 */
    private static final class RecordingRepository implements RequestLogRepository {
        final List<RequestLogRecord> saved = new CopyOnWriteArrayList<>();
        final AtomicInteger failuresLeft = new AtomicInteger();

        @Override
        public void save(RequestLogRecord record) {
            if (failuresLeft.getAndDecrement() > 0) {
                throw new IllegalStateException("db down");
            }
            saved.add(record);
        }

        @Override
        public long sumTokensByTenant(String tenant) {
            return 42L;
        }
    }

    @Test
    void savesAsynchronouslyAndDelegatesQueries() {
        RecordingRepository delegate = new RecordingRepository();
        AsyncRequestLogWriter writer = new AsyncRequestLogWriter(delegate);
        try {
            writer.save(record("1"));
            writer.save(record("2"));
            await(() -> delegate.saved.size() == 2);
            assertThat(writer.sumTokensByTenant("t")).isEqualTo(42L);
        } finally {
            writer.shutdown();
        }
    }

    @Test
    void singleFailureDoesNotDropOtherRecords() {
        RecordingRepository delegate = new RecordingRepository();
        delegate.failuresLeft.set(1); // 第一条落库失败
        AsyncRequestLogWriter writer = new AsyncRequestLogWriter(delegate);
        try {
            writer.save(record("bad"));
            writer.save(record("good"));
            await(() -> !delegate.saved.isEmpty());
            assertThat(delegate.saved).anyMatch(r -> r.requestId().equals("req-good"));
        } finally {
            writer.shutdown();
        }
    }

    @Test
    void shutdownFlushesQueuedRecords() {
        RecordingRepository delegate = new RecordingRepository();
        AsyncRequestLogWriter writer = new AsyncRequestLogWriter(delegate);
        for (int i = 0; i < 50; i++) {
            writer.save(record(String.valueOf(i)));
        }
        writer.shutdown();
        assertThat(delegate.saved).hasSize(50);
    }
}
