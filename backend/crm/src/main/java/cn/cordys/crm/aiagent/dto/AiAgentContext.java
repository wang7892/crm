package cn.cordys.crm.aiagent.dto;

import cn.cordys.common.dto.DeptDataPermissionDTO;
import cn.cordys.crm.aiagent.dto.response.AiKnowledgeSearchTestResponse;
import cn.cordys.crm.aiagent.dto.semantic.AiAgentSemanticRuleMatch;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

@Data
public class AiAgentContext {
    private cn.cordys.crm.aiagent.dto.knowledge.AiAgentQueryKnowledge.Matches queryKnowledge =
            cn.cordys.crm.aiagent.dto.knowledge.AiAgentQueryKnowledge.Matches.empty();
    private String userId;
    private String organizationId;
    private String datasourceId;
    private long datasourceRevision;
    private String datasourceType;
    private String datasourceName;
    private String datasourceNamespace;
    private List<cn.cordys.crm.aiagent.dto.datasource.AiAgentDatasource.Table> datasourceSchema = new ArrayList<>();
    private List<cn.cordys.crm.aiagent.dto.datasource.AiAgentDatasource.Relation> datasourceRelations = new ArrayList<>();
    private List<String> selectedQueryTables = new ArrayList<>();
    private String tableSelectionSummary;
    private DeptDataPermissionDTO dataPermission;
    private DeptDataPermissionDTO customerDataPermission;
    private DeptDataPermissionDTO contractDataPermission;
    private DeptDataPermissionDTO orderDataPermission;
    private String dataScope;
    private String llmProvider;
    private String queryQuestion;
    private List<AiAgentConversationMessage> sqlHistory = new ArrayList<>();
    private String extractedKeywords;
    private String sampleData;
    private String requestedChartType;
    private String queryError;
    private cn.cordys.crm.aiagent.dto.query.AiAgentQueryPlan executedQueryPlan;
    private AiAgentQueryContinuation continuation;
    private boolean continuingQuery;
    private boolean limitRows = true;
    private AiAgentTimeWindow timeWindow;
    private String timeRangeError;
    private boolean llmParseAttempted;
    private ParsedAiAgentQuestion llmParsedQuestion;
    private ParsedAiAgentQuestion originalParsedQuestion;
    private String queryTraceId = java.util.UUID.randomUUID().toString();
    private AiKnowledgeSearchTestResponse knowledgeSearch;
    private List<AiAgentSemanticRuleMatch> semanticRuleMatches = new ArrayList<>();
    private boolean semanticRuleConflict;
    private String semanticRuleFallbackReason;
    private int analysisRowLimit = 100;
    private Map<String, Object> analysisData = new LinkedHashMap<>();
}
