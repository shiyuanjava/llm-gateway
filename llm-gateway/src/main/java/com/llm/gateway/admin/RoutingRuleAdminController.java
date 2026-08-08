package com.llm.gateway.admin;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.llm.gateway.admin.dto.RoutingRuleWriteRequest;
import com.llm.gateway.admin.web.R;
import com.llm.gateway.config.ConfigRefreshService;

/**
 * 路由规则管理接口（{@code /admin/routing-rules}）。增删改后自动刷新路由缓存。
 */
@RestController
@RequestMapping("/admin/routing-rules")
public class RoutingRuleAdminController {

    private final RoutingRuleAdminService service;
    private final ConfigRefreshService refreshService;

    public RoutingRuleAdminController(RoutingRuleAdminService service, ConfigRefreshService refreshService) {
        this.service = service;
        this.refreshService = refreshService;
    }

    /** @return 所有路由规则（含降级链） */
    @GetMapping
    public R<List<RoutingRuleView>> list() {
        return R.ok(service.list());
    }

    /**
     * 新增。
     *
     * @param view 规则视图
     * @return 保存结果
     */
    @PostMapping
    public R<RoutingRuleView> create(@Valid @RequestBody RoutingRuleWriteRequest request) {
        RoutingRuleView saved = service.create(request);
        refreshService.reloadAll();
        return R.ok(saved);
    }

    /**
     * 修改。
     *
     * @param id   主键
     * @param view 规则视图
     * @return 保存结果
     */
    @PutMapping("/{id}")
    public R<RoutingRuleView> update(@PathVariable Long id, @Valid @RequestBody RoutingRuleWriteRequest request) {
        RoutingRuleView saved = service.update(id, request);
        refreshService.reloadAll();
        return R.ok(saved);
    }

    /**
     * 删除。
     *
     * @param id 主键
     * @return 成功
     */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        service.delete(id);
        refreshService.reloadAll();
        return R.ok();
    }
}
