package cn.cordys.crm.aiagent.service;

import cn.cordys.crm.aiagent.dto.query.AiAgentQueryPlan;
import cn.cordys.crm.aiagent.dto.query.AiAgentQueryResult;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiAgentQueryExecutorPermissionTest {

    @Test
    void shouldExecuteExternalQueryWithoutAdditionalOrganizationBinding() {
        AiAgentSqlGuard sqlGuard = mock(AiAgentSqlGuard.class);
        when(sqlGuard.validate("SELECT * FROM contract_info"))
                .thenReturn(AiAgentSqlGuard.SqlGuardResult.allowed("SELECT * FROM contract_info LIMIT 20"));
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.queryForList(
                eq("SELECT * FROM contract_info LIMIT 20"), any(MapSqlParameterSource.class)))
                .thenReturn(List.of(Map.of("product_name", "测试品种")));
        AiAgentQueryExecutor executor = new AiAgentQueryExecutor(sqlGuard);
        ReflectionTestUtils.setField(executor, "externalContractJdbcTemplate", jdbcTemplate);

        AiAgentSemanticSchemaService.EntitySpec entity = new AiAgentSemanticSchemaService()
                .findEntity("contract_info")
                .orElseThrow();
        AiAgentQueryPlan plan = new AiAgentQueryPlan();
        plan.setEntity("contract_info");
        plan.setQueryType("LIST");
        AiAgentSqlBuilder.BuiltQuery query = new AiAgentSqlBuilder.BuiltQuery(
                "SELECT * FROM contract_info",
                Map.of("orgId", "org-other"),
                AiAgentSemanticSchemaService.DataSourceKind.EXTERNAL_CONTRACT,
                entity,
                plan,
                Map.of(),
                Map.of()
        );

        AiAgentQueryResult result = executor.execute(query);

        assertThat(result.getRows()).containsExactly(Map.of("product_name", "测试品种"));
        assertThat(result.getWarnings()).isEmpty();
        verify(jdbcTemplate).queryForList(
                eq("SELECT * FROM contract_info LIMIT 20"), any(MapSqlParameterSource.class));
    }
}
