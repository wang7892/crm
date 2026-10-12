package cn.cordys.crm.aiagent.service;

import cn.cordys.common.util.JSON;
import cn.cordys.crm.aiagent.dto.AiAgentContext;
import cn.cordys.crm.aiagent.dto.datasource.AiAgentDatasource.*;
import cn.cordys.crm.aiagent.dto.response.AiAgentChatResponse.ProcessDetail;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AiAgentTableSelectionService {
    private final AiAgentLlmClient llm;
    private final AiAgentSemanticSchemaService schema;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AiAgentQueryKnowledgeEmbedding embedding;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AiAgentQueryKnowledgeService knowledge;
    private final Map<String, List<Double>> vectors = new ConcurrentHashMap<>();
    private final AiAgentSqlBotPrompts prompts = new AiAgentSqlBotPrompts();
    public AiAgentTableSelectionService(AiAgentLlmClient llm, AiAgentSemanticSchemaService schema) {
        this.llm = llm; this.schema = schema;
    }

    public void select(String question, AiAgentContext context) {
        AiAgentProgress.stage("extract_keywords", "提取查询关键词");
        String keywords = question;
        boolean extractedSuccessfully = false;
        try {
            String extracted = llm.chatTextQuiet(prompts.task("sql", "extract_keywords",
                    Map.of("sqlbot_name", "问数助手")), question, context.getLlmProvider(),
                    context.getSqlHistory().stream().filter(m -> "user".equals(m.role())).toList());
            if (extracted != null && !extracted.isBlank() && !extracted.startsWith("ERROR:")
                    && !"EMPTY".equals(extracted.trim())) {
                keywords = extracted.trim();
                extractedSuccessfully = true;
            }
        } catch (AiAgentRequestCancelledException e) { throw e; }
          catch (RuntimeException ignored) { }
        context.setExtractedKeywords(keywords);
        if (!extractedSuccessfully) AiAgentProgress.failCurrent();
        AiAgentProgress.completeCurrent(extractedSuccessfully ? "已提取查询关键词" : "未提取到有效关键词，使用原问题检索",
                List.of(new ProcessDetail("text", extractedSuccessfully ? "提取结果" : "检索输入（使用原问题）", keywords)));
        if (knowledge != null) keywords = knowledge.expandKeywords(keywords, context.getOrganizationId());
        List<Table> tables = context.getDatasourceId() == null ? builtIn() : context.getDatasourceSchema();
        List<Relation> relations = context.getDatasourceId() == null ? builtInRelations() : context.getDatasourceRelations();
        context.setDatasourceRelations(List.copyOf(relations));
        AiAgentProgress.stage("table_selection", "按关键词与表结构相似度选择相关表");
        List<Double> query = embedding == null ? List.of() : embedding.embed(keywords, context.getOrganizationId());
        String embeddingKey = embedding == null ? null : embedding.key(context.getOrganizationId());
        if (vectors.size() > 1000) vectors.clear();
        Map<String, String> vectorKeys = new LinkedHashMap<>();
        for (Table table : tables) vectorKeys.put(table.getName(), context.getOrganizationId() + ":" + embeddingKey + ":"
                + org.apache.commons.codec.digest.DigestUtils.sha256Hex(JSON.toJSONString(table)));
        if (!query.isEmpty() && embeddingKey != null) {
            var missing = tables.stream().filter(t -> !vectors.containsKey(vectorKeys.get(t.getName()))).toList();
            for (int i = 0; i < missing.size(); i += 32) {
                var batch = missing.subList(i, Math.min(i + 32, missing.size()));
                var indexed = embedding.embedBatch(batch.stream().map(JSON::toJSONString).toList(), context.getOrganizationId());
                if (indexed.size() != batch.size()) break;
                for (int j = 0; j < batch.size(); j++) if (!indexed.get(j).isEmpty()) vectors.put(vectorKeys.get(batch.get(j).getName()), indexed.get(j));
            }
        }
        Map<String, Double> scores = new LinkedHashMap<>();
        String expanded = keywords;
        for (Table table : tables) {
            double keyword = keywordScore(expanded, table);
            List<Double> vector = List.of();
            if (!query.isEmpty() && embeddingKey != null) {
                vector = vectors.getOrDefault(vectorKeys.get(table.getName()), List.of());
            }
            double similarity = vector.isEmpty() ? 0 : AiAgentQueryKnowledgeEmbedding.similarity(query, vector);
            scores.put(table.getName(), 0.4 * similarity + 0.6 * keyword);
        }
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        // SQLBot retains explicitly named tables before filling the ten recall slots.
        for (Table table : tables) if (explicit(question, table.getName()) || explicit(question, table.getComment()))
            selected.add(table.getName());
        for (Table table : tables) if (keywordScore(keywords, table) == 1) selected.add(table.getName());
        if (query.isEmpty() && scores.values().stream().noneMatch(s -> s > 0)) {
            tables.forEach(t -> selected.add(t.getName()));
        } else {
            tables.stream().filter(t -> !selected.contains(t.getName())).sorted(Comparator.comparingDouble((Table t) -> scores.get(t.getName())).reversed())
                    .limit(Math.max(0, 10 - selected.size())).forEach(t -> selected.add(t.getName()));
        }
        expandPaths(selected, relations, new LinkedHashSet<>(tables.stream().map(Table::getName).toList()));
        context.setSelectedQueryTables(List.copyOf(selected));
        context.setTableSelectionSummary("相关表：" + String.join("、", selected));
        var details = new ArrayList<cn.cordys.crm.aiagent.dto.response.AiAgentChatResponse.ProcessDetail>();
        if (!keywords.equals(context.getExtractedKeywords()))
            details.add(new cn.cordys.crm.aiagent.dto.response.AiAgentChatResponse.ProcessDetail("text", "扩展关键词", keywords));
        for (Table table : tables) if (selected.contains(table.getName()))
            details.add(new cn.cordys.crm.aiagent.dto.response.AiAgentChatResponse.ProcessDetail("table", table.getName(), JSON.toJSONString(table)));
        AiAgentProgress.stage("table_selection_result", context.getTableSelectionSummary(), details);
    }

    static boolean explicit(String question, String text) {
        if (text == null || text.isBlank()) return false;
        return question.toLowerCase(Locale.ROOT).contains(text.toLowerCase(Locale.ROOT));
    }

    static double keywordScore(String keywords, Table table) {
        List<String> words = Arrays.stream(keywords.toLowerCase(Locale.ROOT).split("[,，]")).map(String::trim).filter(s -> !s.isBlank()).toList();
        if (words.isEmpty()) return 0;
        String name = table.getName().toLowerCase(Locale.ROOT);
        String comment = Objects.toString(table.getComment(), "").toLowerCase(Locale.ROOT);
        var nameParts = new HashSet<>(Arrays.asList(name.split("[_\\s]+"))); nameParts.remove("");
        var scores = new HashMap<String, Double>();
        for (String word : words) {
            if (word.equals(name)) return 1;
            if (word.equals(comment) && !comment.isEmpty()) return 0.9;
            if (comment.contains(word)) { scores.put(word, 0.7); continue; }
            double score = 0;
            if (nameParts.contains(word)) scores.put(word, 0d);
            for (Column field : table.getColumns()) {
                String fieldComment = Objects.toString(field.getComment(), "").toLowerCase(Locale.ROOT);
                if (word.equals(fieldComment)) score = Math.max(score, 0.5);
                else if (fieldComment.contains(word)) score = Math.max(score, 0.4);
                if (Arrays.asList(field.getName().toLowerCase(Locale.ROOT).split("[_\\s]+")).contains(word)) score = Math.max(score, 0.3);
            }
            if (score > 0) scores.put(word, score);
        }
        var matchingParts = new HashSet<>(words); matchingParts.retainAll(nameParts);
        if (!matchingParts.isEmpty()) {
            double score = Math.round((0.25 + 0.25 * matchingParts.size() / nameParts.size()) * 100) / 100d;
            matchingParts.forEach(word -> scores.merge(word, score, Math::max));
        }
        double best = scores.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
        return Math.round(best * scores.size() / words.size() * 100) / 100d;
    }

    List<Table> builtIn() {
        return schema.entities().values().stream().map(e -> {
            Table t = new Table(); t.setName(e.name()); t.setComment(e.label() + " [" + e.dataSourceKind() + "]");
            t.setColumns(e.fields().values().stream().filter(AiAgentSemanticSchemaService.FieldSpec::selectable).map(f -> {
                Column c = new Column(); c.setName(f.key()); c.setType(f.valueKind().name());
                c.setComment(f.label() + " " + String.join(" ", f.aliases()) + " " + Objects.requireNonNullElse(f.description(), ""));
                return c;
            }).toList()); return t;
        }).toList();
    }

    List<Relation> builtInRelations() {
        List<Relation> relations = new ArrayList<>();
        for (var columns : List.of(
                List.of("customer", "owner", "sys_user", "id"),
                List.of("contract", "customer_id", "customer", "id"),
                List.of("contract", "owner", "sys_user", "id"),
                List.of("sales_order", "customer_id", "customer", "id"),
                List.of("follow_up_record", "customer_id", "customer", "id"),
                List.of("follow_up_record", "owner", "sys_user", "id"),
                List.of("wecom_ingestion_media", "message_id", "wecom_ingestion_message", "id"),
                List.of("wecom_ingestion_message_follow_record", "message_id", "wecom_ingestion_message", "id"),
                List.of("email_webhook_attachment", "event_id", "email_webhook_event", "id"),
                List.of("order_timeline", "order_no", "contract_info", "order_no"))) {
            var source = schema.entities().get(columns.get(0));
            var target = schema.entities().get(columns.get(2));
            if (source == null || target == null || source.dataSourceKind() != target.dataSourceKind()
                    || !source.fields().containsKey(columns.get(1)) || !target.fields().containsKey(columns.get(3))) continue;
            Relation r = new Relation(); r.setSourceTable(source.name()); r.setSourceColumn(columns.get(1));
            r.setTargetTable(target.name()); r.setTargetColumn(columns.get(3)); relations.add(r);
        }
        return relations;
    }

    static void expandPaths(Set<String> selected, List<Relation> relations, Set<String> allowed) {
        Map<String, Set<String>> graph = new LinkedHashMap<>();
        for (Relation r : relations) {
            if (!allowed.contains(r.getSourceTable()) || !allowed.contains(r.getTargetTable())) continue;
            graph.computeIfAbsent(r.getSourceTable(), k -> new LinkedHashSet<>()).add(r.getTargetTable());
            graph.computeIfAbsent(r.getTargetTable(), k -> new LinkedHashSet<>()).add(r.getSourceTable());
        }
        List<String> seeds = List.copyOf(selected);
        for (String start : seeds) for (String target : seeds) {
            Map<String, String> parent = new HashMap<>(); parent.put(start, null);
            ArrayDeque<String> queue = new ArrayDeque<>(); queue.add(start);
            while (!queue.isEmpty() && !parent.containsKey(target)) {
                String next = queue.remove();
                for (String neighbor : graph.getOrDefault(next, Set.of())) if (!parent.containsKey(neighbor)) {
                    parent.put(neighbor, next); queue.add(neighbor);
                }
            }
            if (parent.containsKey(target)) for (String node = target; node != null; node = parent.get(node)) selected.add(node);
        }
    }
}
