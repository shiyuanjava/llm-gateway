package com.llm.gateway.admin;

import org.junit.jupiter.api.Test;

import com.llm.gateway.admin.dto.ApiKeyWriteRequest;
import com.llm.gateway.admin.dto.RoutingRuleWriteRequest;
import com.llm.gateway.admin.web.AdminApiException;
import com.llm.gateway.config.ConfigRefreshService;
import com.llm.gateway.persistence.entity.RoutingRuleEntity;
import com.llm.gateway.persistence.mapper.ApiKeyMapper;
import com.llm.gateway.persistence.mapper.ModelPricingMapper;
import com.llm.gateway.persistence.mapper.RoutingFallbackMapper;
import com.llm.gateway.persistence.mapper.RoutingRuleMapper;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminCrudFailureTest {

    @Test
    void missingApiKeyUpdateReturnsNotFoundAndDoesNotReload() {
        ApiKeyMapper mapper = mock(ApiKeyMapper.class);
        ConfigRefreshService refresh = mock(ConfigRefreshService.class);
        when(mapper.update(any(), any())).thenReturn(0);
        ApiKeyAdminController controller = new ApiKeyAdminController(mapper, refresh);

        assertThatThrownBy(() -> controller.update(999L, new ApiKeyWriteRequest("t", "", "*", true)))
                .isInstanceOf(AdminApiException.class)
                .hasMessage("API Key 不存在");
        verify(refresh, never()).reloadAll();
    }

    @Test
    void missingPricingDeleteReturnsNotFoundAndDoesNotReload() {
        ModelPricingMapper mapper = mock(ModelPricingMapper.class);
        ConfigRefreshService refresh = mock(ConfigRefreshService.class);
        when(mapper.deleteById(anyLong())).thenReturn(0);
        PricingAdminController controller = new PricingAdminController(mapper, refresh);

        assertThatThrownBy(() -> controller.delete(999L))
                .isInstanceOf(AdminApiException.class)
                .hasMessage("计费记录不存在");
        verify(refresh, never()).reloadAll();
    }

    @Test
    void routingAliasCannotChange() {
        RoutingRuleMapper ruleMapper = mock(RoutingRuleMapper.class);
        RoutingRuleEntity existing = new RoutingRuleEntity();
        existing.setId(7L);
        existing.setAlias("old-alias");
        when(ruleMapper.selectById(7L)).thenReturn(existing);
        RoutingRuleAdminService service = new RoutingRuleAdminService(ruleMapper, mock(RoutingFallbackMapper.class));

        RoutingRuleWriteRequest request = new RoutingRuleWriteRequest("new-alias", "mock", "m", null, null, null, null);
        assertThatThrownBy(() -> service.update(7L, request))
                .isInstanceOf(AdminApiException.class)
                .hasMessage("路由 alias 不允许修改");
    }
}
