package cn.cordys.crm.order.service;

import cn.cordys.crm.aiagent.config.AiAgentExternalOrderProperties;
import cn.cordys.crm.order.dto.request.ExternalOrderSyncRequest;
import cn.cordys.crm.order.dto.response.ExternalOrderSyncResult;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ExternalOrderInfoSyncServicePermissionTest {

    @Test
    void shouldNotImportExternalOrdersIntoDifferentOrganization() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        AiAgentExternalOrderProperties properties = new AiAgentExternalOrderProperties();
        properties.setEnabled(true);
        properties.setOrganizationId("org-allowed");
        ExternalOrderInfoSyncService service = new ExternalOrderInfoSyncService();
        ReflectionTestUtils.setField(service, "externalOrderJdbcTemplate", jdbcTemplate);
        ReflectionTestUtils.setField(service, "externalOrderProperties", properties);

        ExternalOrderSyncResult result = service.sync(
                new ExternalOrderSyncRequest(), "operator-1", "org-other");

        assertThat(result.getTotal()).isZero();
        assertThat(result.getWarnings())
                .contains("Current organization is not bound to the external order data source.");
        verifyNoInteractions(jdbcTemplate);
    }
}
