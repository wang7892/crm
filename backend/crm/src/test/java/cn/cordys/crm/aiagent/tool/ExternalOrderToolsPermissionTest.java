package cn.cordys.crm.aiagent.tool;

import cn.cordys.common.dto.DeptDataPermissionDTO;
import cn.cordys.crm.aiagent.dto.AiAgentContext;
import cn.cordys.crm.aiagent.dto.internal.ExternalOrderQueryResult;
import cn.cordys.crm.aiagent.mapper.AiAgentInternalMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ExternalOrderToolsPermissionTest {

    @Test
    void shouldNotQueryExternalContractWhenContractPermissionIsMissing() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        AiAgentInternalMapper mapper = mock(AiAgentInternalMapper.class);
        ExternalOrderTools tools = new ExternalOrderTools();
        ReflectionTestUtils.setField(tools, "jdbcTemplate", jdbcTemplate);
        ReflectionTestUtils.setField(tools, "aiAgentInternalMapper", mapper);

        AiAgentContext context = new AiAgentContext();
        context.setUserId("user-1");
        context.setOrganizationId("org-1");
        DeptDataPermissionDTO customerAll = new DeptDataPermissionDTO();
        customerAll.setAll(true);
        context.setDataPermission(customerAll);

        ExternalOrderQueryResult result = tools.findOrdersByProduct(context, "ABC", 20);

        assertThat(result.getRows()).isEmpty();
        assertThat(result.getWarnings()).contains("No available contract managers in current data permission.");
        verifyNoInteractions(jdbcTemplate, mapper);
    }

    @Test
    void shouldQueryExternalContractWithoutAdditionalOrganizationBinding() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        AiAgentInternalMapper mapper = mock(AiAgentInternalMapper.class);
        ExternalOrderTools tools = new ExternalOrderTools();
        ReflectionTestUtils.setField(tools, "jdbcTemplate", jdbcTemplate);
        ReflectionTestUtils.setField(tools, "aiAgentInternalMapper", mapper);

        AiAgentContext context = new AiAgentContext();
        context.setUserId("user-1");
        context.setOrganizationId("org-other");
        DeptDataPermissionDTO contractAll = new DeptDataPermissionDTO();
        contractAll.setAll(true);
        context.setContractDataPermission(contractAll);

        ExternalOrderQueryResult result = tools.findOrdersByProduct(context, "ABC", 20);

        assertThat(result.getWarnings()).isEmpty();
        verify(jdbcTemplate).query(
                anyString(), any(MapSqlParameterSource.class), any(RowMapper.class));
        verifyNoInteractions(mapper);
    }
}
