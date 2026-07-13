package com.llm.gateway.config;

/**
 * 可热刷新的配置组件：从数据库加载并缓存配置的服务实现它，
 * 当管理端改动配置后调用 {@link #reload()} 即可让改动立即生效，无需重启。
 */
public interface ConfigReloadable {

    /** 重新从数据源加载并替换内存缓存。 */
    void reload();
}
