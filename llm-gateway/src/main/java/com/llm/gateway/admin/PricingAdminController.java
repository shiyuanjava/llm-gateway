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

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.llm.gateway.admin.dto.PricingWriteRequest;
import com.llm.gateway.admin.web.AdminApiException;
import com.llm.gateway.admin.web.R;
import com.llm.gateway.config.ConfigRefreshService;
import com.llm.gateway.persistence.entity.ModelPricingEntity;
import com.llm.gateway.persistence.mapper.ModelPricingMapper;

/**
 * 模型计费单价管理接口（{@code /admin/pricing}）。增删改后自动刷新计费缓存。
 */
@RestController
@RequestMapping("/admin/pricing")
public class PricingAdminController {

    private final ModelPricingMapper mapper;
    private final ConfigRefreshService refreshService;

    public PricingAdminController(ModelPricingMapper mapper, ConfigRefreshService refreshService) {
        this.mapper = mapper;
        this.refreshService = refreshService;
    }

    /** @return 全部计费单价 */
    @GetMapping
    public R<List<ModelPricingEntity>> list() {
        return R.ok(
                mapper.selectList(Wrappers.<ModelPricingEntity>lambdaQuery().orderByAsc(ModelPricingEntity::getId)));
    }

    /**
     * 新增。
     *
     * @param entity 计费记录
     * @return 新增后的实体
     */
    @PostMapping
    public R<ModelPricingEntity> create(@Valid @RequestBody PricingWriteRequest request) {
        ModelPricingEntity entity = toEntity(request);
        entity.setId(null);
        mapper.insert(entity);
        refreshService.reloadAll();
        return R.ok(entity);
    }

    /**
     * 修改（PUT 全量更新语义）：显式 set 全部业务列（null 也写入），可空的缓存单价才能被清回 NULL。
     * 注意：请求体缺省必填列（model、inputPer1k、outputPer1k）将因数据库 NOT NULL 约束报错。
     *
     * @param id     主键
     * @param entity 新值
     * @return 修改后的实体
     */
    @PutMapping("/{id}")
    public R<ModelPricingEntity> update(@PathVariable Long id, @Valid @RequestBody PricingWriteRequest request) {
        ModelPricingEntity entity = toEntity(request);
        entity.setId(id);
        int affected = mapper.update(
                null,
                Wrappers.<ModelPricingEntity>update()
                        .eq("id", id)
                        .set("model", entity.getModel())
                        .set("input_per_1k", entity.getInputPer1k())
                        .set("output_per_1k", entity.getOutputPer1k())
                        .set("cache_read_per_1k", entity.getCacheReadPer1k())
                        .set("cache_write_per_1k", entity.getCacheWritePer1k()));
        if (affected != 1) {
            throw AdminApiException.notFound("计费记录不存在");
        }
        refreshService.reloadAll();
        return R.ok(entity);
    }

    private ModelPricingEntity toEntity(PricingWriteRequest request) {
        ModelPricingEntity entity = new ModelPricingEntity();
        entity.setModel(request.model().trim());
        entity.setInputPer1k(request.inputPer1k());
        entity.setOutputPer1k(request.outputPer1k());
        entity.setCacheReadPer1k(request.cacheReadPer1k());
        entity.setCacheWritePer1k(request.cacheWritePer1k());
        return entity;
    }

    /**
     * 删除。
     *
     * @param id 主键
     * @return 成功
     */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        if (mapper.deleteById(id) != 1) {
            throw AdminApiException.notFound("计费记录不存在");
        }
        refreshService.reloadAll();
        return R.ok();
    }
}
