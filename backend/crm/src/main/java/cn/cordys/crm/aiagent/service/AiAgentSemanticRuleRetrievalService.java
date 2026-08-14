package cn.cordys.crm.aiagent.service;

import cn.cordys.common.util.JSON;
import cn.cordys.crm.aiagent.config.AiAgentSemanticRagProperties;
import cn.cordys.crm.aiagent.domain.AiKnowledgeChunk;
import cn.cordys.crm.aiagent.dto.semantic.AiAgentSemanticRule;
import cn.cordys.crm.aiagent.dto.semantic.AiAgentSemanticRuleMatch;
import cn.cordys.crm.aiagent.mapper.AiAgentKnowledgeMapper;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class AiAgentSemanticRuleRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(AiAgentSemanticRuleRetrievalService.class);
    private static final int HARD_MAX_RULES = 3;

    private final AiAgentKnowledgeMapper knowledgeMapper;
    private final AiAgentSemanticSchemaService schemaService;
    private final AiAgentSemanticRagProperties properties;

    public AiAgentSemanticRuleRetrievalService(AiAgentKnowledgeMapper knowledgeMapper,
                                               AiAgentSemanticSchemaService schemaService,
                                               AiAgentSemanticRagProperties properties) {
        this.knowledgeMapper = knowledgeMapper;
        this.schemaService = schemaService;
        this.properties = properties;
    }

    public RetrievalResult retrieve(String question, String organizationId) {
        if (!properties.isEnabled() && !properties.isShadowEnabled()) {
            return RetrievalResult.empty("DISABLED");
        }
        if (StringUtils.isBlank(question) || StringUtils.isBlank(organizationId)) {
            return RetrievalResult.empty("BLANK_QUERY_OR_ORGANIZATION");
        }
        try {
            List<AiKnowledgeChunk> published = knowledgeMapper.listPublishedSemanticRuleChunks(organizationId);
            List<Candidate> initialCandidates = candidates(question, organizationId, published);
            if (initialCandidates.isEmpty()) {
                return RetrievalResult.empty("NO_EXACT_MATCH");
            }

            List<String> chunkIds = initialCandidates.stream()
                    .map(candidate -> candidate.match().getChunkId())
                    .filter(StringUtils::isNotBlank)
                    .distinct()
                    .toList();
            if (chunkIds.isEmpty()) {
                return RetrievalResult.empty("NO_VALID_RULE_ID");
            }
            List<AiKnowledgeChunk> revalidated = knowledgeMapper.revalidatePublishedSemanticRuleChunks(
                    organizationId, chunkIds);
            List<Candidate> currentCandidates = candidates(question, organizationId, revalidated);
            if (currentCandidates.isEmpty()) {
                return RetrievalResult.empty("RULE_WITHDRAWN_DURING_REVALIDATION");
            }

            String conflictReason = conflictReason(currentCandidates);
            if (conflictReason != null) {
                return new RetrievalResult(limitedMatches(currentCandidates), true, conflictReason);
            }
            return new RetrievalResult(limitedMatches(selectNonOverlapping(currentCandidates)), false, null);
        } catch (RuntimeException e) {
            log.warn("AI agent semantic rule retrieval failed closed: organizationId={}, error={}",
                    organizationId, e.toString());
            log.debug("AI agent semantic rule retrieval failure detail", e);
            return RetrievalResult.empty("RETRIEVAL_FAILED");
        }
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public boolean isShadowEnabled() {
        return properties.isShadowEnabled();
    }

    private List<Candidate> candidates(String question, String organizationId, List<AiKnowledgeChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        List<Candidate> candidates = new ArrayList<>();
        for (AiKnowledgeChunk chunk : chunks) {
            if (chunk == null || !StringUtils.equals(chunk.getOrganizationId(), organizationId)) {
                continue;
            }
            AiAgentSemanticRule rule = parseAndValidate(chunk);
            if (rule == null) {
                continue;
            }
            Candidate candidate = bestCandidate(question, chunk, rule);
            if (candidate != null) {
                candidates.add(candidate);
            }
        }
        return candidates;
    }

    private AiAgentSemanticRule parseAndValidate(AiKnowledgeChunk chunk) {
        try {
            AiAgentSemanticRule rule = JSON.parseObject(chunk.getContent(), AiAgentSemanticRule.class);
            if (!isPublishedAndValid(rule, chunk)) {
                return null;
            }
            return rule;
        } catch (RuntimeException e) {
            log.warn("Ignoring invalid published semantic rule: documentId={}, chunkId={}, error={}",
                    chunk.getDocumentId(), chunk.getId(), e.toString());
            return null;
        }
    }

    private boolean isPublishedAndValid(AiAgentSemanticRule rule, AiKnowledgeChunk chunk) {
        if (rule == null
                || !StringUtils.equals(rule.getSchemaVersion(), "1.0")
                || !StringUtils.equalsAny(rule.getType(),
                AiAgentSemanticRuleValidationService.TYPE_TERM_MAPPING,
                AiAgentSemanticRuleValidationService.TYPE_FILTER_VALUE)
                || !StringUtils.equals(rule.getScope(), "CRM_DATABASE_QUERY")
                || rule.getVersion() == null
                || rule.getVersion() <= 0
                || StringUtils.isBlank(rule.getRuleId())
                || StringUtils.isBlank(rule.getCanonicalTerm())
                || rule.getMapping() == null
                || StringUtils.isBlank(rule.getMapping().getEntity())
                || StringUtils.isBlank(rule.getMapping().getField())
                || rule.getReview() == null
                || !StringUtils.equals(rule.getReview().getStatus(), "APPROVED")
                || (rule.getValidationErrors() != null && !rule.getValidationErrors().isEmpty())) {
            return false;
        }
        if (StringUtils.equals(rule.getType(), AiAgentSemanticRuleValidationService.TYPE_FILTER_VALUE)
                && safeList(rule.getRequiredFilters()).isEmpty()) {
            return false;
        }
        long now = Instant.now().toEpochMilli();
        if ((rule.getEffectiveFrom() != null && rule.getEffectiveFrom() > now)
                || (rule.getEffectiveTo() != null && rule.getEffectiveTo() < now)) {
            return false;
        }
        if (rule.getSource() != null
                && StringUtils.isNotBlank(rule.getSource().getDocumentId())
                && !StringUtils.equals(rule.getSource().getDocumentId(), chunk.getDocumentId())) {
            return false;
        }
        AiAgentSemanticSchemaService.EntitySpec entity = schemaService.findEntity(rule.getMapping().getEntity())
                .orElse(null);
        if (entity == null
                || entity.resolveField(rule.getMapping().getField()).isEmpty()
                || !StringUtils.equalsIgnoreCase(entity.dataSourceKind().name(), rule.getMapping().getDataSource())) {
            return false;
        }
        for (AiAgentSemanticRule.ForbiddenMapping forbidden : safeList(rule.getForbiddenMappings())) {
            AiAgentSemanticSchemaService.EntitySpec forbiddenEntity = schemaService.findEntity(forbidden.getEntity())
                    .orElse(null);
            if (forbiddenEntity == null
                    || (StringUtils.isNotBlank(forbidden.getField())
                    && forbiddenEntity.resolveField(forbidden.getField()).isEmpty())) {
                return false;
            }
        }
        return validFilters(rule.getRequiredFilters()) && validFilters(rule.getForbiddenFilters());
    }

    private Candidate bestCandidate(String question, AiKnowledgeChunk chunk, AiAgentSemanticRule rule) {
        List<TermHit> hits = new ArrayList<>();
        addHit(hits, question, rule.getCanonicalTerm(), "CANONICAL_TERM", 1.0, 0.96);
        for (String alias : safeList(rule.getAliases())) {
            addHit(hits, question, alias, "ALIAS", 0.98, 0.94);
        }
        TermHit hit = hits.stream()
                .sorted(Comparator.comparingInt(TermHit::length).reversed()
                        .thenComparing(Comparator.comparingDouble(TermHit::score).reversed()))
                .findFirst()
                .orElse(null);
        if (hit == null) {
            return null;
        }
        AiAgentSemanticRuleMatch match = toMatch(rule, chunk, hit);
        return new Candidate(match, hit.normalizedTerm(), hit.start(), hit.end(), hit.length());
    }

    private void addHit(List<TermHit> hits, String question, String term, String matchedBy,
                        double directScore, double normalizedScore) {
        String rawQuestion = StringUtils.defaultString(question).toLowerCase(Locale.ROOT);
        String rawTerm = StringUtils.defaultString(term).trim().toLowerCase(Locale.ROOT);
        if (StringUtils.isBlank(rawTerm)) {
            return;
        }
        int directIndex = rawQuestion.indexOf(rawTerm);
        if (directIndex >= 0) {
            hits.add(new TermHit(matchedBy, directScore, normalizeText(rawTerm),
                    directIndex, directIndex + rawTerm.length(), rawTerm.length()));
            return;
        }
        String normalizedQuestion = normalizeText(question);
        String normalizedTerm = normalizeText(term);
        int normalizedIndex = normalizedQuestion.indexOf(normalizedTerm);
        if (normalizedIndex >= 0) {
            hits.add(new TermHit(matchedBy + "_NORMALIZED", normalizedScore, normalizedTerm,
                    normalizedIndex, normalizedIndex + normalizedTerm.length(), normalizedTerm.length()));
        }
    }

    private AiAgentSemanticRuleMatch toMatch(AiAgentSemanticRule rule, AiKnowledgeChunk chunk, TermHit hit) {
        AiAgentSemanticRuleMatch match = new AiAgentSemanticRuleMatch();
        match.setRuleId(rule.getRuleId());
        match.setVersion(rule.getVersion());
        match.setCanonicalTerm(rule.getCanonicalTerm());
        match.setAliases(List.copyOf(safeList(rule.getAliases())));
        match.setRuleType(rule.getType());
        match.setInstruction(StringUtils.firstNonBlank(
                rule.getInstruction(),
                rule.getDefinition(),
                rule.getSource() == null ? null : rule.getSource().getQuote()));
        match.setTarget(target(rule.getMapping().getEntity(), rule.getMapping().getField()));
        match.setForbiddenTargets(safeList(rule.getForbiddenMappings()).stream()
                .map(forbidden -> target(forbidden.getEntity(), forbidden.getField()))
                .toList());
        match.setRequiredFilters(safeList(rule.getRequiredFilters()).stream()
                .map(this::filterConstraint)
                .toList());
        match.setForbiddenFilters(safeList(rule.getForbiddenFilters()).stream()
                .map(this::filterConstraint)
                .toList());
        match.setPriority(rule.getPriority() == null ? 100 : rule.getPriority());
        match.setDocumentId(chunk.getDocumentId());
        match.setChunkId(chunk.getId());
        match.setPageNo(chunk.getPageNo());
        match.setSectionPath(chunk.getSectionPath());
        match.setMatchedBy(hit.matchedBy());
        match.setScore(hit.score());
        return match;
    }

    private AiAgentSemanticRuleMatch.Target target(String entity, String field) {
        AiAgentSemanticRuleMatch.Target target = new AiAgentSemanticRuleMatch.Target();
        target.setEntity(normalizeKey(entity));
        target.setField(StringUtils.isBlank(field) ? null : normalizeKey(field));
        return target;
    }

    private AiAgentSemanticRuleMatch.FilterConstraint filterConstraint(
            AiAgentSemanticRule.FilterConstraint source) {
        AiAgentSemanticRuleMatch.FilterConstraint target = new AiAgentSemanticRuleMatch.FilterConstraint();
        target.setEntity(normalizeKey(source.getEntity()));
        target.setField(normalizeKey(source.getField()));
        target.setOperator(normalizeKey(source.getOperator()));
        target.setValue(source.getValue());
        return target;
    }

    private boolean validFilters(List<AiAgentSemanticRule.FilterConstraint> filters) {
        for (AiAgentSemanticRule.FilterConstraint filter : safeList(filters)) {
            if (filter == null || StringUtils.isAnyBlank(
                    filter.getEntity(), filter.getField(), filter.getOperator())) {
                return false;
            }
            AiAgentSemanticSchemaService.EntitySpec entity = schemaService.findEntity(filter.getEntity()).orElse(null);
            if (entity == null || entity.resolveField(filter.getField()).isEmpty()
                    || !schemaService.isOperatorAllowed(filter.getOperator())) {
                return false;
            }
        }
        return true;
    }

    private String conflictReason(List<Candidate> candidates) {
        Map<String, Set<Integer>> versionsByRule = new HashMap<>();
        for (Candidate candidate : candidates) {
            versionsByRule.computeIfAbsent(candidate.match().getRuleId(), ignored -> new HashSet<>())
                    .add(candidate.match().getVersion());
        }
        if (versionsByRule.values().stream().anyMatch(versions -> versions.size() > 1)) {
            return "同一语义规则存在多个生效版本";
        }

        for (int leftIndex = 0; leftIndex < candidates.size(); leftIndex++) {
            Candidate left = candidates.get(leftIndex);
            for (int rightIndex = leftIndex + 1; rightIndex < candidates.size(); rightIndex++) {
                Candidate right = candidates.get(rightIndex);
                if (StringUtils.equals(left.normalizedTerm(), right.normalizedTerm())
                        && priority(left.match()) == priority(right.match())
                        && !sameTarget(left.match().getTarget(), right.match().getTarget())) {
                    return "相同术语存在冲突的已生效映射";
                }
                if (isForbidden(left.match().getTarget(), right.match().getForbiddenTargets())
                        || isForbidden(right.match().getTarget(), left.match().getForbiddenTargets())) {
                    return "已生效规则的目标与禁止目标冲突";
                }
            }
        }
        long entityCount = candidates.stream()
                .map(candidate -> candidate.match().getTarget())
                .filter(target -> target != null)
                .map(AiAgentSemanticRuleMatch.Target::getEntity)
                .map(this::normalizeKey)
                .distinct()
                .count();
        return entityCount > 1 ? "当前问题命中了多个不兼容的数据实体" : null;
    }

    private List<Candidate> selectNonOverlapping(List<Candidate> candidates) {
        List<Candidate> byLength = candidates.stream()
                .sorted(Comparator.comparingInt(Candidate::length).reversed()
                        .thenComparing(Comparator.<Candidate>comparingDouble(candidate -> candidate.match().getScore()).reversed())
                        .thenComparing(Comparator.<Candidate>comparingInt(candidate -> priority(candidate.match())).reversed())
                        .thenComparing(Comparator.<Candidate>comparingInt(candidate -> version(candidate.match())).reversed()))
                .toList();
        List<Candidate> selected = new ArrayList<>();
        Set<String> targets = new HashSet<>();
        for (Candidate candidate : byLength) {
            boolean overlaps = selected.stream().anyMatch(existing -> overlaps(existing, candidate));
            String targetKey = targetKey(candidate.match().getTarget());
            if (!overlaps && targets.add(targetKey)) {
                selected.add(candidate);
            }
        }
        return selected.stream()
                .sorted(candidateOrder())
                .toList();
    }

    private List<AiAgentSemanticRuleMatch> limitedMatches(List<Candidate> candidates) {
        int limit = Math.min(HARD_MAX_RULES, Math.max(1, properties.getMaxRules()));
        return candidates.stream()
                .sorted(candidateOrder())
                .map(Candidate::match)
                .limit(limit)
                .toList();
    }

    private Comparator<Candidate> candidateOrder() {
        return Comparator.comparingDouble((Candidate candidate) -> candidate.match().getScore()).reversed()
                .thenComparing(Comparator.comparingInt((Candidate candidate) -> priority(candidate.match())).reversed())
                .thenComparing(Comparator.comparingInt((Candidate candidate) -> version(candidate.match())).reversed())
                .thenComparing(candidate -> candidate.match().getRuleId());
    }

    private boolean overlaps(Candidate left, Candidate right) {
        return left.start() < right.end() && right.start() < left.end();
    }

    private boolean sameTarget(AiAgentSemanticRuleMatch.Target left, AiAgentSemanticRuleMatch.Target right) {
        return left != null && right != null
                && StringUtils.equals(normalizeKey(left.getEntity()), normalizeKey(right.getEntity()))
                && StringUtils.equals(normalizeKey(left.getField()), normalizeKey(right.getField()));
    }

    private boolean isForbidden(AiAgentSemanticRuleMatch.Target target,
                                List<AiAgentSemanticRuleMatch.Target> forbiddenTargets) {
        if (target == null) {
            return false;
        }
        return safeList(forbiddenTargets).stream().anyMatch(forbidden ->
                StringUtils.equals(normalizeKey(target.getEntity()), normalizeKey(forbidden.getEntity()))
                        && (StringUtils.isBlank(forbidden.getField())
                        || StringUtils.equals(normalizeKey(target.getField()), normalizeKey(forbidden.getField()))));
    }

    private int priority(AiAgentSemanticRuleMatch match) {
        return match.getPriority() == null ? 100 : match.getPriority();
    }

    private int version(AiAgentSemanticRuleMatch match) {
        return match.getVersion() == null ? 0 : match.getVersion();
    }

    private String targetKey(AiAgentSemanticRuleMatch.Target target) {
        return target == null ? "" : normalizeKey(target.getEntity()) + "." + normalizeKey(target.getField());
    }

    private String normalizeKey(String value) {
        return StringUtils.defaultString(value).trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeText(String value) {
        return Normalizer.normalize(StringUtils.defaultString(value), Normalizer.Form.NFKC)
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    public record RetrievalResult(List<AiAgentSemanticRuleMatch> matches, boolean conflict, String fallbackReason) {
        public RetrievalResult {
            matches = matches == null ? List.of() : List.copyOf(matches);
        }

        static RetrievalResult empty(String fallbackReason) {
            return new RetrievalResult(List.of(), false, fallbackReason);
        }
    }

    private record Candidate(AiAgentSemanticRuleMatch match, String normalizedTerm,
                             int start, int end, int length) {
    }

    private record TermHit(String matchedBy, double score, String normalizedTerm,
                           int start, int end, int length) {
    }
}
