package com.llm.gateway.cache;

import org.springframework.stereotype.Component;

/**
 * 确定性的本地 Embedder：把文本按词哈希到固定维度的「词袋」向量并做 L2 归一化。
 *
 * <p>它<strong>不是</strong>真正的语义模型，只能捕捉词面重合度，但足以离线演示语义缓存的
 * 命中流程与相似度阈值。要换成真实模型，实现 {@link Embedder} 接口替换本 Bean 即可。
 */
@Component
public class MockEmbedder implements Embedder {

    private static final int DIMENSIONS = 64;

    @Override
    public float[] embed(String text) {
        float[] vector = new float[DIMENSIONS];
        if (text == null || text.isBlank()) {
            return vector;
        }
        for (String token : text.toLowerCase().split("\\s+|(?<=[\\u4e00-\\u9fa5])")) {
            if (token.isEmpty()) {
                continue;
            }
            int bucket = Math.floorMod(token.hashCode(), DIMENSIONS);
            vector[bucket] += 1.0f;
        }
        normalize(vector);
        return vector;
    }

    /**
     * 对向量做 L2 归一化（便于用点积直接得到余弦相似度）。
     *
     * @param vector 待归一化向量（原地修改）
     */
    private void normalize(float[] vector) {
        double norm = 0.0;
        for (float v : vector) {
            norm += v * v;
        }
        norm = Math.sqrt(norm);
        if (norm == 0.0) {
            return;
        }
        for (int i = 0; i < vector.length; i++) {
            vector[i] /= (float) norm;
        }
    }
}
