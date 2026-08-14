package cn.cordys.crm.aiagent.dto.semantic;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AiAgentSemanticRule {
    private String schemaVersion;
    private String ruleId;
    private Integer version;
    private String type;
    private String canonicalTerm;
    private List<String> aliases = new ArrayList<>();
    private String definition;
    private String instruction;
    private String scope;
    private Mapping mapping;
    private List<ForbiddenMapping> forbiddenMappings = new ArrayList<>();
    private List<FilterConstraint> requiredFilters = new ArrayList<>();
    private List<FilterConstraint> forbiddenFilters = new ArrayList<>();
    private List<Example> examples = new ArrayList<>();
    private Integer priority;
    private Long effectiveFrom;
    private Long effectiveTo;
    private Source source;
    private Extraction extraction;
    private Review review;
    private List<String> validationErrors = new ArrayList<>();

    @Data
    public static class Mapping {
        private String entity;
        private String field;
        private String dataSource;
    }

    @Data
    public static class ForbiddenMapping {
        private String entity;
        private String field;
        private String reason;
    }

    @Data
    public static class FilterConstraint {
        private String entity;
        private String field;
        private String operator;
        private Object value;
    }

    @Data
    public static class Example {
        private String question;
        private String expectedEntity;
        private String expectedField;
    }

    @Data
    public static class Source {
        private String documentId;
        private Integer pageNo;
        private String sectionPath;
        private String quote;
    }

    @Data
    public static class Extraction {
        private Double confidence;
        private String model;
    }

    @Data
    public static class Review {
        private String status;
        private String reviewerId;
        private Long reviewedAt;
        private String comment;
    }
}
