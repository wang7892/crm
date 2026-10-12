package cn.cordys.crm.aiagent.service;

import cn.cordys.common.util.JSON;
import cn.cordys.crm.aiagent.dto.AiAgentContext;
import cn.cordys.crm.aiagent.dto.response.*;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import java.util.*;

/** SQLBot's separate chart, analysis, prediction and question suggestion tasks. */
@Service
public class AiAgentSqlBotResultService {
    private final AiAgentLlmClient llm;
    private final AiAgentSqlBotPrompts prompts;
    private final AiAgentChartService charts;
    private final LlmAiAgentQuestionParser parser;
    private final AiAgentQueryKnowledgeService knowledge;

    public AiAgentSqlBotResultService(AiAgentLlmClient llm, AiAgentSqlBotPrompts prompts,
                                     AiAgentChartService charts, LlmAiAgentQuestionParser parser,
                                     AiAgentQueryKnowledgeService knowledge) {
        this.llm = llm; this.prompts = prompts; this.charts = charts; this.parser = parser; this.knowledge = knowledge;
    }

    public void decorate(AiAgentChatResponse response, AiAgentContext context) {
        if (response.getResultTable() == null || response.getSqlQueries().isEmpty()) return;
        Map<String, Object> values = values(response, context);
        try {
            AiAgentProgress.stage("chart", "生成图表字段映射");
            Map<String, Object> config = JSON.parseToMap(cleanJson(llm.chat(
                    prompts.task("chart", "system", values) + prompts.task("chart", "generate_rules", values),
                    prompts.task("chart", "user", values), context.getLlmProvider())));
            response.setChartConfig(config);
            applyLabels(response.getResultTable(), config);
            response.setCharts(charts.build(response.getResultTable(), config,
                    response.getPageInfo() != null && response.getPageInfo().isHasMore()));
        } catch (AiAgentRequestCancelledException e) { throw e; }
          catch (RuntimeException e) { AiAgentProgress.failCurrent(); response.getWarnings().add("图表配置生成失败，查询数据仍可查看。"); }
        // SQLBot finishes the answer before its separate recommendation request.
        AiAgentLlmUsage.finishAnswer();
        try {
            AiAgentProgress.stage("guess", "生成推荐追问");
            values.put("articles_number", 4);
            values.put("old_questions", String.join("\n", context.getSqlHistory().stream()
                    .filter(m -> "user".equals(m.role())).map(m -> m.content()).toList()));
            String json = llm.chatTextQuiet(prompts.task("guess", "system", values), prompts.task("guess", "user", values),
                    context.getLlmProvider(), List.of());
            List<?> guesses = JSON.parseObject(cleanJson(json), List.class);
            response.setRecommendedQuestions(guesses.stream().filter(String.class::isInstance).map(String.class::cast)
                    .map(String::trim).filter(s -> !s.isBlank() && !s.equals(context.getQueryQuestion())).distinct().limit(4).toList());
        } catch (AiAgentRequestCancelledException e) { throw e; }
          catch (RuntimeException ignored) { AiAgentProgress.failCurrent(); /* An optional suggestion must not discard the query. */ }
    }

    public AiAgentChatResponse analyze(AiAgentChatResponse previous, AiAgentContext context, boolean predict) {
        if (previous.getResultTable() == null || previous.getResultTable().getRows().isEmpty())
            throw new IllegalArgumentException("该查询没有可分析的数据，请先查询。");
        String task = predict ? "predict" : "analysis";
        AiAgentProgress.stage(task, predict ? "预测查询数据" : "分析查询数据");
        var response = new AiAgentChatResponse();
        response.setQueryQuestion(previous.getQueryQuestion());
        response.setSqlQueries(new ArrayList<>(previous.getSqlQueries()));
        response.setIntent(predict ? "CRM_DATA_PREDICTION" : "CRM_DATA_ANALYSIS");
        Map<String, Object> values = values(previous, context);
        // A referenced result is the sole dataset: do not generate or execute supplementary queries.
        var matches = knowledge.retrieve(previous.getQueryQuestion(), context.getDatasourceId(), context.getOrganizationId(), context.getUserId());
        values.put("terminologies", knowledge.prompt(matches, true));
        String answer = predict ? llm.chatTextQuiet(prompts.task(task, "system", values), prompts.task(task, "user", values), context.getLlmProvider(), List.of())
                : llm.chatText(prompts.task(task, "system", values), prompts.task(task, "user", values), context.getLlmProvider());
        if (StringUtils.isBlank(answer)) throw new IllegalArgumentException("模型未返回" + (predict ? "预测" : "分析") + "结果。");
        if (!predict) { response.setAnswer(answer.trim()); return response; }
        try {
            List<?> data = JSON.parseObject(cleanJson(answer), List.class);
            if (data.size() < 2 || data.size() > 1000) throw new IllegalArgumentException("预测周期数量无效");
            var table = new AiAgentResultTable();
            table.setColumns(previous.getResultTable().getColumns());
            Set<String> keys = new LinkedHashSet<>(table.getColumns().stream().map(AiAgentResultTable.Column::key).toList());
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Object item : data) {
                if (!(item instanceof Map<?, ?> row) || !row.keySet().equals(keys)) throw new IllegalArgumentException("预测字段无效");
                var copy = new LinkedHashMap<String, Object>();
                for (var col : table.getColumns()) {
                    Object value = row.get(col.key());
                    if (value instanceof Map<?, ?> || value instanceof List<?>) throw new IllegalArgumentException("预测值无效");
                    if ("number".equals(col.type()) && value != null && !(value instanceof Number)) throw new IllegalArgumentException("预测数值无效");
                    copy.put(col.key(), value);
                }
                rows.add(copy);
            }
            table.setRows(rows); response.setPredictionTable(table);
            response.setChartConfig(previous.getChartConfig());
            var combined = new AiAgentResultTable(); combined.setColumns(table.getColumns());
            combined.setRows(new ArrayList<>(previous.getResultTable().getRows())); combined.getRows().addAll(rows);
            var config = new LinkedHashMap<>(previous.getChartConfig());
            if (!config.isEmpty() && !"table".equals(config.get("type"))) config.put("type", "line");
            response.setCharts(charts.build(combined, config, false));
            response.getCharts().forEach(c -> {
                Map<?, ?> axis = config.get("axis") instanceof Map<?, ?> m ? m : Map.of();
                Map<?, ?> x = axis.get("x") instanceof Map<?, ?> m ? m : Map.of();
                String key = Objects.toString(x.get("value"), "");
                c.setPredictionStart((int) previous.getResultTable().getRows().stream().map(r -> r.get(key)).distinct().count());
                c.setCoverage("历史数据与模型预测");
            });
            response.setAnswer("已生成 " + rows.size() + " 个周期的预测数据。");
        } catch (RuntimeException e) {
            AiAgentProgress.failCurrent();
            response.setAnswer(answer.trim());
            response.setPredictionTable(null);
            response.getWarnings().add("未取得可展示的预测数据。");
        }
        return response;
    }

    private Map<String, Object> values(AiAgentChatResponse response, AiAgentContext context) {
        var values = new HashMap<String, Object>();
        values.put("lang", "中文"); values.put("sqlbot_name", "问数助手");
        values.put("custom_prompt", ""); values.put("terminologies", "");
        values.put("question", Objects.toString(context.getQueryQuestion(), ""));
        values.put("sql", String.join("\n", response.getSqlQueries()));
        values.put("schema", parser.schema(context));
        values.put("chart_type", Objects.toString(context.getRequestedChartType(), ""));
        values.put("fields", JSON.toJSONString(response.getResultTable().getColumns()));
        values.put("data", JSON.toJSONString(response.getResultTable().getRows()));
        return values;
    }

    private void applyLabels(AiAgentResultTable table, Map<String, Object> config) {
        var labels = new HashMap<String, String>();
        if (config.get("columns") instanceof List<?> cols) cols.forEach(c -> addLabel(labels, c));
        if (config.get("axis") instanceof Map<?, ?> axis) axis.values().forEach(v -> {
            if (v instanceof List<?> list) list.forEach(c -> addLabel(labels, c)); else addLabel(labels, v);
        });
        table.setColumns(table.getColumns().stream().map(c -> new AiAgentResultTable.Column(c.key(), labels.getOrDefault(c.key(), c.label()), c.type())).toList());
    }
    private void addLabel(Map<String, String> labels, Object value) {
        if (value instanceof Map<?, ?> m && m.get("value") instanceof String key && m.get("name") instanceof String name)
            labels.put(key, name);
    }
    static String cleanJson(String value) {
        if (value == null) return "";
        String text = value.trim().replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "").trim();
        int array = text.indexOf('['), object = text.indexOf('{');
        int start = array < 0 ? object : object < 0 ? array : Math.min(array, object);
        if (start < 0) return text;
        int end = text.lastIndexOf(text.charAt(start) == '[' ? ']' : '}');
        return end < start ? text : text.substring(start, end + 1);
    }
}
