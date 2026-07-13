package com.llm.gateway.persistence.repository;

import java.util.List;

/**
 * API Key 仓储抽象。把存储细节挡在接口之后，单元测试可注入内存假实现。
 */
public interface ApiKeyRepository {

    /** @return 所有启用的 API Key 记录 */
    List<ApiKeyRecord> findAll();
}
