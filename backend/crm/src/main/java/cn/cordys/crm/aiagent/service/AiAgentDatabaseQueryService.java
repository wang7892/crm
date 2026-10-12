package cn.cordys.crm.aiagent.service;

import cn.cordys.crm.aiagent.dto.AiAgentContext;
import cn.cordys.crm.aiagent.dto.AiAgentQueryContinuation;
import cn.cordys.crm.aiagent.dto.ParsedAiAgentQuestion;
import cn.cordys.common.util.JSON;
import cn.cordys.crm.aiagent.dto.query.AiAgentQueryPlan;
import cn.cordys.crm.aiagent.dto.query.AiAgentQueryResult;
import cn.cordys.crm.aiagent.dto.response.AiAgentChatResponse;
import cn.cordys.crm.aiagent.dto.response.AiAgentToolCallDTO;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

@Service
public class AiAgentDatabaseQueryService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AiAgentDatabaseQueryService.class);

    @jakarta.annotation.Resource
    private AiAgentQueryContractService queryContractService;

    private final AiAgentQueryPlanner queryPlanner;
    private final AiAgentSqlBuilder sqlBuilder;
    private final AiAgentQueryExecutor queryExecutor;
    private final AiAgentAnswerRenderer answerRenderer;

    public AiAgentDatabaseQueryService(AiAgentQueryPlanner queryPlanner,
                                       AiAgentSqlBuilder sqlBuilder,
                                       AiAgentQueryExecutor queryExecutor,
                                       AiAgentAnswerRenderer answerRenderer) {
        this.queryPlanner = queryPlanner;
        this.sqlBuilder = sqlBuilder;
        this.queryExecutor = queryExecutor;
        this.answerRenderer = answerRenderer;
    }

    public AiAgentChatResponse answer(AiAgentQueryPlan queryPlan, AiAgentContext context) {
        if (queryPlan == null) {
            return null;
        }
        if (Boolean.TRUE.equals(queryPlan.getNeedClarification())) {
            return clarification(queryPlan);
        }
        try {
            if (context != null && StringUtils.isNotBlank(context.getTimeRangeError())) {
                throw new IllegalArgumentException(context.getTimeRangeError());
            }
            if (context != null && !context.isContinuingQuery()) {
                queryPlan.setOffset(0L);
                if (StringUtils.isNotBlank(context.getQueryQuestion())) {
                    queryPlan.setLimit(AiAgentResultWindow.requestedSize(context.getQueryQuestion(), context.isLimitRows()));
                }
            }
            // Apply the current display cap to saved pages as well as newly generated plans.
            queryPlan.setLimit(AiAgentResultWindow.normalizeSize(queryPlan.getLimit(), context == null || context.isLimitRows()));
            if (context != null && (context.getOriginalParsedQuestion() != null
                    || AiAgentRankingPolicy.salesAmount(context.getQueryQuestion())
                    || (!"LIST".equalsIgnoreCase(queryPlan.getQueryType()) && AiAgentRankingPolicy.sorting(context.getQueryQuestion())))) {
                ParsedAiAgentQuestion request = new ParsedAiAgentQuestion();
                request.setRawQuestion(context.getOriginalParsedQuestion() == null ? context.getQueryQuestion()
                        : context.getOriginalParsedQuestion().getRawQuestion());
                request.setQueryPlan(queryPlan);
                var contract = queryContractService == null ? new AiAgentQueryContractService(new AiAgentSemanticSchemaService()) : queryContractService;
                contract.prepare(request, context.getOriginalParsedQuestion(), context);
            }
            if (queryPlan.getTopN() != null && context != null && !context.isContinuingQuery()) {
                queryPlan.setLimit(context.isLimitRows() ? AiAgentResultWindow.DEFAULT_SIZE : AiAgentResultWindow.UNLIMITED);
            }
            AiAgentQueryResult result = query(queryPlan, context);
            if (context != null && result.getWarnings().isEmpty()) {
                context.setAnalysisData(answerRenderer.analysisData(result, context.getAnalysisRowLimit()));
                context.setExecutedQueryPlan(result.getPlan());
            }
            AiAgentChatResponse response = answerRenderer.render(result);
            if (context != null && response.getPageInfo() != null && response.getPageInfo().isContinuable()) {
                AiAgentQueryContinuation continuation = new AiAgentQueryContinuation();
                continuation.setPlan(result.getPlan());
                continuation.setTimeWindow(context.getTimeWindow());
                continuation.setQuestion(context.getQueryQuestion());
                continuation.setNextOffset(response.getPageInfo().getOffset() + response.getPageInfo().getReturned());
                context.setContinuation(continuation);
            }
            return response;
        } catch (IllegalArgumentException e) {
            AiAgentChatResponse response = new AiAgentChatResponse();
            response.setIntent("CRM_DATABASE_QUERY_REJECTED");
            response.setAnswer("这个问题暂时不能通过通用数据库查询回答：" + e.getMessage() + "。");
            response.getWarnings().add("通用查询只允许访问已配置的表、字段和操作符。");
            response.getTools().add(tool("crm_database_query_planner", "REJECTED", e.getMessage()));
            return response;
        }
    }

    public AiAgentQueryResult query(AiAgentQueryPlan queryPlan, AiAgentContext context) {
        AiAgentQueryPlanner.PlannedQuery plannedQuery = queryPlanner.plan(queryPlan);
        AiAgentSqlBuilder.BuiltQuery builtQuery = sqlBuilder.build(plannedQuery, context);
        String trace = context == null ? "internal" : context.getQueryTraceId();
        java.util.Map<String, Object> parameters = new java.util.LinkedHashMap<>(builtQuery.params());
        for (int i = 0; i < plannedQuery.queryPlan().getFilters().size(); i++) {
            var filter = plannedQuery.queryPlan().getFilters().get(i);
            if (plannedQuery.entity().findField(filter.getField()).orElseThrow().sensitive()) {
                parameters.put("filter" + i, "***");
            }
        }
        log.debug("AI query trace={} stage=execute entity={} type={} fields={} groupBy={} sql={} params={}",
                trace, plannedQuery.entity().name(), plannedQuery.queryPlan().getQueryType(),
                plannedQuery.queryPlan().getSelectFields(), plannedQuery.queryPlan().getGroupBy(),
                builtQuery.sql(), JSON.toJSONString(parameters));
        AiAgentQueryResult result = queryExecutor.execute(builtQuery);
        result.setTraceId(trace);
        result.setTimeWindowLabel(context == null || context.getTimeWindow() == null ? null : context.getTimeWindow().label());
        log.debug("AI query trace={} stage=result rows={} warnings={} durationMs={}",
                trace, result.getRows().size(), result.getWarnings(), result.getDurationMs());
        return result;
    }

    private AiAgentChatResponse clarification(AiAgentQueryPlan queryPlan) {
        AiAgentChatResponse response = new AiAgentChatResponse();
        response.setIntent("QUESTION_CLARIFICATION_REQUIRED");
        response.setAnswer(StringUtils.defaultIfBlank(queryPlan.getClarificationQuestion(),
                "这个问题还缺少关键条件，请补充要查询的表、字段、时间范围或筛选条件。"));
        response.getTools().add(tool("crm_database_query_clarification", "SUCCESS", "通用查询计划需要补充条件"));
        return response;
    }

    private AiAgentToolCallDTO tool(String name, String status, String summary) {
        AiAgentToolCallDTO tool = new AiAgentToolCallDTO();
        tool.setName(name);
        tool.setStatus(status);
        tool.setSummary(summary);
        tool.setDurationMs(0L);
        tool.setEvidenceId("ev_" + name);
        return tool;
    }
}
