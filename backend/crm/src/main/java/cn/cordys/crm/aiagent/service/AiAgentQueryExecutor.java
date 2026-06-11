package cn.cordys.crm.aiagent.service;

import cn.cordys.crm.aiagent.dto.query.AiAgentQueryResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

@Service
public class AiAgentQueryExecutor {

    private final AiAgentSqlGuard sqlGuard;
    @Autowired(required = false)
    @Qualifier("dataSource")
    private DataSource crmDataSource;
    @Autowired(required = false)
    @Qualifier("aiAgentExternalOrderJdbcTemplate")
    private NamedParameterJdbcTemplate externalContractJdbcTemplate;
    private NamedParameterJdbcTemplate crmJdbcTemplate;

    public AiAgentQueryExecutor(AiAgentSqlGuard sqlGuard) {
        this.sqlGuard = sqlGuard;
    }

    public AiAgentQueryResult execute(AiAgentSqlBuilder.BuiltQuery builtQuery) {
        long start = System.currentTimeMillis();
        AiAgentQueryResult result = new AiAgentQueryResult();
        result.setPlan(builtQuery.plan());
        result.setEntityLabel(builtQuery.entity().label());
        result.setEvidence(builtQuery.entity().evidence());
        result.setSelectedFields(builtQuery.plan().getSelectFields());
        result.setGroupByFields(builtQuery.plan().getGroupBy());
        result.setMetrics(builtQuery.plan().getMetrics());
        result.setFieldLabels(builtQuery.fieldLabels());
        result.setFieldMasks(builtQuery.fieldMasks());
        result.setToolName("crm_database_query");

        AiAgentSqlGuard.SqlGuardResult guardResult = sqlGuard.validate(builtQuery.sql());
        if (!guardResult.allowed()) {
            result.getWarnings().add("受控 SQL 查询未执行：" + guardResult.reason());
            result.setDurationMs(System.currentTimeMillis() - start);
            return result;
        }

        NamedParameterJdbcTemplate jdbcTemplate = resolveJdbcTemplate(builtQuery.dataSourceKind());
        if (jdbcTemplate == null) {
            result.getWarnings().add(builtQuery.dataSourceKind() == AiAgentSemanticSchemaService.DataSourceKind.EXTERNAL_CONTRACT
                    ? "外部 contract_info 只读数据源未配置。"
                    : "CRM 只读查询数据源未配置。");
            result.setDurationMs(System.currentTimeMillis() - start);
            return result;
        }

        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    guardResult.sql(),
                    new MapSqlParameterSource(builtQuery.params())
            );
            result.setRows(rows);
        } catch (DataAccessException e) {
            result.getWarnings().add("数据库查询执行失败：" + e.getMostSpecificCause().getMessage());
        }
        result.setDurationMs(System.currentTimeMillis() - start);
        return result;
    }

    private NamedParameterJdbcTemplate resolveJdbcTemplate(AiAgentSemanticSchemaService.DataSourceKind dataSourceKind) {
        if (dataSourceKind == AiAgentSemanticSchemaService.DataSourceKind.EXTERNAL_CONTRACT) {
            return externalContractJdbcTemplate;
        }
        if (crmJdbcTemplate == null && crmDataSource != null) {
            crmJdbcTemplate = new NamedParameterJdbcTemplate(crmDataSource);
        }
        return crmJdbcTemplate;
    }
}
