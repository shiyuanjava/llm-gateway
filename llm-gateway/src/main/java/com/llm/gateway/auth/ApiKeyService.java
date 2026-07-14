package com.llm.gateway.auth;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.llm.gateway.config.ConfigReloadable;
import com.llm.gateway.exception.AuthorizationException;
import com.llm.gateway.persistence.repository.ApiKeyRecord;
import com.llm.gateway.persistence.repository.ApiKeyRepository;

/**
 * API Key 鉴权与模型授权服务。
 *
 * <p>从数据库加载 API Key 建立内存索引；运行时据此完成「认证」与「授权」。索引可通过
 * {@link #reload()} 热刷新（管理端改动 Key 后调用），无需重启。
 *
 * <p>索引以 SHA-256 哈希为键，明文 Key 不进入内存索引。
 */
@Service
public class ApiKeyService implements ConfigReloadable {

    private final ApiKeyRepository apiKeyRepository;
    private volatile Map<String, Principal> keyIndex;

    /**
     * @param apiKeyRepository API Key 仓储（数据库）
     */
    public ApiKeyService(ApiKeyRepository apiKeyRepository) {
        this.apiKeyRepository = apiKeyRepository;
        reload();
    }

    @Override
    public void reload() {
        Map<String, Principal> index = new HashMap<>();
        for (ApiKeyRecord record : apiKeyRepository.findAll()) {
            index.put(record.keyHash(), new Principal(record.tenant(), record.roles(), record.allowedModels()));
        }
        this.keyIndex = Map.copyOf(index);
    }

    /**
     * 认证：根据 API Key 查找对应主体（按明文的 SHA-256 哈希查索引）。
     *
     * @param apiKey 请求携带的 API Key（明文）
     * @return 命中则返回主体，否则为空
     */
    public Optional<Principal> authenticate(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(keyIndex.get(ApiKeyGenerator.sha256(apiKey)));
    }

    /**
     * 授权：校验主体是否有权访问目标模型，无权则抛出 {@link AuthorizationException}。
     *
     * @param principal 主体
     * @param model     目标模型或别名
     */
    public void authorize(Principal principal, String model) {
        if (!principal.canAccess(model)) {
            throw new AuthorizationException("租户 [" + principal.tenant() + "] 无权访问模型 [" + model + "]");
        }
    }
}
