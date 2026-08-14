package cn.cordys.crm.aiagent.dto.semantic;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AiAgentSemanticRuleContext {
    private List<Rule> rules = new ArrayList<>();

    @Data
    public static class Rule {
        private String ruleId;
        private Integer version;
        private String ruleType;
        private String canonicalTerm;
        private List<String> aliases = new ArrayList<>();
        private String instruction;
        private Target target;
        private List<Target> forbiddenTargets = new ArrayList<>();
        private List<FilterConstraint> requiredFilters = new ArrayList<>();
        private List<FilterConstraint> forbiddenFilters = new ArrayList<>();
    }

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
