package cn.cordys.crm.aiagent.service;

import cn.cordys.crm.aiagent.dto.AiAgentContext;
import cn.cordys.crm.aiagent.dto.query.AiAgentQueryPlan;
import cn.cordys.crm.aiagent.dto.query.AiAgentQueryResult;
import cn.cordys.crm.aiagent.dto.response.AiAgentChatResponse;
import cn.cordys.crm.aiagent.dto.response.AiAgentToolCallDTO;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

@Service
public class AiAgentDatabaseQueryService {

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
            return answerRenderer.render(query(queryPlan, context));
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
        return queryExecutor.execute(builtQuery);
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
