package com.llm.gateway.redis;

/** Logical Redis roles with independent failure semantics and metrics. */
public enum RedisRole {
    CONTROL,
    CACHE;

    public String tag() {
        return name().toLowerCase();
    }
}
