package com.llm.gateway.admin;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.llm.gateway.admin.web.R;
import com.llm.gateway.config.ConfigRefreshService;
import com.llm.gateway.config.GatewayProperties;

/**
 * 元信息与运维接口（{@code /admin/meta}）：供前端下拉选择供应商、展示默认 LLM，并提供手动刷新。
 */
@RestController
@RequestMapping("/admin/meta")
public class MetaAdminController {

    private final GatewayProperties properties;
    private final ConfigRefreshService refreshService;

    public MetaAdminController(GatewayProperties properties, ConfigRefreshService refreshService) {
        this.properties = properties;
        this.refreshService = refreshService;
    }

    /** @return 供应商列表、默认 provider/model */
    @GetMapping
    public R<Meta> meta() {
        List<String> providers = properties.providers() == null
                ? List.of()
                : List.copyOf(properties.providers().keySet());
        String defaultProvider =
                properties.llm() == null ? null : properties.llm().provider();
        String defaultModel = properties.llm() == null ? null : properties.llm().model();
        return R.ok(new Meta(providers, defaultProvider, defaultModel));
    }

    /**
     * 手动刷新全部配置缓存。
     *
     * @return 成功
     */
    @PostMapping("/reload")
    public R<Void> reload() {
        refreshService.reloadAll();
        return R.ok();
    }

    /**
     * 元信息。
     *
     * @param providers       已接入供应商名
     * @param defaultProvider 默认供应商
     * @param defaultModel    默认模型
     */
    public record Meta(List<String> providers, String defaultProvider, String defaultModel) {}
}
