package cn.cordys.crm.aiagent.dto.datasource;

import jakarta.validation.constraints.*;
import lombok.Data;
import java.util.ArrayList;
import java.util.List;

/** Connection details are never included in the model's query context. */
public final class AiAgentDatasource {
    private AiAgentDatasource() { }

    @Data
    public static class Config {
        @Size(max = 64) private String id;
        @NotBlank @Size(max = 100) private String name;
        @NotBlank @Pattern(regexp = "MYSQL|POSTGRESQL|ORACLE|SQLSERVER|FILE") private String type;
        @Size(max = 253) private String host;
        @Min(1) @Max(65535) private Integer port;
        @Size(max = 128) private String database;
        @Size(max = 128) private String schema;
        @Size(max = 128) private String username;
        @lombok.ToString.Exclude
        @Size(max = 4096) private String password;
        private boolean ssl;
        private Long revision;
        @Size(max = 60) private List<@Size(max = 256) String> selectedTables = new ArrayList<>();
        private List<Table> metadata = new ArrayList<>();
        private List<Relation> relations = new ArrayList<>();
    }

    @Data
    public static class Column {
        private String name;
        private String type;
        private String comment;
    }

    @Data
    public static class Table {
        private String name;
        private String comment;
        private List<Column> columns = new ArrayList<>();
        private List<Relation> relations = new ArrayList<>();
    }

    @Data
    public static class Relation {
        private String sourceTable;
        private String sourceColumn;
        private String targetTable;
        private String targetColumn;
    }

    @Data
    public static class View {
        private String id;
        private String name;
        private String type;
        private String host;
        private Integer port;
        private String database;
        private String schema;
        private String username;
        private boolean ssl;
        private boolean passwordConfigured;
        private long revision;
        private long updateTime;
        private int tableCount;
        private List<String> selectedTables = new ArrayList<>();
        private List<Table> tables = new ArrayList<>();
        private List<Relation> relations = new ArrayList<>();
    }

    @Data
    public static class Stored {
        private String id;
        private String organizationId;
        private String ownerId;
        private String name;
        private String type;
        private String configJson;
        private String tablesJson;
        private String selectedJson;
        private long revision;
        private long updateTime;
    }
}
