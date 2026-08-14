package cn.cordys.crm.aiagent.service;

import cn.cordys.common.util.JSON;
import cn.cordys.crm.aiagent.config.AiAgentSemanticRagProperties;
import cn.cordys.crm.aiagent.dto.semantic.AiAgentSemanticRuleContext;
import cn.cordys.crm.aiagent.dto.semantic.AiAgentSemanticRuleMatch;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class AiAgentSemanticContextBuilder {

    private static final int HARD_MAX_RULES = 3;
    private static final int MAX_ALIASES = 20;
    private static final int MAX_FORBIDDEN_TARGETS = 10;
    private static final int MAX_FILTERS = 10;
    private static final Pattern RULE_ID_PATTERN = Pattern.compile("[A-Z0-9_]{1,64}");
    private static final Pattern SCHEMA_KEY_PATTERN = Pattern.compile("[a-z0-9_]{1,64}");

    private final AiAgentSemanticRagProperties properties;

    public AiAgentSemanticContextBuilder(AiAgentSemanticRagProperties properties) {
        this.properties = properties;
    }

    public AiAgentSemanticRuleContext build(List<AiAgentSemanticRuleMatch> matches) {
        AiAgentSemanticRuleContext context = new AiAgentSemanticRuleContext();
        if (matches == null || matches.isEmpty()) {
            return context;
        }
        int maxRules = Math.min(HARD_MAX_RULES, Math.max(1, properties.getMaxRules()));
        int maxContextChars = Math.max(2, properties.getMaxContextChars());
        for (AiAgentSemanticRuleMatch match : matches) {
            if (context.getRules().size() >= maxRules) {
                break;
            }
            AiAgentSemanticRuleContext.Rule rule = controlledRule(match);
            if (rule == null) {
                continue;
            }
            context.getRules().add(rule);
            if (JSON.toJSONString(context).length() > maxContextChars) {
                context.getRules().remove(context.getRules().size() - 1);
                break;
            }
        }
        return context;
    }

    public String toPromptJson(AiAgentSemanticRuleContext context) {
        AiAgentSemanticRuleContext safeContext = context == null ? new AiAgentSemanticRuleContext() : context;
        return JSON.toJSONString(safeContext);
    }

    private AiAgentSemanticRuleContext.Rule controlledRule(AiAgentSemanticRuleMatch match) {
        if (match == null
                || !RULE_ID_PATTERN.matcher(StringUtils.defaultString(match.getRuleId())).matches()
                || match.getVersion() == null
                || match.getVersion() <= 0
                || !isSafeTerm(match.getCanonicalTerm())
                || match.getTarget() == null
                || !isSchemaKey(match.getTarget().getEntity())
                || !isSchemaKey(match.getTarget().getField())) {
            return null;
        }
        AiAgentSemanticRuleContext.Rule rule = new AiAgentSemanticRuleContext.Rule();
        rule.setRuleId(match.getRuleId());
        rule.setVersion(match.getVersion());
        rule.setRuleType(StringUtils.equals(
                match.getRuleType(), AiAgentSemanticRuleValidationService.TYPE_FILTER_VALUE)
                ? AiAgentSemanticRuleValidationService.TYPE_FILTER_VALUE
                : AiAgentSemanticRuleValidationService.TYPE_TERM_MAPPING);
        rule.setCanonicalTerm(match.getCanonicalTerm().trim());
        rule.setAliases(safeAliases(match.getAliases(), rule.getCanonicalTerm()));
        rule.setInstruction(safeInstruction(match.getInstruction()));
        rule.setTarget(target(match.getTarget()));
        if (match.getForbiddenTargets() != null) {
            match.getForbiddenTargets().stream()
                    .filter(this::isSafeForbiddenTarget)
                    .limit(MAX_FORBIDDEN_TARGETS)
                    .map(this::target)
                    .forEach(rule.getForbiddenTargets()::add);
        }
        List<AiAgentSemanticRuleContext.FilterConstraint> requiredFilters = controlledFilters(
                match.getRequiredFilters());
        if (match.getRequiredFilters() != null && requiredFilters.size() != match.getRequiredFilters().size()) {
            return null;
        }
        rule.setRequiredFilters(requiredFilters);
        List<AiAgentSemanticRuleContext.FilterConstraint> forbiddenFilters = controlledFilters(
                match.getForbiddenFilters());
        if (match.getForbiddenFilters() != null && forbiddenFilters.size() != match.getForbiddenFilters().size()) {
            return null;
        }
        rule.setForbiddenFilters(forbiddenFilters);
        return rule;
    }

    private List<String> safeAliases(List<String> aliases, String canonicalTerm) {
        if (aliases == null || aliases.isEmpty()) {
            return List.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String alias : aliases) {
            if (result.size() >= MAX_ALIASES) {
                break;
            }
            if (isSafeTerm(alias) && !StringUtils.equals(alias.trim(), canonicalTerm)) {
                result.add(alias.trim());
            }
        }
        return List.copyOf(result);
    }

    private AiAgentSemanticRuleContext.Target target(AiAgentSemanticRuleMatch.Target source) {
        AiAgentSemanticRuleContext.Target target = new AiAgentSemanticRuleContext.Target();
        target.setEntity(source.getEntity().trim().toLowerCase(Locale.ROOT));
        target.setField(StringUtils.trimToNull(source.getField()) == null
                ? null : source.getField().trim().toLowerCase(Locale.ROOT));
        return target;
    }

    private List<AiAgentSemanticRuleContext.FilterConstraint> controlledFilters(
            List<AiAgentSemanticRuleMatch.FilterConstraint> filters) {
        if (filters == null || filters.isEmpty()) {
            return List.of();
        }
        List<AiAgentSemanticRuleContext.FilterConstraint> result = new ArrayList<>();
        for (AiAgentSemanticRuleMatch.FilterConstraint filter : filters) {
            if (result.size() >= MAX_FILTERS) {
                break;
            }
            if (filter == null
                    || !isSchemaKey(filter.getEntity())
                    || !isSchemaKey(filter.getField())
                    || !isSchemaKey(filter.getOperator())) {
                continue;
            }
            Object safeValue = safeValue(filter.getValue());
            if (filter.getValue() != null && safeValue == null) {
                continue;
            }
            AiAgentSemanticRuleContext.FilterConstraint controlled =
                    new AiAgentSemanticRuleContext.FilterConstraint();
            controlled.setEntity(filter.getEntity().trim().toLowerCase(Locale.ROOT));
            controlled.setField(filter.getField().trim().toLowerCase(Locale.ROOT));
            controlled.setOperator(filter.getOperator().trim().toLowerCase(Locale.ROOT));
            controlled.setValue(safeValue);
            result.add(controlled);
        }
        return List.copyOf(result);
    }

    private Object safeValue(Object value) {
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof String text) {
            return isSafeBusinessText(text, 256) ? text.trim() : null;
        }
        if (value instanceof Collection<?> collection && collection.size() <= 20) {
            List<Object> result = new ArrayList<>();
            for (Object item : collection) {
                Object safeItem = safeValue(item);
                if (safeItem == null) {
                    return null;
                }
                result.add(safeItem);
            }
            return List.copyOf(result);
        }
        return null;
    }

    private boolean isSafeForbiddenTarget(AiAgentSemanticRuleMatch.Target target) {
        return target != null
                && isSchemaKey(target.getEntity())
                && (StringUtils.isBlank(target.getField()) || isSchemaKey(target.getField()));
    }

    private boolean isSchemaKey(String value) {
        return SCHEMA_KEY_PATTERN.matcher(StringUtils.defaultString(value).trim().toLowerCase(Locale.ROOT)).matches();
    }

    private boolean isSafeTerm(String value) {
        return isSafeBusinessText(value, 64);
    }

    private String safeInstruction(String value) {
        return isSafeBusinessText(value, 1000) ? value.trim() : null;
    }

    private boolean isSafeBusinessText(String value, int maxLength) {
        String term = StringUtils.defaultString(value).trim();
        if (term.isEmpty() || term.length() > maxLength || term.contains("`")
                || term.chars().anyMatch(Character::isISOControl)) {
            return false;
        }
        String lower = term.toLowerCase(Locale.ROOT);
        return !lower.contains("ignore previous")
                && !lower.contains("system prompt")
                && !lower.contains("忽略前述")
                && !lower.contains("忽略系统")
                && !lower.contains("执行 sql")
                && !lower.contains("执行sql");
    }
}
