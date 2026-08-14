package cn.cordys.crm.aiagent.service;

import cn.cordys.common.util.JSON;
import cn.cordys.crm.aiagent.domain.AiKnowledgeDocument;
import cn.cordys.crm.aiagent.dto.response.AiSemanticSchemaOptionsResponse;
import cn.cordys.crm.aiagent.dto.semantic.AiAgentSemanticRule;
import cn.cordys.crm.aiagent.dto.semantic.AiAgentSemanticRuleExtractionCandidate;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class AiAgentSemanticRuleValidationService {

    public static final String CATEGORY = "SEMANTIC_RULE";
    public static final String SCHEMA_VERSION = "1.0";
    public static final String TYPE_TERM_MAPPING = "TERM_MAPPING";
    public static final String TYPE_FILTER_VALUE = "FILTER_VALUE";
    public static final String SCOPE_DATABASE_QUERY = "CRM_DATABASE_QUERY";
    public static final String REVIEW_PENDING = "PENDING";
    public static final String REVIEW_APPROVED = "APPROVED";
    public static final String REVIEW_REJECTED = "REJECTED";
    public static final String REVIEW_INVALID = "INVALID";
    private static final int MAX_RULE_JSON_LENGTH = 16_000;
    private static final Pattern RULE_ID_PATTERN = Pattern.compile("[A-Z0-9_]{1,64}");
    private static final Pattern UNSAFE_TERM_PATTERN = Pattern.compile(
            "(?i)(```|<script|system\\s*prompt|ignore\\s+(all\\s+)?previous|忽略.{0,12}(指令|规则|提示)|执行\\s*sql)"
    );

    private final AiAgentSemanticSchemaService schemaService;

    public AiAgentSemanticRuleValidationService(AiAgentSemanticSchemaService schemaService) {
        this.schemaService = schemaService;
    }

    public AiAgentSemanticRule fromCandidate(AiAgentSemanticRuleExtractionCandidate candidate,
                                             AiKnowledgeDocument document,
                                             String organizationId,
                                             SourceLocation sourceLocation,
                                             String extractionModel,
                                             String normalizedMarkdown) {
        AiAgentSemanticRule rule = new AiAgentSemanticRule();
        rule.setSchemaVersion(SCHEMA_VERSION);
        rule.setType(candidate == null ? null : candidate.getRuleType());
        rule.setCanonicalTerm(candidate == null ? null : candidate.getCanonicalTerm());
        rule.setAliases(candidate == null ? List.of() : candidate.getAliases());
        rule.setDefinition(candidate == null ? null : candidate.getDefinition());
        rule.setInstruction(automaticInstruction(candidate));
        rule.setScope(SCOPE_DATABASE_QUERY);
        rule.setVersion(0);
        rule.setPriority(100);

        AiAgentSemanticRule.Mapping mapping = new AiAgentSemanticRule.Mapping();
        if (candidate != null && candidate.getSuggestedMapping() != null) {
            mapping.setEntity(candidate.getSuggestedMapping().getEntity());
            mapping.setField(candidate.getSuggestedMapping().getField());
        } else if (candidate != null && candidate.getRequiredFilters() != null
                && !candidate.getRequiredFilters().isEmpty()) {
            mapping.setEntity(candidate.getRequiredFilters().get(0).getEntity());
            mapping.setField(candidate.getRequiredFilters().get(0).getField());
        }
        rule.setMapping(mapping);
        rule.setForbiddenMappings(candidate == null ? List.of() : candidate.getForbiddenMappings());
        rule.setRequiredFilters(candidate == null ? List.of() : candidate.getRequiredFilters());
        rule.setForbiddenFilters(candidate == null ? List.of() : candidate.getForbiddenFilters());
        rule.setExamples(candidate == null ? List.of() : candidate.getExamples());

        AiAgentSemanticRule.Source source = new AiAgentSemanticRule.Source();
        source.setDocumentId(document.getId());
        source.setPageNo(sourceLocation == null ? null : sourceLocation.pageNo());
        source.setSectionPath(sourceLocation == null ? null : sourceLocation.sectionPath());
        source.setQuote(candidate == null ? null : candidate.getSourceQuote());
        rule.setSource(source);

        AiAgentSemanticRule.Extraction extraction = new AiAgentSemanticRule.Extraction();
        extraction.setConfidence(candidate == null ? null : candidate.getConfidence());
        extraction.setModel(StringUtils.defaultIfBlank(extractionModel, "configured-extraction-model"));
        rule.setExtraction(extraction);

        AiAgentSemanticRule.Review review = new AiAgentSemanticRule.Review();
        review.setStatus(REVIEW_PENDING);
        rule.setReview(review);

        normalize(rule);
        rule.setRuleId(generateRuleId(organizationId, rule.getScope(), rule.getCanonicalTerm()));
        List<String> errors = validate(rule, normalizedMarkdown, true);
        rule.setValidationErrors(errors);
        if (errors.isEmpty()) {
            rule.getReview().setStatus(REVIEW_APPROVED);
            rule.getReview().setReviewerId("SYSTEM_AUTO");
            rule.getReview().setReviewedAt(System.currentTimeMillis());
            rule.getReview().setComment("文档上传后自动校验并生效");
        } else {
            rule.getReview().setStatus(REVIEW_INVALID);
        }
        return rule;
    }

    public void normalize(AiAgentSemanticRule rule) {
        if (rule == null) {
            return;
        }
        rule.setSchemaVersion(SCHEMA_VERSION);
        String normalizedType = StringUtils.upperCase(StringUtils.trimToNull(rule.getType()), Locale.ROOT);
        if (StringUtils.isBlank(normalizedType)) {
            normalizedType = rule.getRequiredFilters() == null || rule.getRequiredFilters().isEmpty()
                    ? TYPE_TERM_MAPPING : TYPE_FILTER_VALUE;
        }
        rule.setType(normalizedType);
        rule.setScope(SCOPE_DATABASE_QUERY);
        rule.setCanonicalTerm(normalizeText(rule.getCanonicalTerm()));
        rule.setDefinition(StringUtils.trimToNull(rule.getDefinition()));
        rule.setInstruction(StringUtils.trimToNull(rule.getInstruction()));
        rule.setAliases(normalizeAliases(rule.getAliases(), rule.getCanonicalTerm()));
        rule.setForbiddenMappings(normalizeForbiddenMappings(rule.getForbiddenMappings()));
        rule.setRequiredFilters(normalizeFilterConstraints(rule.getRequiredFilters()));
        rule.setForbiddenFilters(normalizeFilterConstraints(rule.getForbiddenFilters()));
        rule.setExamples(normalizeExamples(rule.getExamples()));
        rule.setPriority(rule.getPriority() == null ? 100 : rule.getPriority());
        rule.setVersion(rule.getVersion() == null ? 0 : rule.getVersion());
        if (rule.getMapping() != null) {
            rule.getMapping().setEntity(normalizeKey(rule.getMapping().getEntity()));
            rule.getMapping().setField(normalizeKey(rule.getMapping().getField()));
            schemaService.findEntity(rule.getMapping().getEntity())
                    .ifPresent(entity -> rule.getMapping().setDataSource(entity.dataSourceKind().name()));
        }
        if (rule.getSource() != null) {
            rule.getSource().setQuote(StringUtils.trimToNull(rule.getSource().getQuote()));
            rule.getSource().setSectionPath(StringUtils.trimToNull(rule.getSource().getSectionPath()));
        }
        if (rule.getExtraction() != null && rule.getExtraction().getConfidence() != null) {
            rule.getExtraction().setConfidence(Math.max(0D, Math.min(1D, rule.getExtraction().getConfidence())));
        }
        if (rule.getReview() == null) {
            AiAgentSemanticRule.Review review = new AiAgentSemanticRule.Review();
            review.setStatus(REVIEW_PENDING);
            rule.setReview(review);
        }
        if (rule.getValidationErrors() == null) {
            rule.setValidationErrors(new ArrayList<>());
        }
    }

    public List<String> validate(AiAgentSemanticRule rule, String normalizedMarkdown, boolean requireSourceLocation) {
        List<String> errors = new ArrayList<>();
        if (rule == null) {
            return List.of("规则内容为空");
        }
        normalize(rule);
        validateTrustedContract(rule, errors);
        validateTerm(rule.getCanonicalTerm(), "规范术语", errors);
        validateAliases(rule, errors);
        validateDefinition(rule.getDefinition(), errors);
        validateInstruction(rule.getInstruction(), errors);
        validateMapping(rule.getMapping(), errors);
        validateForbiddenMappings(rule.getForbiddenMappings(), errors);
        validateFilterConstraints(rule.getRequiredFilters(), "必需过滤条件", errors);
        validateFilterConstraints(rule.getForbiddenFilters(), "禁止过滤条件", errors);
        validateFilterRuleContract(rule, errors);
        validateExamples(rule.getExamples(), errors);
        validatePriorityAndDates(rule, errors);
        validateSource(rule.getSource(), normalizedMarkdown, requireSourceLocation, errors);
        String json = JSON.toJSONString(rule);
        if (json.length() > MAX_RULE_JSON_LENGTH) {
            errors.add("单条规则超过 " + MAX_RULE_JSON_LENGTH + " 字符，不能拆分保存");
        }
        return errors.stream().distinct().toList();
    }

    public boolean isEffective(AiAgentSemanticRule rule, long now) {
        return rule != null
                && (rule.getEffectiveFrom() == null || rule.getEffectiveFrom() <= now)
                && (rule.getEffectiveTo() == null || rule.getEffectiveTo() >= now);
    }

    public String serialize(AiAgentSemanticRule rule) {
        normalize(rule);
        return JSON.toJSONString(rule);
    }

    public AiAgentSemanticRule deserialize(String content) {
        AiAgentSemanticRule rule = JSON.parseObject(content, AiAgentSemanticRule.class);
        normalize(rule);
        return rule;
    }

    public String semanticPayloadHash(AiAgentSemanticRule rule) {
        normalize(rule);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", rule.getType());
        payload.put("canonicalTerm", rule.getCanonicalTerm());
        payload.put("aliases", sortedJsonValues(rule.getAliases()));
        payload.put("definition", rule.getDefinition());
        payload.put("instruction", rule.getInstruction());
        payload.put("scope", rule.getScope());
        payload.put("mapping", rule.getMapping());
        payload.put("forbiddenMappings", sortedJsonValues(rule.getForbiddenMappings()));
        payload.put("requiredFilters", sortedJsonValues(rule.getRequiredFilters()));
        payload.put("forbiddenFilters", sortedJsonValues(rule.getForbiddenFilters()));
        payload.put("examples", sortedJsonValues(rule.getExamples()));
        payload.put("priority", rule.getPriority());
        payload.put("effectiveFrom", rule.getEffectiveFrom());
        payload.put("effectiveTo", rule.getEffectiveTo());
        return DigestUtils.sha256Hex(JSON.toJSONString(payload));
    }

    public String generateRuleId(String organizationId, String scope, String canonicalTerm) {
        String seed = String.join("|",
                StringUtils.defaultString(organizationId),
                StringUtils.defaultIfBlank(scope, SCOPE_DATABASE_QUERY),
                normalizeLookup(canonicalTerm));
        return "TERM_" + DigestUtils.sha256Hex(seed).substring(0, 32).toUpperCase(Locale.ROOT);
    }

    public AiSemanticSchemaOptionsResponse schemaOptions() {
        AiSemanticSchemaOptionsResponse response = new AiSemanticSchemaOptionsResponse();
        List<AiSemanticSchemaOptionsResponse.EntityOption> entities = schemaService.entities().values().stream()
                .map(entity -> {
                    AiSemanticSchemaOptionsResponse.EntityOption option = new AiSemanticSchemaOptionsResponse.EntityOption();
                    option.setKey(entity.name());
                    option.setLabel(entity.label());
                    option.setDataSource(entity.dataSourceKind().name());
                    option.setFields(entity.fields().values().stream().map(field -> {
                        AiSemanticSchemaOptionsResponse.FieldOption fieldOption = new AiSemanticSchemaOptionsResponse.FieldOption();
                        fieldOption.setKey(field.key());
                        fieldOption.setLabel(field.label());
                        fieldOption.setAliases(field.aliases());
                        fieldOption.setSelectable(field.selectable());
                        fieldOption.setFilterable(field.filterable());
                        fieldOption.setSortable(field.sortable());
                        fieldOption.setAggregatable(field.aggregatable());
                        return fieldOption;
                    }).toList());
                    return option;
                }).toList();
        response.setEntities(entities);
        return response;
    }

    private void validateTrustedContract(AiAgentSemanticRule rule, List<String> errors) {
        if (!StringUtils.equals(rule.getSchemaVersion(), SCHEMA_VERSION)) {
            errors.add("schemaVersion 必须为 " + SCHEMA_VERSION);
        }
        if (!StringUtils.equalsAny(rule.getType(), TYPE_TERM_MAPPING, TYPE_FILTER_VALUE)) {
            errors.add("规则类型只允许 TERM_MAPPING 或 FILTER_VALUE");
        }
        if (!StringUtils.equals(rule.getScope(), SCOPE_DATABASE_QUERY)) {
            errors.add("scope 必须为 CRM_DATABASE_QUERY");
        }
        if (StringUtils.isBlank(rule.getRuleId()) || !RULE_ID_PATTERN.matcher(rule.getRuleId()).matches()) {
            errors.add("ruleId 格式无效");
        }
    }

    private String automaticInstruction(AiAgentSemanticRuleExtractionCandidate candidate) {
        if (candidate == null) {
            return null;
        }
        String instruction = StringUtils.firstNonBlank(
                candidate.getInstruction(), candidate.getDefinition(), candidate.getSourceQuote());
        String quote = StringUtils.trimToNull(candidate.getSourceQuote());
        if (StringUtils.isBlank(instruction) || StringUtils.isBlank(quote) || instruction.contains(quote)) {
            return instruction;
        }
        return StringUtils.left(instruction, 450) + "；来源原文：" + StringUtils.left(quote, 500);
    }

    private void validateTerm(String value, String label, List<String> errors) {
        if (StringUtils.isBlank(value)) {
            errors.add(label + "不能为空");
            return;
        }
        if (value.length() > 64) {
            errors.add(label + "不能超过 64 个字符");
        }
        if (containsControlCharacter(value) || UNSAFE_TERM_PATTERN.matcher(value).find()) {
            errors.add(label + "包含不允许的指令或控制字符");
        }
    }

    private void validateAliases(AiAgentSemanticRule rule, List<String> errors) {
        List<String> aliases = rule.getAliases();
        if (aliases.size() > 20) {
            errors.add("同义词不能超过 20 个");
        }
        for (String alias : aliases) {
            validateTerm(alias, "同义词", errors);
            if (StringUtils.equalsIgnoreCase(alias, rule.getCanonicalTerm())) {
                errors.add("同义词不能与规范术语相同");
            }
        }
    }

    private void validateDefinition(String definition, List<String> errors) {
        if (StringUtils.length(definition) > 500) {
            errors.add("业务定义不能超过 500 个字符");
        }
    }

    private void validateInstruction(String instruction, List<String> errors) {
        if (StringUtils.isNotBlank(instruction) && instruction.length() > 1000) {
            errors.add("业务说明不能超过 1000 个字符");
        }
    }

    private void validateMapping(AiAgentSemanticRule.Mapping mapping, List<String> errors) {
        if (mapping == null || StringUtils.isAnyBlank(mapping.getEntity(), mapping.getField())) {
            errors.add("目标实体和字段不能为空");
            return;
        }
        schemaService.findEntity(mapping.getEntity()).ifPresentOrElse(entity -> {
            entity.findField(mapping.getField()).ifPresentOrElse(field -> {
                if (!field.selectable() && !field.filterable() && !field.aggregatable()) {
                    errors.add("目标字段不能用于业务查询：" + mapping.getEntity() + "." + mapping.getField());
                }
                mapping.setDataSource(entity.dataSourceKind().name());
            }, () -> errors.add("未知目标字段：" + mapping.getEntity() + "." + mapping.getField()));
        }, () -> errors.add("未知目标实体：" + mapping.getEntity()));
    }

    private void validateForbiddenMappings(List<AiAgentSemanticRule.ForbiddenMapping> mappings, List<String> errors) {
        if (mappings.size() > 10) {
            errors.add("禁止映射不能超过 10 条");
        }
        for (AiAgentSemanticRule.ForbiddenMapping mapping : mappings) {
            if (mapping == null || StringUtils.isBlank(mapping.getEntity())) {
                errors.add("禁止映射实体不能为空");
                continue;
            }
            schemaService.findEntity(mapping.getEntity()).ifPresentOrElse(entity -> {
                if (StringUtils.isNotBlank(mapping.getField()) && entity.findField(mapping.getField()).isEmpty()) {
                    errors.add("未知禁止字段：" + mapping.getEntity() + "." + mapping.getField());
                }
            }, () -> errors.add("未知禁止实体：" + mapping.getEntity()));
        }
    }

    private void validateFilterConstraints(List<AiAgentSemanticRule.FilterConstraint> filters,
                                           String label,
                                           List<String> errors) {
        if (filters.size() > 10) {
            errors.add(label + "不能超过 10 条");
        }
        for (AiAgentSemanticRule.FilterConstraint filter : filters) {
            if (filter == null || StringUtils.isAnyBlank(filter.getEntity(), filter.getField(), filter.getOperator())) {
                errors.add(label + "的实体、字段和操作符不能为空");
                continue;
            }
            if (!schemaService.isOperatorAllowed(filter.getOperator())) {
                errors.add(label + "包含不支持的操作符：" + filter.getOperator());
            }
            schemaService.findEntity(filter.getEntity()).ifPresentOrElse(entity ->
                    entity.findField(filter.getField()).ifPresentOrElse(field -> {
                        if (!field.filterable()) {
                            errors.add(label + "字段不允许过滤：" + filter.getEntity() + "." + filter.getField());
                        }
                    }, () -> errors.add(label + "包含未知字段：" + filter.getEntity() + "." + filter.getField())),
                    () -> errors.add(label + "包含未知实体：" + filter.getEntity()));
            if (!StringUtils.equalsAny(filter.getOperator(), "is_null", "not_null") && filter.getValue() == null) {
                errors.add(label + "的 " + filter.getOperator() + " 操作必须提供值");
            }
            if (JSON.toJSONString(filter.getValue()).length() > 500) {
                errors.add(label + "的值不能超过 500 个字符");
            }
        }
    }

    private void validateFilterRuleContract(AiAgentSemanticRule rule, List<String> errors) {
        if (StringUtils.equals(rule.getType(), TYPE_FILTER_VALUE) && rule.getRequiredFilters().isEmpty()) {
            errors.add("FILTER_VALUE 规则至少需要一个必需过滤条件");
        }
        if (rule.getMapping() == null) {
            return;
        }
        for (AiAgentSemanticRule.FilterConstraint filter : rule.getRequiredFilters()) {
            if (!StringUtils.equals(filter.getEntity(), rule.getMapping().getEntity())) {
                errors.add("必需过滤条件必须与规则目标属于同一实体");
            }
        }
    }

    private void validateExamples(List<AiAgentSemanticRule.Example> examples, List<String> errors) {
        if (examples.size() > 5) {
            errors.add("示例不能超过 5 条");
        }
        for (AiAgentSemanticRule.Example example : examples) {
            if (example == null || StringUtils.isBlank(example.getQuestion())) {
                errors.add("示例问题不能为空");
                continue;
            }
            if (example.getQuestion().length() > 500) {
                errors.add("示例问题不能超过 500 个字符");
            }
            if (StringUtils.isNotBlank(example.getExpectedEntity())) {
                schemaService.findEntity(example.getExpectedEntity()).ifPresentOrElse(entity -> {
                    if (StringUtils.isNotBlank(example.getExpectedField())
                            && entity.findField(example.getExpectedField()).isEmpty()) {
                        errors.add("示例包含未知字段：" + example.getExpectedEntity() + "." + example.getExpectedField());
                    }
                }, () -> errors.add("示例包含未知实体：" + example.getExpectedEntity()));
            }
        }
    }

    private void validatePriorityAndDates(AiAgentSemanticRule rule, List<String> errors) {
        if (rule.getPriority() < 0 || rule.getPriority() > 1000) {
            errors.add("优先级必须在 0 到 1000 之间");
        }
        if (rule.getEffectiveFrom() != null && rule.getEffectiveTo() != null
                && rule.getEffectiveFrom() > rule.getEffectiveTo()) {
            errors.add("生效开始时间不能晚于结束时间");
        }
    }

    private void validateSource(AiAgentSemanticRule.Source source, String markdown, boolean required, List<String> errors) {
        if (!required) {
            return;
        }
        if (source == null || StringUtils.isAnyBlank(source.getDocumentId(), source.getQuote())) {
            errors.add("来源文档和来源原文不能为空");
            return;
        }
        if (source.getQuote().length() > 500) {
            errors.add("来源原文不能超过 500 个字符");
        }
        if (StringUtils.isBlank(markdown) || !markdown.contains(source.getQuote())) {
            errors.add("来源原文无法在规范化文档中定位");
        }
    }

    private List<String> normalizeAliases(List<String> aliases, String canonicalTerm) {
        Set<String> result = new LinkedHashSet<>();
        for (String alias : aliases == null ? List.<String>of() : aliases) {
            String normalized = normalizeText(alias);
            if (StringUtils.isNotBlank(normalized) && !StringUtils.equalsIgnoreCase(normalized, canonicalTerm)) {
                result.add(normalized);
            }
        }
        return new ArrayList<>(result);
    }

    private List<AiAgentSemanticRule.ForbiddenMapping> normalizeForbiddenMappings(
            List<AiAgentSemanticRule.ForbiddenMapping> mappings) {
        Map<String, AiAgentSemanticRule.ForbiddenMapping> result = new LinkedHashMap<>();
        for (AiAgentSemanticRule.ForbiddenMapping mapping : mappings == null
                ? List.<AiAgentSemanticRule.ForbiddenMapping>of() : mappings) {
            if (mapping == null) {
                continue;
            }
            mapping.setEntity(normalizeKey(mapping.getEntity()));
            mapping.setField(normalizeKey(mapping.getField()));
            mapping.setReason(StringUtils.trimToNull(mapping.getReason()));
            result.put(mapping.getEntity() + "." + StringUtils.defaultString(mapping.getField()), mapping);
        }
        return new ArrayList<>(result.values());
    }

    private List<AiAgentSemanticRule.Example> normalizeExamples(List<AiAgentSemanticRule.Example> examples) {
        Map<String, AiAgentSemanticRule.Example> result = new LinkedHashMap<>();
        for (AiAgentSemanticRule.Example example : examples == null ? List.<AiAgentSemanticRule.Example>of() : examples) {
            if (example == null) {
                continue;
            }
            example.setQuestion(StringUtils.trimToNull(example.getQuestion()));
            example.setExpectedEntity(normalizeKey(example.getExpectedEntity()));
            example.setExpectedField(normalizeKey(example.getExpectedField()));
            result.put(StringUtils.defaultString(example.getQuestion()) + "|"
                    + StringUtils.defaultString(example.getExpectedEntity()) + "|"
                    + StringUtils.defaultString(example.getExpectedField()), example);
        }
        return new ArrayList<>(result.values());
    }

    private List<AiAgentSemanticRule.FilterConstraint> normalizeFilterConstraints(
            List<AiAgentSemanticRule.FilterConstraint> filters) {
        Map<String, AiAgentSemanticRule.FilterConstraint> result = new LinkedHashMap<>();
        for (AiAgentSemanticRule.FilterConstraint filter : filters == null
                ? List.<AiAgentSemanticRule.FilterConstraint>of() : filters) {
            if (filter == null) {
                continue;
            }
            filter.setEntity(normalizeKey(filter.getEntity()));
            filter.setField(normalizeKey(filter.getField()));
            filter.setOperator(normalizeKey(filter.getOperator()));
            if (filter.getValue() instanceof String value) {
                filter.setValue(StringUtils.trim(value));
            }
            String key = filter.getEntity() + "." + filter.getField() + "|"
                    + filter.getOperator() + "|" + JSON.toJSONString(filter.getValue());
            result.put(key, filter);
        }
        return new ArrayList<>(result.values());
    }

    private List<String> sortedJsonValues(List<?> values) {
        return (values == null ? List.of() : values).stream()
                .map(JSON::toJSONString)
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    private String normalizeText(String value) {
        String normalized = Normalizer.normalize(StringUtils.defaultString(value), Normalizer.Form.NFKC)
                .replaceAll("[\\r\\n]+", " ")
                .trim();
        return StringUtils.trimToNull(normalized);
    }

    private String normalizeLookup(String value) {
        return StringUtils.lowerCase(StringUtils.defaultString(normalizeText(value)), Locale.ROOT);
    }

    private String normalizeKey(String value) {
        return StringUtils.lowerCase(StringUtils.trimToNull(value), Locale.ROOT);
    }

    private boolean containsControlCharacter(String value) {
        return value.chars().anyMatch(character -> Character.isISOControl(character));
    }

    public record SourceLocation(Integer pageNo, String sectionPath) {
    }
}
