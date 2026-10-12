package cn.cordys.crm.aiagent.service;

import cn.cordys.crm.aiagent.dto.query.AiAgentQueryResult;
import cn.cordys.crm.aiagent.config.AiAgentSqlProperties;
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
    @Autowired
    private AiAgentSqlProperties sqlProperties = new AiAgentSqlProperties();
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
        return execute(builtQuery, true);
    }

    public AiAgentQueryResult execute(AiAgentSqlBuilder.BuiltQuery builtQuery, boolean countTotal) {
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

        AiAgentSqlGuard.SqlGuardResult guardResult = Integer.valueOf(AiAgentResultWindow.UNLIMITED).equals(builtQuery.plan().getLimit())
                ? sqlGuard.validate(builtQuery.sql(), false) : sqlGuard.validate(builtQuery.sql());
        if (!guardResult.allowed()) {
            result.getWarnings().add("受控 SQL 查询未执行：" + guardResult.reason());
            result.setDurationMs(System.currentTimeMillis() - start);
            return result;
        }
        NamedParameterJdbcTemplate jdbcTemplate = resolveJdbcTemplate(builtQuery.dataSourceKind());
        if (jdbcTemplate == null) {
            result.getWarnings().add(builtQuery.dataSourceKind() == AiAgentSemanticSchemaService.DataSourceKind.EXTERNAL_CONTRACT
                    ? "外部订单只读数据源未配置。"
                    : "CRM 只读查询数据源未配置。");
            result.setDurationMs(System.currentTimeMillis() - start);
            return result;
        }

        try {
            if (jdbcTemplate.getJdbcTemplate() != null) {
                int timeout = Math.max(1, Math.min(120, sqlProperties.getQueryTimeoutSeconds()));
                int existingTimeout = jdbcTemplate.getJdbcTemplate().getQueryTimeout();
                jdbcTemplate.getJdbcTemplate().setQueryTimeout(existingTimeout > 0 ? Math.min(existingTimeout, timeout) : timeout);
            }
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    guardResult.sql(),
                    new MapSqlParameterSource(builtQuery.params())
            );
            result.setRows(rows);
            if (countTotal && AiAgentResultWindow.isPaged(builtQuery.plan()) && builtQuery.countSql() != null) {
                readTotal(builtQuery, jdbcTemplate, result);
            }
        } catch (DataAccessException e) {
            String state = e.getMostSpecificCause() instanceof java.sql.SQLException sqlException ? sqlException.getSQLState() : null;
            result.getWarnings().add("数据库查询执行失败，请检查字段、日期语法和分组条件"
                    + (state != null && state.matches("[A-Z0-9]{5}") ? " (SQLState=" + state + ")" : ""));
        }
        result.setDurationMs(System.currentTimeMillis() - start);
        return result;
    }

    private void readTotal(AiAgentSqlBuilder.BuiltQuery query, NamedParameterJdbcTemplate jdbc,
                           AiAgentQueryResult result) {
        try {
            var countGuard = sqlGuard.validate(query.countSql());
            if (countGuard == null || !countGuard.allowed()) {
                result.setTotalWarning("总数统计未能执行，当前仅展示已取得的记录，不能视为全部结果。");
                return;
            }
            Long total = jdbc.queryForObject(countGuard.sql(), new MapSqlParameterSource(query.params()), Long.class);
            long offset = query.plan().getOffset() == null ? 0 : query.plan().getOffset();
            if (total == null || total < 0 || (!result.getRows().isEmpty() && total < offset + result.getRows().size())) {
                result.setTotalWarning("查询期间数据发生变化或总数未能核实，请重新查询；当前记录不能视为全部结果。");
                return;
            }
            result.setTotalRows(total);
        } catch (DataAccessException e) {
            // Keep usable detail records without presenting their length as the total.
            result.setTotalWarning("总数统计失败，当前仅展示已取得的记录，无法确认剩余数量。");
        }
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
