package com.llm.gateway.admin;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.llm.gateway.admin.web.R;
import com.llm.gateway.auth.ApiKeyGenerator;
import com.llm.gateway.config.ConfigRefreshService;
import com.llm.gateway.persistence.entity.ApiKeyEntity;
import com.llm.gateway.persistence.mapper.ApiKeyMapper;

/**
 * API Key 管理接口（{@code /admin/api-keys}）。增删改后自动刷新网关的鉴权缓存。
 *
 * <p>Key 由服务端生成，库中只存 SHA-256 哈希与展示前缀，明文仅在创建响应中返回一次。
 */
@RestController
@RequestMapping("/admin/api-keys")
public class ApiKeyAdminController {

    /** 创建响应：实体信息 + 仅此一次返回的完整明文 Key。 */
    public record ApiKeyCreatedView(ApiKeyEntity entity, String apiKey) {}

    private final ApiKeyMapper mapper;
    private final ConfigRefreshService refreshService;

    public ApiKeyAdminController(ApiKeyMapper mapper, ConfigRefreshService refreshService) {
        this.mapper = mapper;
        this.refreshService = refreshService;
    }

    /** @return 全部 API Key（按 id 升序） */
    @GetMapping
    public R<List<ApiKeyEntity>> list() {
        return R.ok(mapper.selectList(Wrappers.<ApiKeyEntity>lambdaQuery().orderByAsc(ApiKeyEntity::getId)));
    }

    /**
     * 新增：服务端生成 Key，明文仅在本响应返回一次，库中只存哈希与前缀。
     *
     * @param entity 租户/角色/可用模型/启用（key 字段忽略）
     * @return 实体 + 一次性明文 Key
     */
    @PostMapping
    public R<ApiKeyCreatedView> create(@RequestBody ApiKeyEntity entity) {
        String plainKey = ApiKeyGenerator.generate();
        entity.setId(null);
        entity.setKeyHash(ApiKeyGenerator.sha256(plainKey));
        entity.setKeyPrefix(ApiKeyGenerator.prefixOf(plainKey));
        if (entity.getEnabled() == null) {
            entity.setEnabled(true);
        }
        mapper.insert(entity);
        refreshService.reloadAll();
        return R.ok(new ApiKeyCreatedView(entity, plainKey));
    }

    /**
     * 修改（PUT 全量更新语义，同 Pricing）：显式 set 全部业务列；key 哈希与前缀不在
     * SET 列表中，请求体携带的 key 字段天然无效（防篡改），响应中也不回显。
     * 注意：请求体缺省必填列（tenant 等）将因数据库 NOT NULL 约束报错。
     *
     * @param id     主键
     * @param entity 新值
     * @return 修改后的实体（不含 key 字段）
     */
    @PutMapping("/{id}")
    public R<ApiKeyEntity> update(@PathVariable Long id, @RequestBody ApiKeyEntity entity) {
        entity.setId(id);
        entity.setKeyHash(null);
        entity.setKeyPrefix(null);
        mapper.update(
                null,
                Wrappers.<ApiKeyEntity>update()
                        .eq("id", id)
                        .set("tenant", entity.getTenant())
                        .set("roles", entity.getRoles())
                        .set("allowed_models", entity.getAllowedModels())
                        .set("enabled", entity.getEnabled()));
        refreshService.reloadAll();
        return R.ok(entity);
    }

    /**
     * 删除。
     *
     * @param id 主键
     * @return 成功
     */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        mapper.deleteById(id);
        refreshService.reloadAll();
        return R.ok();
    }
}
