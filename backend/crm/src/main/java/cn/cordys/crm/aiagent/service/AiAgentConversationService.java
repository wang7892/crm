package cn.cordys.crm.aiagent.service;

import cn.cordys.common.dto.DeptDataPermissionDTO;
import cn.cordys.common.util.JSON;
import cn.cordys.crm.aiagent.config.AiAgentConversationProperties;
import cn.cordys.crm.aiagent.dto.*;
import cn.cordys.crm.aiagent.dto.response.AiAgentChatResponse;
import cn.cordys.crm.aiagent.mapper.AiAgentInternalMapper;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.function.Function;
import java.util.function.BiFunction;

/** SQL generation receives recent user/SQL turns directly, as in SQLBot. */
@Service
public class AiAgentConversationService {
    private final AiAgentInternalMapper mapper;
    private final AiAgentConversationProperties properties;
    public AiAgentConversationService(AiAgentInternalMapper mapper, AiAgentLlmClient llmClient,
                                      AiAgentConversationProperties properties, AiAgentIntentValidator intentValidator) {
        this.mapper = mapper; this.properties = properties;
    }
    public int maxQueryRows() { return Math.max(1, Math.min(properties.getMaxQueryRows(), 200)); }

    public void prepareSqlHistory(String sessionId, AiAgentContext context, String beforeMessageId) {
        var stored = mapper.listRecentConversationMessages(sessionId, context.getOrganizationId(), context.getUserId(), 100);
        var chronological = new ArrayList<>(stored == null ? List.<cn.cordys.crm.aiagent.domain.AiAgentMessage>of() : stored);
        Collections.reverse(chronological);
        if (beforeMessageId != null && chronological.stream().noneMatch(m -> beforeMessageId.equals(m.getId()))) {
            context.setSqlHistory(List.of()); return;
        }
        var messages = new ArrayList<AiAgentConversationMessage>();
        String fingerprint = permissionFingerprint(context);
        String lastError = null;
        for (var message : chronological) {
            if (Objects.equals(beforeMessageId, message.getId())) break;
            if (!"assistant".equals(message.getRole())) continue;
            try {
                var evidence = JSON.parseToMap(StringUtils.defaultIfBlank(message.getEvidenceJson(), "{}"));
                var snapshot = JSON.parseObject(JSON.toJSONString(evidence.get("conversation")), AiAgentConversationSnapshot.class);
                var response = JSON.parseObject(JSON.toJSONString(evidence.get("response")), AiAgentChatResponse.class);
                if (snapshot == null || response == null || !fingerprint.equals(snapshot.getPermissionFingerprint())
                        || !"QUERY".equals(snapshot.getMode()) || StringUtils.isBlank(response.getQueryQuestion())) continue;
                var content = new LinkedHashMap<String, Object>();
                boolean success = StringUtils.isBlank(response.getQueryError()) && !response.getSqlQueries().isEmpty();
                content.put("success", success);
                if (!response.getSqlQueries().isEmpty()) content.put("sql", response.getSqlQueries().getFirst());
                if (success) {
                    content.put("chart-type", Objects.toString(response.getChartConfig().get("type"), "table"));
                    content.put("brief", StringUtils.defaultString(response.getAnswer()));
                } else content.put("message", Objects.toString(response.getQueryError(), response.getAnswer()));
                messages.add(new AiAgentConversationMessage("user", response.getQueryQuestion()));
                messages.add(new AiAgentConversationMessage("assistant", JSON.toJSONString(content)));
                lastError = response.getQueryError();
            } catch (RuntimeException ignored) { /* Skip records without usable server-owned SQL evidence. */ }
        }
        while (messages.size() > 6 || (messages.size() > 2 && messages.stream().mapToInt(m -> m.content().length()).sum()
                > Math.max(2000, properties.getMaxHistoryChars()))) messages.subList(0, 2).clear();
        context.setSqlHistory(messages);
        if (context.getQueryError() == null) context.setQueryError(lastError);
    }

    public Outcome respond(String sessionId, String question, AiAgentContext context,
                           Function<String, AiAgentQueryEvidence> queryRunner) {
        prepareSqlHistory(sessionId, context, null);
        return queryOnly(question, context, queryRunner);
    }
    public Outcome respond(String sessionId, String question, AiAgentContext context,
                           Function<String, AiAgentQueryEvidence> queryRunner,
                           BiFunction<AiAgentQueryEvidence, String, AiAgentQueryEvidence> unusedSortRunner) {
        return respond(sessionId, question, context, queryRunner);
    }
    public Outcome queryOnly(String question, AiAgentContext context, Function<String, AiAgentQueryEvidence> queryRunner) {
        AiAgentQueryEvidence query = queryRunner.apply(question);
        return complete(question, "QUERY", context, query.getResponse(), query);
    }
    public Outcome complete(String question, String mode, AiAgentContext context, AiAgentChatResponse response,
                            AiAgentQueryEvidence query) {
        response.setDatasourceId(context.getDatasourceId()); response.setDatasourceName(context.getDatasourceName());
        response.setDatasourceType(context.getDatasourceType());
        var snapshot = new AiAgentConversationSnapshot(); snapshot.setPermissionFingerprint(permissionFingerprint(context));
        snapshot.setQuestion(question); snapshot.setResolvedQuestion(Objects.toString(response.getQueryQuestion(), question));
        snapshot.setMode(mode);
        if (query != null) {
            snapshot.setContinuation(query.getContinuation());
            // Only keep a bounded dataset for historic-record compatibility. The full result is stored once in response.
            var bounded = JSON.parseObject(JSON.toJSONString(query), AiAgentQueryEvidence.class);
            if (bounded.getResponse() != null) {
                bounded.getResponse().setResultTable(null); bounded.getResponse().setPredictionTable(null);
                bounded.getResponse().setCharts(List.of()); bounded.getResponse().setPoints(List.of());
            }
            snapshot.setDatasets(List.of(bounded));
        }
        return new Outcome(response, snapshot);
    }
    History loadHistory(String sessionId, AiAgentContext context) {
        prepareSqlHistory(sessionId, context, null);
        return new History(context.getSqlHistory(), List.of(), false, List.of());
    }
    static String permissionFingerprint(AiAgentContext context) {
        var identity = new ArrayList<>(Arrays.asList(context.getUserId(), context.getOrganizationId(),
                permission(context.getDataPermission()), permission(context.getCustomerDataPermission()),
                permission(context.getContractDataPermission()), permission(context.getOrderDataPermission())));
        if (context.getDatasourceId() != null) { identity.add(context.getDatasourceId()); identity.add(context.getDatasourceRevision()); }
        return DigestUtils.sha256Hex(JSON.toJSONString(identity));
    }
    private static Object permission(DeptDataPermissionDTO permission) {
        if (permission == null) return null;
        return Arrays.asList(permission.getViewId(), permission.getAll(), permission.getSelf(), permission.getVisible(),
                new TreeSet<>(permission.getDeptIds() == null ? Set.of() : permission.getDeptIds()));
    }
    public record Outcome(AiAgentChatResponse response, AiAgentConversationSnapshot snapshot) {
        public Map<String, Object> evidenceSnapshot() { return Map.of("response", response, "conversation", snapshot); }
    }
    record History(List<AiAgentConversationMessage> messages, List<AiAgentQueryEvidence> datasets, boolean unavailable,
                   List<String> lastDatasetIds) { }
}
