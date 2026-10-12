package cn.cordys.crm.aiagent.service;

import cn.cordys.common.util.JSON;
import cn.cordys.crm.aiagent.config.AiAgentLlmProperties;
import cn.cordys.crm.aiagent.config.AiAgentSqlProperties;
import cn.cordys.crm.aiagent.dto.AiAgentContext;
import cn.cordys.crm.aiagent.dto.ParsedAiAgentQuestion;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class LlmAiAgentQuestionParser {
    private final AiAgentLlmProperties properties;
    private final AiAgentLlmClient llm;
    private final AiAgentSemanticSchemaService schema;
    @org.springframework.beans.factory.annotation.Autowired
    private AiAgentSqlProperties sqlProperties = new AiAgentSqlProperties();
    @org.springframework.beans.factory.annotation.Autowired
    private AiAgentQueryKnowledgeService queryKnowledgeService;
    @org.springframework.beans.factory.annotation.Autowired
    private AiAgentTableSelectionService tableSelectionService;
    @org.springframework.beans.factory.annotation.Autowired
    private AiAgentSampleDataService sampleDataService;
    @org.springframework.beans.factory.annotation.Autowired
    private AiAgentSqlBotPrompts prompts = new AiAgentSqlBotPrompts();

    public LlmAiAgentQuestionParser(AiAgentLlmProperties properties, AiAgentLlmClient llm,
            AiAgentIntentValidator ignoredValidator, AiAgentSemanticSchemaService schema,
            AiAgentParsedQuestionNormalizer ignoredNormalizer) {
        this.properties = properties; this.llm = llm; this.schema = schema;
    }
    public ParsedAiAgentQuestion parse(String question) { return parse(question, null, null); }
    public ParsedAiAgentQuestion parse(String question, String provider) { return parse(question, provider, null); }
    public ParsedAiAgentQuestion parse(String question, String provider, AiAgentContext supplied) {
        if (!properties.isEnabled() || !sqlProperties.isTextToSqlEnabled() || StringUtils.isBlank(question)) return null;
        AiAgentContext context = supplied == null ? new AiAgentContext() : supplied;
        context.setLlmProvider(provider);
        if (question.length() > Math.max(100, properties.getMaxInputLength())) return null;
        try {
            AiAgentProgress.stage("question_context", "准备本次查询问题",
                    List.of(new cn.cordys.crm.aiagent.dto.response.AiAgentChatResponse.ProcessDetail("text", "查询问题", question)));
            if (tableSelectionService != null) tableSelectionService.select(question, context);
            if (queryKnowledgeService != null) {
                AiAgentProgress.stage("knowledge", "检索业务术语与 SQL 示例");
                String keywords = StringUtils.defaultIfBlank(context.getExtractedKeywords(), question);
                context.setQueryKnowledge(queryKnowledgeService.retrieve(keywords, context.getDatasourceId(),
                        context.getOrganizationId(), context.getUserId()));
                AiAgentQueryKnowledgeService.traceMatches(context.getQueryKnowledge(), false);
            }
            if (sampleDataService != null) {
                AiAgentProgress.stage("sample_data", "读取相关表的样例数据");
                context.setSampleData(sampleDataService.read(context));
            }
            String structure = schema(context);
            String knowledge = queryKnowledgeService == null ? "" : queryKnowledgeService.prompt(context.getQueryKnowledge(), false);
            AiAgentProgress.stage("sql_generation", "生成查询 SQL");
            String content = llm.chat(prompts.sqlSystem(context, structure, knowledge),
                    prompts.sqlUser(question, context, structure), provider, context.getSqlHistory());
            if (StringUtils.isBlank(content)) return null;
            int start = content.indexOf('{'), end = content.lastIndexOf('}');
            if (start < 0 || end < start) return null;
            Map<String, Object> value = JSON.parseToMap(content.substring(start, end + 1));
            ParsedAiAgentQuestion parsed = new ParsedAiAgentQuestion();
            parsed.setRawQuestion(question); parsed.setNormalizedQuestion(question); parsed.setSource("LLM");
            parsed.setIntent("CRM_DATABASE_QUERY");
            parsed.setCandidateSql(Objects.toString(value.get("sql"), null));
            parsed.setChartType(Objects.toString(value.get("chart-type"), "table"));
            parsed.setBrief(Objects.toString(value.get("brief"), null));
            parsed.setSqlRequired(StringUtils.isNotBlank(parsed.getCandidateSql()));
            parsed.setNeedClarification(!Boolean.TRUE.equals(value.get("success")) || !parsed.isSqlRequired());
            parsed.setClarificationQuestion(Objects.toString(value.get("message"),
                    "当前表结构无法回答这个问题。"));
            if (!parsed.isNeedClarification()) AiAgentProgress.stage("sql_generated", "已生成 SQL，等待校验和执行",
                    List.of(new cn.cordys.crm.aiagent.dto.response.AiAgentChatResponse.ProcessDetail("sql", "生成的 SQL", parsed.getCandidateSql())));
            return parsed;
        } catch (AiAgentRequestCancelledException e) { throw e; }
          catch (RuntimeException e) {
            AiAgentProgress.stage("sql_generation_failed", "SQL 生成失败，可重新生成");
            return null;
        }
    }

    String schema(AiAgentContext context) {
        Set<String> selected = new LinkedHashSet<>(context.getSelectedQueryTables());
        String structure = context.getDatasourceId() == null ? schema.sqlSchemaPrompt(selected)
                : JSON.toJSONString(context.getDatasourceSchema().stream()
                        .filter(t -> selected.isEmpty() || selected.contains(t.getName())).toList());
        return structure + "\n表关系：" + JSON.toJSONString(context.getDatasourceRelations());
    }
}
