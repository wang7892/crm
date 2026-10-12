package cn.cordys.crm.aiagent.dto.knowledge;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

public final class AiAgentQueryKnowledge {
    private AiAgentQueryKnowledge() { }

    @Data
    public static class Item {
        private String id;
        private String kind;
        private String name;
        private String content;
        private List<String> synonyms = new ArrayList<>();
        private boolean allDatasources;
        private List<String> datasourceIds = new ArrayList<>();
        private boolean enabled = true;
        private long revision;
        private long updateTime;
        private String embeddingStatus;
    }

    @Data
    public static class Stored {
        private String id;
        private String organizationId;
        private String kind;
        private String name;
        private String content;
        private String synonymsJson;
        private boolean allDatasources;
        private String datasourceIdsJson;
        private boolean enabled;
        private long revision;
        private String embeddingJson;
        private String embeddingKey;
        private String createUser;
        private long createTime;
        private long updateTime;
    }

    public record Matches(List<Item> terms, List<Item> examples, boolean truncated) {
        public static Matches empty() { return new Matches(List.of(), List.of(), false); }
    }

    public record Change(long revision, boolean enabled) { }
    public record Removal(String id, long revision) { }
    public record Search(String question, String datasourceId) { }
    public record ReindexResult(int success, int failed, int stale, int remaining, List<String> processedIds) { }
}
