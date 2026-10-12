package cn.cordys.crm.aiagent.service;

import cn.cordys.crm.aiagent.config.AiAgentSqlProperties;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AiAgentSqlGuard {

    private final AiAgentSqlProperties properties;

    public AiAgentSqlGuard(AiAgentSqlProperties properties) {
        this.properties = properties;
    }

    public SqlGuardResult validate(String sql) {
        return validate(sql, true);
    }

    public SqlGuardResult validate(String sql, boolean applyDefaultLimit) {
        if (!properties.isEnabled()) {
            return SqlGuardResult.rejected("受控 SQL 查询未开启");
        }
        try {
            var parsed = AiAgentSqlParser.parse(sql, true);
            Set<String> allowed = properties.getAllowedTables().stream()
                    .map(AiAgentSqlParser::identifier).collect(Collectors.toSet());
            for (String table : parsed.tables()) {
                if (!allowed.contains(table)) return SqlGuardResult.rejected("表不允许查询：" + table);
            }
            var query = parsed.select();
            if (applyDefaultLimit && query.getLimit() == null) {
                query.setLimit(new net.sf.jsqlparser.statement.select.Limit()
                        .withRowCount(new net.sf.jsqlparser.expression.LongValue(Math.max(1, properties.getMaxRows()))));
            }
            return SqlGuardResult.allowed(query.toString());
        } catch (IllegalArgumentException e) {
            return SqlGuardResult.rejected(e.getMessage());
        }
    }

    public record SqlGuardResult(boolean allowed, String sql, String reason) {
        public static SqlGuardResult allowed(String sql) {
            return new SqlGuardResult(true, sql, null);
        }

        public static SqlGuardResult rejected(String reason) {
            return new SqlGuardResult(false, null, reason);
        }
    }
}
