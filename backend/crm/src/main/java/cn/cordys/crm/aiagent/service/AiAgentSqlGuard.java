package cn.cordys.crm.aiagent.service;

import cn.cordys.crm.aiagent.config.AiAgentSqlProperties;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class AiAgentSqlGuard {

    private final AiAgentSqlProperties properties;

    public AiAgentSqlGuard(AiAgentSqlProperties properties) {
        this.properties = properties;
    }

    public SqlGuardResult validate(String sql) {
        String normalized = normalize(sql);
        if (!properties.isEnabled()) {
            return SqlGuardResult.rejected("受控 SQL 查询未开启");
        }
        if (StringUtils.isBlank(normalized)) {
            return SqlGuardResult.rejected("SQL 为空");
        }
        if (!normalized.startsWith("select ")) {
            return SqlGuardResult.rejected("只允许 SELECT 查询");
        }
        if (normalized.contains(";")) {
            return SqlGuardResult.rejected("不允许多语句 SQL");
        }
        if (containsAny(normalized, " insert ", " update ", " delete ", " drop ", " alter ", " truncate ", " create ",
                " grant ", " revoke ", " replace ", " into outfile ", " load_file", " information_schema",
                " performance_schema", " mysql.")) {
            return SqlGuardResult.rejected("SQL 包含禁止语句或系统表");
        }
        if (!containsAllowedTable(normalized)) {
            return SqlGuardResult.rejected("SQL 未命中允许查询的表");
        }
        return SqlGuardResult.allowed(enforceLimit(sql));
    }

    private String normalize(String sql) {
        return StringUtils.defaultString(sql).replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private boolean containsAllowedTable(String normalizedSql) {
        return properties.getAllowedTables().stream()
                .map(table -> table.toLowerCase(Locale.ROOT))
                .anyMatch(table -> normalizedSql.contains(" " + table + " ")
                        || normalizedSql.contains(" " + table)
                        || normalizedSql.contains(table + " "));
    }

    private String enforceLimit(String sql) {
        String text = StringUtils.removeEnd(StringUtils.defaultString(sql).trim(), ";");
        if (normalize(text).matches(".*\\blimit\\s+\\d+\\b.*")) {
            return text;
        }
        return text + " LIMIT " + Math.max(1, properties.getMaxRows());
    }

    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
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
