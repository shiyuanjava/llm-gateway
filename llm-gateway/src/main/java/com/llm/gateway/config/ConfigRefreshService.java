package com.llm.gateway.config;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 配置刷新服务：一次性刷新所有 {@link ConfigReloadable} 组件的缓存。
 *
 * <p>管理端（Admin REST）在改动 api_key / 路由规则 / 计费等配置后调用 {@link #reloadAll()}，
 * 使改动即时生效。
 */
@Service
public class ConfigRefreshService {

    private static final Logger log = LoggerFactory.getLogger(ConfigRefreshService.class);

    private final List<ConfigReloadable> reloadables;

    /**
     * @param reloadables 容器中所有可热刷新的配置组件
     */
    public ConfigRefreshService(List<ConfigReloadable> reloadables) {
        this.reloadables = reloadables;
    }

    /** 刷新所有配置组件的缓存。 */
    public void reloadAll() {
        for (ConfigReloadable reloadable : reloadables) {
            reloadable.reload();
        }
        log.info("已刷新 {} 个配置组件缓存", reloadables.size());
    }
}
