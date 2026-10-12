package cn.cordys.crm.aiagent.service;

import cn.cordys.common.util.JSON;
import cn.cordys.crm.aiagent.dto.AiAgentContext;
import org.springframework.stereotype.Service;
import java.util.*;

/** SQLBot samples: three rows and ten columns, read through the host's existing query boundary. */
@Service
public class AiAgentSampleDataService {
    private final AiAgentTextSqlCompiler compiler;
    private final AiAgentQueryExecutor executor;
    private final AiAgentDatasourceQueryService sources;
    private final AiAgentSemanticSchemaService schema;
    public AiAgentSampleDataService(AiAgentTextSqlCompiler compiler, AiAgentQueryExecutor executor,
            AiAgentDatasourceQueryService sources, AiAgentSemanticSchemaService schema) {
        this.compiler = compiler; this.executor = executor; this.sources = sources; this.schema = schema;
    }
    public String read(AiAgentContext context) {
        StringBuilder result = new StringBuilder();
        for (String table : context.getSelectedQueryTables()) {
            try {
                List<String> columns = context.getDatasourceId() == null
                        ? schema.findEntity(table).orElseThrow().fields().values().stream()
                            .filter(AiAgentSemanticSchemaService.FieldSpec::selectable).map(AiAgentSemanticSchemaService.FieldSpec::key).limit(10).toList()
                        : context.getDatasourceSchema().stream().filter(t -> t.getName().equals(table)).findFirst().orElseThrow()
                            .getColumns().stream().map(c -> c.getName()).limit(10).toList();
                if (columns.isEmpty()) continue;
                String sql = "SELECT " + java.util.stream.IntStream.range(0, columns.size())
                        .mapToObj(i -> quote(columns.get(i), context) + " AS sample_" + i).collect(java.util.stream.Collectors.joining(", "))
                        + " FROM " + quote(table, context);
                var query = context.getDatasourceId() == null ? executor.execute(compiler.compile(sql, context, 3, 0), false)
                        : sources.execute(sql, context, 3, 0, false);
                if (!query.getWarnings().isEmpty() || query.getRows().isEmpty()) continue;
                List<Map<String, Object>> rows = new ArrayList<>();
                for (var row : query.getRows().stream().limit(3).toList()) {
                    Map<String, Object> values = new LinkedHashMap<>();
                    for (int i = 0; i < columns.size(); i++) {
                        Object value = row.get("sample_" + i);
                        if (value instanceof String s) value = s.substring(0, Math.min(100, s.length())).replace('\n', ' ').replace('\r', ' ');
                        values.put(columns.get(i), value);
                    }
                    rows.add(values);
                }
                if (result.length() + JSON.toJSONString(rows).length() > 16000) break;
                result.append("\n# Table: ").append(table).append('\n').append(JSON.toJSONString(rows));
            } catch (AiAgentRequestCancelledException e) { throw e; }
              catch (RuntimeException ignored) { /* A missing sample does not prevent a query. */ }
        }
        return result.toString();
    }
    private String quote(String identifier, AiAgentContext context) {
        return context.getDatasourceId() == null || "MYSQL".equals(context.getDatasourceType())
                ? "`" + identifier.replace("`", "``") + "`" : "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
