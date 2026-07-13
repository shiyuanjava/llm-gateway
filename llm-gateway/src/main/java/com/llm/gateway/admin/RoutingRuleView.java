package com.llm.gateway.admin;

import java.util.ArrayList;
import java.util.List;

/**
 * 路由规则视图对象：把 {@code routing_rule} 与其降级链 {@code routing_fallback} 合成一个对象，
 * 方便前端整体编辑。
 */
public class RoutingRuleView {

    /** 主键（新增时为空）。 */
    private Long id;
    /** 别名。 */
    private String alias;
    /** 首选供应商。 */
    private String primaryProvider;
    /** 首选物理模型。 */
    private String primaryModel;
    /** 升级阈值（提示词 Token），可空。 */
    private Integer maxPromptTokens;
    /** 升级目标供应商，可空。 */
    private String escalateProvider;
    /** 升级目标物理模型，可空。 */
    private String escalateModel;
    /** 降级链。 */
    private List<Fallback> fallbacks = new ArrayList<>();

    /** 一个降级目标。 */
    public static class Fallback {
        /** 顺序。 */
        private Integer seq;
        /** 供应商。 */
        private String provider;
        /** 物理模型。 */
        private String model;

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

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getAlias() {
        return alias;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }

    public String getPrimaryProvider() {
        return primaryProvider;
    }

    public void setPrimaryProvider(String primaryProvider) {
        this.primaryProvider = primaryProvider;
    }

    public String getPrimaryModel() {
        return primaryModel;
    }

    public void setPrimaryModel(String primaryModel) {
        this.primaryModel = primaryModel;
    }

    public Integer getMaxPromptTokens() {
        return maxPromptTokens;
    }

    public void setMaxPromptTokens(Integer maxPromptTokens) {
        this.maxPromptTokens = maxPromptTokens;
    }

    public String getEscalateProvider() {
        return escalateProvider;
    }

    public void setEscalateProvider(String escalateProvider) {
        this.escalateProvider = escalateProvider;
    }

    public String getEscalateModel() {
        return escalateModel;
    }

    public void setEscalateModel(String escalateModel) {
        this.escalateModel = escalateModel;
    }

    public List<Fallback> getFallbacks() {
        return fallbacks;
    }

    public void setFallbacks(List<Fallback> fallbacks) {
        this.fallbacks = fallbacks == null ? new ArrayList<>() : fallbacks;
    }
}
