package com.llm.gateway.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * {@code routing_fallback} 表实体：某路由规则的一个降级目标，按 {@code seq} 排序。
 */
@TableName("routing_fallback")
public class RoutingFallbackEntity {

    /** 主键，自增。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属路由规则的别名（关联 routing_rule.alias）。 */
    private String ruleAlias;

    /** 降级顺序，从小到大依次尝试。 */
    private Integer seq;

    /** 降级目标供应商名。 */
    private String provider;

    /** 降级目标物理模型名。 */
    private String model;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRuleAlias() {
        return ruleAlias;
    }

    public void setRuleAlias(String ruleAlias) {
        this.ruleAlias = ruleAlias;
    }

    public Integer getSeq() {
        return seq;
    }

    public void setSeq(Integer seq) {
        this.seq = seq;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }
}
