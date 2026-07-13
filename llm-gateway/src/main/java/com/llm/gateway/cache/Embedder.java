package com.llm.gateway.cache;

/**
 * 文本向量化抽象。语义缓存用它把文本编码成向量再比对相似度。
 *
 * <p>生产环境会接入真实的 Embedding 模型（OpenAI text-embedding-3、bge 等）；
 * 本工程默认提供一个确定性的本地实现 {@link MockEmbedder}，使语义缓存无需外网即可演示。
 */
public interface Embedder {

    /**
     * 把文本编码为向量。
     *
     * @param text 输入文本
     * @return 向量
     */
    float[] embed(String text);
}
