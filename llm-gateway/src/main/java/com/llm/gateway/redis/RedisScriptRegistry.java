package com.llm.gateway.redis;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/** Central cache for typed Lua scripts loaded from the classpath. */
@Component
public class RedisScriptRegistry {

    private final Map<String, RedisScript<?>> scripts = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public <T> RedisScript<T> load(String classpath, Class<T> resultType) {
        String cacheKey = classpath + "|" + resultType.getName();
        return (RedisScript<T>) scripts.computeIfAbsent(cacheKey, ignored -> {
            DefaultRedisScript<T> script = new DefaultRedisScript<>();
            script.setLocation(new ClassPathResource(classpath));
            script.setResultType(resultType);
            return script;
        });
    }
}
