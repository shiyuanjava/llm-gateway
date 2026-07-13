package com.llm.gateway.auth;

import java.util.List;

/**
 * 鉴权通过后代表调用方身份的主体，贯穿整条请求流水线，用于限流、配额、成本归因与授权。
 *
 * @param tenant        租户标识
 * @param roles         角色列表（RBAC）
 * @param allowedModels 允许访问的模型/别名，{@code "*"} 表示全部
 */
public record Principal(String tenant, List<String> roles, List<String> allowedModels) {

    private static final String WILDCARD = "*";

    /**
     * 判断该主体是否有权访问指定模型或别名。
     *
     * @param model 模型名或别名
     * @return 有权访问返回 true
     */
    public boolean canAccess(String model) {
        if (allowedModels == null || allowedModels.isEmpty()) {
            return false;
        }
        return allowedModels.contains(WILDCARD) || allowedModels.contains(model);
    }
}
