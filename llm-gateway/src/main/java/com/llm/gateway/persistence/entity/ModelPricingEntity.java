package com.llm.gateway.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * {@code model_pricing} 表实体：模型每 1K Token 的计费单价。
 *
 * <p>注意：字段名含数字（{@code inputPer1k}），MyBatis-Plus 默认驼峰转下划线会得到 {@code input_per1k}，
 * 与列名 {@code input_per_1k} 不符，故用 {@link TableField} 显式指定列名。
 */
@TableName("model_pricing")
public class ModelPricingEntity {

    /** 主键，自增。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 物理模型名。 */
    private String model;

    /** 输入（prompt）每 1K Token 单价（美元）。 */
    @TableField("input_per_1k")
    private Double inputPer1k;

    /** 输出（completion）每 1K Token 单价（美元）。 */
    @TableField("output_per_1k")
    private Double outputPer1k;

    /** 缓存读每 1K Token 单价（美元），NULL=未配置按 input 单价计。 */
    @TableField("cache_read_per_1k")
    private Double cacheReadPer1k;

    /** 缓存写每 1K Token 单价（美元），NULL=未配置按 input 单价计。 */
    @TableField("cache_write_per_1k")
    private Double cacheWritePer1k;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Double getInputPer1k() {
        return inputPer1k;
    }

    public void setInputPer1k(Double inputPer1k) {
        this.inputPer1k = inputPer1k;
    }

    public Double getOutputPer1k() {
        return outputPer1k;
    }

    public void setOutputPer1k(Double outputPer1k) {
        this.outputPer1k = outputPer1k;
    }

    public Double getCacheReadPer1k() {
        return cacheReadPer1k;
    }

    public void setCacheReadPer1k(Double cacheReadPer1k) {
        this.cacheReadPer1k = cacheReadPer1k;
    }

    public Double getCacheWritePer1k() {
        return cacheWritePer1k;
    }

    public void setCacheWritePer1k(Double cacheWritePer1k) {
        this.cacheWritePer1k = cacheWritePer1k;
    }
}
