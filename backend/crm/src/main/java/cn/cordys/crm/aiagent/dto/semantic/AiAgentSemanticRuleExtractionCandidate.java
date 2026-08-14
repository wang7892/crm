package cn.cordys.crm.aiagent.dto.semantic;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AiAgentSemanticRuleExtractionCandidate {
    private String ruleType;
    private String canonicalTerm;
    private List<String> aliases = new ArrayList<>();
    private String definition;
    private String instruction;
    private SuggestedMapping suggestedMapping;
    private List<AiAgentSemanticRule.ForbiddenMapping> forbiddenMappings = new ArrayList<>();
    private List<AiAgentSemanticRule.FilterConstraint> requiredFilters = new ArrayList<>();
    private List<AiAgentSemanticRule.FilterConstraint> forbiddenFilters = new ArrayList<>();
    private List<AiAgentSemanticRule.Example> examples = new ArrayList<>();
    private String sourceQuote;
    private Double confidence;

    @Data
    public static class SuggestedMapping {
        private String entity;
        private String field;
    }
}
