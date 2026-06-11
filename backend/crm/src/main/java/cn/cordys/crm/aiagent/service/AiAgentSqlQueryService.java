package cn.cordys.crm.aiagent.service;

import cn.cordys.crm.aiagent.dto.response.AiAgentChatResponse;
import org.springframework.stereotype.Service;

@Service
public class AiAgentSqlQueryService {

    private final AiAgentSqlGuard sqlGuard;

    public AiAgentSqlQueryService(AiAgentSqlGuard sqlGuard) {
        this.sqlGuard = sqlGuard;
    }

    public AiAgentChatResponse explainCandidateSql(String candidateSql) {
        AiAgentSqlGuard.SqlGuardResult guardResult = sqlGuard.validate(candidateSql);
        AiAgentChatResponse response = new AiAgentChatResponse();
        response.setIntent("CONTROLLED_SQL_QUERY");
        response.getEvidence().add("controlled_sql");
        if (!guardResult.allowed()) {
            response.setAnswer("这个问题需要通过受控 SQL 查询数据库，但当前不能执行：" + guardResult.reason() + "。");
            response.getWarnings().add("候选 SQL 已被后端安全校验拦截，未访问数据库。");
            response.getTools().add(tool("controlled_sql_guard", "REJECTED", guardResult.reason()));
            return response;
        }
        response.setAnswer("这个问题已生成候选只读 SQL，但当前版本尚未开放 SQL 执行。已通过基础 SQL 安全校验，后续需要接入权限注入和只读账号执行。");
        response.getPoints().add("候选 SQL：" + guardResult.sql());
        response.getTools().add(tool("controlled_sql_guard", "SKIPPED", "SQL 基础校验通过，执行能力未开放"));
        return response;
    }

    private cn.cordys.crm.aiagent.dto.response.AiAgentToolCallDTO tool(String name, String status, String summary) {
        cn.cordys.crm.aiagent.dto.response.AiAgentToolCallDTO tool = new cn.cordys.crm.aiagent.dto.response.AiAgentToolCallDTO();
        tool.setName(name);
        tool.setStatus(status);
        tool.setSummary(summary);
        tool.setDurationMs(0L);
        tool.setEvidenceId("ev_" + name);
        return tool;
    }
}
