package cn.cordys.crm.aiagent.service;

import cn.cordys.crm.aiagent.config.AiAgentSemanticRagProperties;
import cn.cordys.crm.aiagent.dto.AiAgentContext;
import cn.cordys.crm.aiagent.dto.ParsedAiAgentQuestion;
import cn.cordys.crm.aiagent.dto.query.AiAgentQueryFilter;
import cn.cordys.crm.aiagent.dto.query.AiAgentQueryMetric;
import cn.cordys.crm.aiagent.dto.query.AiAgentQueryOrder;
import cn.cordys.crm.aiagent.dto.query.AiAgentQueryPlan;
import cn.cordys.crm.aiagent.dto.semantic.AiAgentSemanticRuleMatch;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class AiAgentSemanticPlanGuard {

    private final AiAgentSemanticRagProperties properties;

    public AiAgentSemanticPlanGuard(AiAgentSemanticRagProperties properties) {
        this.properties = properties;
    }

    public boolean isEnforced(AiAgentContext context) {
        return properties.isEnabled()
                && context != null
                && context.getSemanticRuleMatches() != null
                && !context.getSemanticRuleMatches().isEmpty();
    }

    public boolean targetsEntity(AiAgentContext context, String entity) {
        if (!isEnforced(context)) {
            return false;
        }
        return context.getSemanticRuleMatches().stream()
                .map(AiAgentSemanticRuleMatch::getTarget)
                .filter(target -> target != null)
                .anyMatch(target -> StringUtils.equalsIgnoreCase(target.getEntity(), entity));
    }

    public GuardResult validate(ParsedAiAgentQuestion parsedQuestion, AiAgentContext context) {
        if (!isEnforced(context)) {
            return GuardResult.pass();
        }
        if (parsedQuestion == null) {
            return GuardResult.reject("大模型未返回可验证的查询计划");
        }
        if (!StringUtils.equals(parsedQuestion.getIntent(), "CRM_DATABASE_QUERY")) {
            return GuardResult.reject("命中业务术语规则后必须生成 CRM_DATABASE_QUERY 查询计划");
        }
        return validate(parsedQuestion.getQueryPlan(), context.getSemanticRuleMatches(), parsedQuestion.getRawQuestion());
    }

    GuardResult validate(AiAgentQueryPlan plan, List<AiAgentSemanticRuleMatch> matches, String question) {
        if (plan == null) {
            return GuardResult.reject("查询计划为空");
        }
        String entity = normalizeKey(plan.getEntity());
        Set<String> usedFields = collectUsedFields(plan);
        for (AiAgentSemanticRuleMatch match : matches) {
            if (match == null || match.getTarget() == null) {
                return GuardResult.reject("语义规则目标为空");
            }
            String targetEntity = normalizeKey(match.getTarget().getEntity());
            String targetField = normalizeKey(match.getTarget().getField());
            if (!StringUtils.equals(entity, targetEntity)) {
                return GuardResult.reject("查询实体与术语“" + safeTerm(match.getCanonicalTerm()) + "”的已审核目标不一致");
            }
            for (AiAgentSemanticRuleMatch.Target forbidden : safeList(match.getForbiddenTargets())) {
                if (!StringUtils.equals(entity, normalizeKey(forbidden.getEntity()))) {
                    continue;
                }
                String forbiddenField = normalizeKey(forbidden.getField());
                if (StringUtils.isBlank(forbiddenField) || usedFields.contains(forbiddenField)) {
                    return GuardResult.reject("查询计划使用了术语“" + safeTerm(match.getCanonicalTerm()) + "”的禁止目标");
                }
            }
            for (AiAgentSemanticRuleMatch.FilterConstraint required : safeList(match.getRequiredFilters())) {
                if (!StringUtils.equals(entity, normalizeKey(required.getEntity()))) {
                    return GuardResult.reject("术语“" + safeTerm(match.getCanonicalTerm()) + "”的必需过滤条件实体不一致");
                }
                if (!containsFilter(plan, required)) {
                    return GuardResult.reject("查询计划缺少术语“" + safeTerm(match.getCanonicalTerm())
                            + "”要求的过滤条件：" + safeFilter(required));
                }
            }
            for (AiAgentSemanticRuleMatch.FilterConstraint forbidden : safeList(match.getForbiddenFilters())) {
                if (StringUtils.equals(entity, normalizeKey(forbidden.getEntity()))
                        && containsFilter(plan, forbidden)) {
                    return GuardResult.reject("查询计划使用了术语“" + safeTerm(match.getCanonicalTerm())
                            + "”禁止的过滤条件：" + safeFilter(forbidden));
                }
            }
            if (StringUtils.isBlank(targetField) || !usesTargetSemantically(plan, targetField)) {
                return GuardResult.reject("查询计划未使用术语“" + safeTerm(match.getCanonicalTerm()) + "”对应的目标字段");
            }
            if (requiresGrouping(question, match) && !normalizedFields(plan.getGroupBy()).contains(targetField)) {
                return GuardResult.reject("按术语“" + safeTerm(match.getCanonicalTerm()) + "”统计时必须使用目标字段分组");
            }
        }
        return GuardResult.pass();
    }

    private boolean containsFilter(AiAgentQueryPlan plan,
                                   AiAgentSemanticRuleMatch.FilterConstraint constraint) {
        String expectedField = normalizeKey(constraint.getField());
        String expectedOperator = normalizeOperator(constraint.getOperator());
        String expectedValue = normalizeValue(constraint.getValue());
        return safeList(plan.getFilters()).stream().anyMatch(filter ->
                StringUtils.equals(expectedField, normalizeKey(filter.getField()))
                        && StringUtils.equals(expectedOperator, normalizeOperator(filter.getOperator()))
                        && StringUtils.equals(expectedValue, normalizeValue(filter.getValue())));
    }

    private String normalizeOperator(String value) {
        return normalizeKey(StringUtils.defaultIfBlank(value, "eq"));
    }

    private String normalizeValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Iterable<?> iterable) {
            List<String> values = new java.util.ArrayList<>();
            for (Object item : iterable) {
                values.add(normalizeValue(item));
            }
            return String.join("\u001f", values);
        }
        return normalizeText(String.valueOf(value)).replaceAll("\\s+", " ");
    }

    private String safeFilter(AiAgentSemanticRuleMatch.FilterConstraint filter) {
        return safeTerm(filter.getField()) + " " + safeTerm(filter.getOperator()) + " "
                + safeTerm(String.valueOf(filter.getValue()));
    }

    private boolean usesTargetSemantically(AiAgentQueryPlan plan, String targetField) {
        boolean filtered = safeList(plan.getFilters()).stream()
                .map(AiAgentQueryFilter::getField)
                .map(this::normalizeKey)
                .anyMatch(targetField::equals);
        String queryType = StringUtils.upperCase(
                StringUtils.defaultIfBlank(plan.getQueryType(), "LIST"), Locale.ROOT);
        if (StringUtils.equals(queryType, "LIST")) {
            return filtered || normalizedFields(plan.getSelectFields()).contains(targetField);
        }
        if (StringUtils.equalsAny(queryType, "COUNT", "AGGREGATE")) {
            boolean grouped = normalizedFields(plan.getGroupBy()).contains(targetField);
            boolean aggregated = safeList(plan.getMetrics()).stream()
                    .filter(metric -> !StringUtils.equalsIgnoreCase(metric.getFunction(), "count"))
                    .map(AiAgentQueryMetric::getField)
                    .map(this::normalizeKey)
                    .anyMatch(targetField::equals);
            return filtered || grouped || aggregated;
        }
        return false;
    }

    private Set<String> collectUsedFields(AiAgentQueryPlan plan) {
        Set<String> fields = new LinkedHashSet<>(normalizedFields(plan.getSelectFields()));
        for (AiAgentQueryFilter filter : safeList(plan.getFilters())) {
            fields.add(normalizeKey(filter.getField()));
        }
        for (AiAgentQueryMetric metric : safeList(plan.getMetrics())) {
            fields.add(normalizeKey(metric.getField()));
        }
        fields.addAll(normalizedFields(plan.getGroupBy()));
        for (AiAgentQueryOrder order : safeList(plan.getOrderBy())) {
            fields.add(normalizeKey(order.getField()));
        }
        fields.remove("");
        return fields;
    }

    private Set<String> normalizedFields(List<String> fields) {
        Set<String> result = new LinkedHashSet<>();
        for (String field : safeList(fields)) {
            String normalized = normalizeKey(field);
            if (StringUtils.isNotBlank(normalized)) {
                result.add(normalized);
            }
        }
        return result;
    }

    private boolean requiresGrouping(String question, AiAgentSemanticRuleMatch match) {
        String compactQuestion = normalizeText(question).replace(" ", "");
        for (String term : matchedTerms(match)) {
            String normalizedTerm = normalizeText(term).replace(" ", "");
            if (StringUtils.isBlank(normalizedTerm)) {
                continue;
            }
            if (compactQuestion.contains("每个" + normalizedTerm)
                    || compactQuestion.contains("各" + normalizedTerm)
                    || compactQuestion.contains("按" + normalizedTerm)) {
                return true;
            }
        }
        return false;
    }

    private List<String> matchedTerms(AiAgentSemanticRuleMatch match) {
        List<String> terms = new java.util.ArrayList<>();
        terms.add(match.getCanonicalTerm());
        terms.addAll(safeList(match.getAliases()));
        return terms;
    }

    private String normalizeKey(String value) {
        return StringUtils.defaultString(value).trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeText(String value) {
        return Normalizer.normalize(StringUtils.defaultString(value), Normalizer.Form.NFKC)
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private String safeTerm(String value) {
        return StringUtils.abbreviate(StringUtils.defaultIfBlank(value, "未命名术语").replaceAll("\\s+", " "), 64);
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    public record GuardResult(boolean allowed, String reason) {
        static GuardResult pass() {
            return new GuardResult(true, null);
        }

        static GuardResult reject(String reason) {
            return new GuardResult(false, reason);
        }
    }
}
