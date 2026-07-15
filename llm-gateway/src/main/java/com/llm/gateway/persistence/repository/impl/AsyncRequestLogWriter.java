package com.llm.gateway.persistence.repository.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import com.llm.gateway.persistence.repository.RequestLogRecord;
import com.llm.gateway.persistence.repository.RequestLogRepository;

/**
 * 请求日志仓储的异步装饰器：{@link #save} 只做入队（微秒级），由单个后台线程攒批落库，
 * 把审计写入从请求热路径上摘掉——MySQL 抖动不再直接抬高请求 P99。
 *
 * <p>降级语义：队列打满（DB 长时间不可用或写入速率超限）时丢弃并记本地 WARN 日志，
 * 保证主链路永不因审计而阻塞；应用关闭时尽力清空队列。查询类方法直接透传同步实现。
 */
@Component
@Primary
public class AsyncRequestLogWriter implements RequestLogRepository {

    private static final Logger log = LoggerFactory.getLogger(AsyncRequestLogWriter.class);

    private static final int QUEUE_CAPACITY = 10_000;
    private static final int BATCH_SIZE = 100;

    private final RequestLogRepository delegate;
    private final BlockingQueue<RequestLogRecord> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final Thread worker;
    private volatile boolean running = true;

    /**
     * @param delegate 同步落库实现
     */
    public AsyncRequestLogWriter(@Qualifier("requestLogRepositoryImpl") RequestLogRepository delegate) {
        this.delegate = delegate;
        this.worker = Thread.ofPlatform().name("request-log-writer").daemon(true).start(this::drainLoop);
    }

    @Override
    public void save(RequestLogRecord record) {
        if (!queue.offer(record)) {
            // 队列打满：宁可丢审计也不阻塞请求；本地日志保底可追溯
            log.warn("审计日志队列已满，降级为本地日志：{}", record);
        }
    }

    @Override
    public long sumTokensByTenant(String tenant) {
        return delegate.sumTokensByTenant(tenant);
    }

    /** 后台批量落库循环：阻塞等首条 → 攒批 → 逐条写（单条失败只影响自身）。 */
    private void drainLoop() {
        List<RequestLogRecord> batch = new ArrayList<>(BATCH_SIZE);
        while (running || !queue.isEmpty()) {
            try {
                RequestLogRecord first = queue.poll(500, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                queue.drainTo(batch, BATCH_SIZE - 1);
                for (RequestLogRecord record : batch) {
                    persistQuietly(record);
                }
                batch.clear();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // 中断退出前尽力清空剩余记录
        queue.forEach(this::persistQuietly);
        queue.clear();
    }

    /** 单条落库；失败降级为本地 WARN 日志，不影响批内其他记录。 */
    private void persistQuietly(RequestLogRecord record) {
        try {
            delegate.save(record);
        } catch (RuntimeException e) {
            log.warn("审计日志落库失败，降级为本地日志：{}，原因：{}", record, e.getMessage());
        }
    }

    /** 应用关闭：停止接收、唤醒并等待后台线程清空队列。 */
    @PreDestroy
    public void shutdown() {
        running = false;
        worker.interrupt();
        try {
            worker.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
