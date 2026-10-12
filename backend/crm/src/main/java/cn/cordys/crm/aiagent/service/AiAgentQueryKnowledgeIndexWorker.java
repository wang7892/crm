package cn.cordys.crm.aiagent.service;

import cn.cordys.crm.aiagent.config.AiAgentQueryKnowledgeIndexConfig;
import cn.cordys.crm.aiagent.config.AiAgentQueryKnowledgeProperties;
import cn.cordys.crm.aiagent.mapper.AiAgentQueryKnowledgeMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class AiAgentQueryKnowledgeIndexWorker {
    private final AiAgentQueryKnowledgeMapper mapper;
    private final AiAgentQueryKnowledgeEmbedding embedding;
    private final AiAgentQueryKnowledgeProperties properties;
    private String afterId = "";
    private String modelKey;

    public AiAgentQueryKnowledgeIndexWorker(AiAgentQueryKnowledgeMapper mapper, AiAgentQueryKnowledgeEmbedding embedding,
                                            AiAgentQueryKnowledgeProperties properties) {
        this.mapper = mapper; this.embedding = embedding; this.properties = properties;
    }

    @Scheduled(initialDelayString = "${crm.ai-agent.query-knowledge.index-initial-delay-ms:5000}",
            fixedDelayString = "${crm.ai-agent.query-knowledge.index-poll-ms:30000}",
            scheduler = AiAgentQueryKnowledgeIndexConfig.SCHEDULER)
    public synchronized void rebuildPendingIndexes() {
        String key = embedding.key();
        if (key == null) return;
        if (!key.equals(modelKey)) { modelKey = key; afterId = ""; }
        try {
            int batchSize = Math.max(1, Math.min(100, properties.getIndexBatchSize()));
            String termKey = AiAgentQueryKnowledgeIndex.key("TERM", key);
            var pending = mapper.pendingIndexes(key, termKey, afterId, batchSize);
            if (pending.isEmpty() && !afterId.isEmpty()) {
                afterId = "";
                pending = mapper.pendingIndexes(key, termKey, afterId, batchSize);
            }
            int success = 0, failed = 0, stale = 0;
            for (var item : pending) {
                // Advance past failures so one unavailable record cannot block later organizations.
                afterId = item.getId();
                String indexJson = AiAgentQueryKnowledgeIndex.embed(item, embedding);
                if (indexJson == null) { failed++; continue; }
                if (!key.equals(embedding.key())) return;
                if (mapper.updateEmbedding(item.getOrganizationId(), item.getId(), item.getRevision(),
                        indexJson, AiAgentQueryKnowledgeIndex.key(item.getKind(), key)) == 1) success++;
                else stale++;
            }
            if (!pending.isEmpty()) log.info("Query knowledge semantic indexing: success={}, failed={}, stale={}", success, failed, stale);
        } catch (Exception e) {
            log.warn("Query knowledge semantic indexing interrupted; will retry; errorType={}", e.getClass().getSimpleName());
        }
    }
}
