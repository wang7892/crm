package cn.cordys.crm.aiagent.dto.semantic;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AiAgentSemanticRuleMatch {
    private String ruleId;
    private Integer version;
    private String canonicalTerm;
    private List<String> aliases = new ArrayList<>();
    private String ruleType;
    private String instruction;
    private Target target;
    private List<Target> forbiddenTargets = new ArrayList<>();
    private List<FilterConstraint> requiredFilters = new ArrayList<>();
    private List<FilterConstraint> forbiddenFilters = new ArrayList<>();
    private Integer priority;
    private String documentId;
    private String chunkId;
    private Integer pageNo;
    private String sectionPath;
    private String matchedBy;
    private double score;

    @Data
    public static class Target {
        private String entity;
        private String field;
    }

    @Data
    public static class FilterConstraint {
        private String entity;
        private String field;
        private String operator;
        private Object value;
    }
}
