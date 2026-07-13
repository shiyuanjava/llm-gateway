package com.llm.gateway.cache;

import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.springframework.stereotype.Component;

import com.llm.gateway.api.dto.ChatCompletionResponse;
import com.llm.gateway.config.GatewayProperties;

/**
 * 语义缓存：对查询文本做向量化，命中相似度超过阈值的历史结果即直接返回。
 *
 * <p>相比精确缓存，它能覆盖「换了说法但意思相同」的请求。本实现为单机内存版，按 FIFO 限制容量；
 * 生产环境通常用向量数据库（Milvus、pgvector 等）承载。
 */
@Component
public class SemanticCache {

    private static final int MAX_ENTRIES = 500;

    private final ConcurrentLinkedDeque<Entry> entries = new ConcurrentLinkedDeque<>();
    private final Embedder embedder;
    private final double threshold;
    private final long ttlMillis;

    /**
     * @param embedder   向量化器
     * @param properties 网关配置，提供相似度阈值与 TTL
     */
    public SemanticCache(Embedder embedder, GatewayProperties properties) {
        this.embedder = embedder;
        this.threshold = properties.cache().semantic().similarityThreshold();
        this.ttlMillis = properties.cache().ttlSeconds() * 1000L;
    }

    /**
     * 查询与给定文本语义最相近且超过阈值的缓存结果。
     *
     * @param text 查询文本
     * @return 命中则返回响应，否则为空
     */
    public Optional<ChatCompletionResponse> lookup(String text) {
        float[] query = embedder.embed(text);
        long now = clock();
        ChatCompletionResponse best = null;
        double bestScore = threshold;
        for (Entry entry : entries) {
            if (entry.expiresAt < now) {
                entries.remove(entry);
                continue;
            }
            double score = cosine(query, entry.embedding);
            if (score >= bestScore) {
                bestScore = score;
                best = entry.response;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * 写入一条语义缓存。
     *
     * @param text     原始查询文本
     * @param response 对应响应
     */
    public void put(String text, ChatCompletionResponse response) {
        entries.addLast(new Entry(embedder.embed(text), response, clock() + ttlMillis));
        while (entries.size() > MAX_ENTRIES) {
            entries.pollFirst();
        }
    }

    /**
     * 当前时间（毫秒）。抽成方法便于测试覆写。
     *
     * @return 当前毫秒时间戳
     */
    protected long clock() {
        return System.currentTimeMillis();
    }

    /**
     * 计算两个向量的余弦相似度。
     *
     * @param a 向量 a
     * @param b 向量 b
     * @return 余弦相似度
     */
    private double cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            return 0.0;
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * 语义缓存条目。
     */
    private record Entry(float[] embedding, ChatCompletionResponse response, long expiresAt) {
    }
}
