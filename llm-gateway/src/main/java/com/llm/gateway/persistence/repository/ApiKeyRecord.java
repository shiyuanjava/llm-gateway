package com.llm.gateway.persistence.repository;

import java.util.List;

/**
 * API Key 领域记录（仓储层把数据库实体转换成它，屏蔽存储细节）。
 *
 * @param keyHash       密钥的 SHA-256 哈希（hex）
 * @param tenant        租户
 * @param roles         角色列表
 * @param allowedModels 可访问的模型/别名列表，{@code ["*"]} 表示全部
 */
public record ApiKeyRecord(String keyHash, String tenant, List<String> roles, List<String> allowedModels) {
}
