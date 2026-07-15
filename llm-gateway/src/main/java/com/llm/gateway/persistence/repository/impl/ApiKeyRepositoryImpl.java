package com.llm.gateway.persistence.repository.impl;

import java.util.Arrays;
import java.util.List;

import org.springframework.stereotype.Repository;

import com.llm.gateway.persistence.mapper.ApiKeyMapper;
import com.llm.gateway.persistence.repository.ApiKeyRecord;
import com.llm.gateway.persistence.repository.ApiKeyRepository;

/**
 * 基于 MyBatis-Plus 的 API Key 仓储实现。
 */
@Repository
public class ApiKeyRepositoryImpl implements ApiKeyRepository {

    private final ApiKeyMapper mapper;

    public ApiKeyRepositoryImpl(ApiKeyMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<ApiKeyRecord> findAll() {
        return mapper.selectList(null).stream()
                .filter(e -> !Boolean.FALSE.equals(e.getEnabled()))
                .map(e -> new ApiKeyRecord(
                        e.getKeyHash(), e.getTenant(), splitCsv(e.getRoles()), splitCsv(e.getAllowedModels())))
                .toList();
    }

    /**
     * 把逗号分隔字符串拆成去空白的非空列表。
     *
     * @param csv 逗号分隔字符串
     * @return 列表
     */
    static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
