package cn.cordys.crm.aiagent.service;

import cn.cordys.crm.aiagent.config.AiAgentSqlProperties;
import cn.cordys.crm.aiagent.dto.AiAgentContext;
import cn.cordys.crm.aiagent.dto.AiAgentQueryContinuation;
import cn.cordys.crm.aiagent.dto.response.AiAgentChatResponse;
import org.springframework.stereotype.Service;

@Service
public class AiAgentSqlQueryService {

    private final AiAgentSqlProperties properties;
    private final AiAgentTextSqlCompiler compiler;
    private final AiAgentQueryExecutor executor;
    private final AiAgentAnswerRenderer renderer;
    @jakarta.annotation.Resource
    private AiAgentDatasourceQueryService datasourceQueryService;
    @jakarta.annotation.Resource
    private AiAgentSqlBotResultService sqlBotResults;

    public AiAgentSqlQueryService(AiAgentSqlProperties properties, AiAgentTextSqlCompiler compiler,
                                 AiAgentQueryExecutor executor, AiAgentAnswerRenderer renderer) {
        this.properties = properties;
        this.compiler = compiler;
        this.executor = executor;
        this.renderer = renderer;
    }

    public boolean isTextToSqlEnabled() {
        return properties.isTextToSqlEnabled();
    }

    public AiAgentChatResponse answer(String generatedSql, AiAgentContext context) {
        return answer(generatedSql, context, AiAgentResultWindow.requestedSize(context == null ? null : context.getQueryQuestion(),
                context == null || context.isLimitRows()), 0L);
    }

    public AiAgentChatResponse answer(String generatedSql, AiAgentContext context, int pageSize, long offset) {
        if (!properties.isEnabled() || !properties.isTextToSqlEnabled()) {
            throw new IllegalArgumentException("大模型 SQL 查询未开启");
        }
        if (context == null || org.apache.commons.lang3.StringUtils.isAnyBlank(context.getOrganizationId(), context.getUserId())) {
            throw new IllegalArgumentException("当前用户和组织权限不可用");
        }
        context.setContinuation(null);
        context.setExecutedQueryPlan(null);
        context.setAnalysisData(java.util.Map.of());
        boolean custom = context.getDatasourceId() != null;
        AiAgentProgress.stage("sql_query", "校验只读 SQL、访问范围并执行查询");
        var result = custom ? datasourceQueryService.execute(generatedSql, context, pageSize, offset)
                : executor.execute(compiler.compile(generatedSql, context, pageSize, offset));
        result.setToolName("text_to_sql_query");
        result.setTraceId(context.getQueryTraceId());
        result.setTimeWindowLabel(context.getTimeWindow() == null ? null : context.getTimeWindow().label());
        if (!result.getWarnings().isEmpty()) {
            throw new IllegalArgumentException(String.join("；", result.getWarnings()));
        }
        context.setAnalysisData(renderer.analysisData(result, context.getAnalysisRowLimit()));
        // Display metadata is not an executable legacy query plan.
        var response = renderer.render(result);
        String counts = "本次返回：" + result.getRows().size() + " 条";
        if (response.getPageInfo() != null) {
            var page = response.getPageInfo();
            counts += "\n已读取位置：" + page.getOffset();
            if (page.getTotal() != null) counts += "\n匹配总数：" + page.getTotal() + " " + page.getUnit();
            counts += page.isHasMore() ? "\n仍有后续结果" : "\n本次查询结果已全部返回";
        }
        AiAgentProgress.stage("query_result", "只读与访问范围校验通过，查询完成", java.util.List.of(
                new AiAgentChatResponse.ProcessDetail("sql", "执行成功的模型 SQL", generatedSql),
                new AiAgentChatResponse.ProcessDetail("text", "执行结果", counts)));
        // Expose the successful model SQL, never the permission rewrite or its bindings.
        response.getSqlQueries().add(generatedSql);
        response.setQueryQuestion(context.getQueryQuestion());
        response.getEvidence().add("llm_generated_sql");
        if (response.getPageInfo() != null && response.getPageInfo().isContinuable()) {
            var continuation = new AiAgentQueryContinuation();
            continuation.setGeneratedSql(generatedSql);
            continuation.setPageSize(result.getPlan().getLimit());
            continuation.setTimeWindow(context.getTimeWindow());
            continuation.setQuestion(context.getQueryQuestion());
            continuation.setNextOffset(offset + result.getRows().size());
            context.setContinuation(continuation);
        }
        if (sqlBotResults != null) sqlBotResults.decorate(response, context);
        return response;
    }
}
