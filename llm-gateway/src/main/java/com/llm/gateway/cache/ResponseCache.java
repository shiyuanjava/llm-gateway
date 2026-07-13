package com.llm.gateway.cache;

import java.util.Optional;

import com.llm.gateway.api.dto.ChatCompletionResponse;

/**
 * 响应缓存抽象。便于从单机内存缓存演进到 Redis 等分布式缓存。
 */
public interface ResponseCache {

    /**
     * 按键读取缓存。
     *
     * @param key 缓存键
     * @return 命中且未过期则返回响应，否则为空
     */
    Optional<ChatCompletionResponse> get(String key);

    /**
     * 写入缓存。
     *
     * @param key      缓存键
     * @param response 响应
     */
    void put(String key, ChatCompletionResponse response);
}
