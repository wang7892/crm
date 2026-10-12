package cn.cordys.crm.aiagent.service;

import cn.cordys.common.util.JSON;
import cn.cordys.crm.aiagent.dto.knowledge.AiAgentQueryKnowledge.Item;
import cn.cordys.crm.aiagent.dto.knowledge.AiAgentQueryKnowledge.Stored;
import org.apache.commons.codec.digest.DigestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class AiAgentQueryKnowledgeIndex {
    private AiAgentQueryKnowledgeIndex() { }

    static String key(String kind, String modelKey) {
        if (modelKey == null) return null;
        // Reject the old combined name/description vectors and rebuild terms automatically.
        return "TERM".equals(kind) ? DigestUtils.sha256Hex(modelKey + "\nterm-words-v1") : modelKey;
    }

    static String embed(Item item, AiAgentQueryKnowledgeEmbedding embedding, String org) {
        return embed(item.getKind(), item.getName(), item.getSynonyms(), embedding, org);
    }

    static String embed(Stored item, AiAgentQueryKnowledgeEmbedding embedding) {
        var synonyms = item.getSynonymsJson() == null ? List.<String>of()
                : JSON.parseArray(item.getSynonymsJson(), String.class);
        return embed(item.getKind(), item.getName(), synonyms, embedding, item.getOrganizationId());
    }

    private static String embed(String kind, String name, List<String> synonyms,
                                AiAgentQueryKnowledgeEmbedding embedding, String org) {
        if (!"TERM".equals(kind)) {
            var vector = embedding.embed(name, org);
            return vector.isEmpty() ? null : JSON.toJSONString(vector);
        }
        List<String> words = new ArrayList<>();
        words.add(name); words.addAll(synonyms);
        var vectors = embedding.embedBatch(words, org);
        if (vectors.size() != words.size() || vectors.stream().anyMatch(List::isEmpty)) return null;
        return JSON.toJSONString(vectors);
    }

    static List<Double> similarities(List<Double> query, Stored item) {
        if (!"TERM".equals(item.getKind())) return List.of(AiAgentQueryKnowledgeEmbedding.similarity(query,
                JSON.parseArray(item.getEmbeddingJson(), Double.class)));
        // Rank each name/synonym separately, then map the selected words to their parent term.
        return JSON.parseArray(item.getEmbeddingJson(), Double[].class).stream()
                .map(v -> AiAgentQueryKnowledgeEmbedding.similarity(query, Arrays.asList(v))).toList();
    }
}
