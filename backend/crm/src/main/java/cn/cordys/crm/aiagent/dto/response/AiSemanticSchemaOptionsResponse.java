package cn.cordys.crm.aiagent.dto.response;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AiSemanticSchemaOptionsResponse {
    private List<EntityOption> entities = new ArrayList<>();

    @Data
    public static class EntityOption {
        private String key;
        private String label;
        private String dataSource;
        private List<FieldOption> fields = new ArrayList<>();
    }

    @Data
    public static class FieldOption {
        private String key;
        private String label;
        private List<String> aliases = new ArrayList<>();
        private boolean selectable;
        private boolean filterable;
        private boolean sortable;
        private boolean aggregatable;
    }
}
