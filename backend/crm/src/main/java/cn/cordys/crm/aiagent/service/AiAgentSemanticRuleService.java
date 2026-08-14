package cn.cordys.crm.aiagent.service;

import cn.cordys.common.exception.GenericException;
import cn.cordys.common.pager.PageUtils;
import cn.cordys.common.pager.Pager;
import cn.cordys.crm.aiagent.domain.AiKnowledgeChunk;
import cn.cordys.crm.aiagent.domain.AiKnowledgeDocument;
import cn.cordys.crm.aiagent.dto.request.AiSemanticRuleBatchReviewRequest;
import cn.cordys.crm.aiagent.dto.request.AiSemanticRulePageRequest;
import cn.cordys.crm.aiagent.dto.request.AiSemanticRuleReviewRequest;
import cn.cordys.crm.aiagent.dto.request.AiSemanticRuleSaveRequest;
import cn.cordys.crm.aiagent.dto.request.AiSemanticRuleVersionPageRequest;
import cn.cordys.crm.aiagent.dto.request.AiSemanticRuleVersionSwitchRequest;
import cn.cordys.crm.aiagent.dto.response.AiSemanticRuleResponse;
import cn.cordys.crm.aiagent.dto.response.AiSemanticRuleStats;
import cn.cordys.crm.aiagent.dto.response.AiSemanticSchemaOptionsResponse;
import cn.cordys.crm.aiagent.dto.semantic.AiAgentSemanticRule;
import cn.cordys.crm.aiagent.mapper.AiAgentKnowledgeMapper;
import cn.cordys.mybatis.BaseMapper;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
@Transactional(rollbackFor = Exception.class)
public class AiAgentSemanticRuleService {

    private static final String LOCK_PREFIX = "ai-agent:semantic-publish:";
    private static final Set<String> REVIEWABLE_STATUSES = Set.of(
            AiAgentSemanticRuleValidationService.REVIEW_APPROVED,
            AiAgentSemanticRuleValidationService.REVIEW_REJECTED
    );

    @Resource
    private AiAgentKnowledgeMapper knowledgeMapper;
    @Resource
    private BaseMapper<AiKnowledgeDocument> documentMapper;
    @Resource
    private AiAgentSemanticRuleValidationService validationService;
    @Resource
    private ApplicationEventPublisher eventPublisher;
    @Autowired(required = false)
    private Redisson redisson;

    public Pager<List<AiSemanticRuleResponse>> pageRules(AiSemanticRulePageRequest request, String orgId) {
        AiKnowledgeDocument document = requireSemanticDocument(request.getDocumentId(), orgId);
        Page<Object> page = PageHelper.startPage(request.getCurrent(), request.getPageSize());
        List<AiSemanticRuleResponse> responses = knowledgeMapper.listSemanticRuleChunks(request, orgId).stream()
                .map(chunk -> toResponse(chunk, document))
                .toList();
        return PageUtils.setPageInfo(page, responses);
    }

    public AiSemanticRuleResponse saveRule(String chunkId,
                                           AiSemanticRuleSaveRequest request,
                                           String orgId,
                                           String userId) {
        AiKnowledgeChunk chunk = requireRuleChunk(chunkId, orgId);
        AiKnowledgeDocument document = requireEditableDocument(chunk.getDocumentId(), orgId);
        AiAgentSemanticRule rule = parseRule(chunk);
        if (rule.getVersion() != null && rule.getVersion() > 0) {
            throw new GenericException("已发布版本不能原地编辑，请上传新文档生成新版本");
        }

        rule.setCanonicalTerm(request.getCanonicalTerm());
        rule.setAliases(request.getAliases());
        rule.setDefinition(request.getDefinition());
        AiAgentSemanticRule.Mapping mapping = new AiAgentSemanticRule.Mapping();
        mapping.setEntity(request.getMapping().getEntity());
        mapping.setField(request.getMapping().getField());
        rule.setMapping(mapping);
        rule.setForbiddenMappings(request.getForbiddenMappings());
        rule.setExamples(request.getExamples());
        rule.setPriority(request.getPriority());
        rule.setEffectiveFrom(request.getEffectiveFrom());
        rule.setEffectiveTo(request.getEffectiveTo());
        validationService.normalize(rule);
        rule.setRuleId(validationService.generateRuleId(orgId, rule.getScope(), rule.getCanonicalTerm()));
        List<String> errors = validationService.validate(rule, normalizedMarkdown(document), true);
        rule.setValidationErrors(errors);
        resetReview(rule, errors.isEmpty()
                ? AiAgentSemanticRuleValidationService.REVIEW_PENDING
                : AiAgentSemanticRuleValidationService.REVIEW_INVALID, null, null);

        long updateTime = nextUpdateTime(request.getExpectedUpdateTime());
        int updated = knowledgeMapper.updateSemanticRuleChunkOptimistic(
                chunkId,
                orgId,
                request.getExpectedUpdateTime(),
                rule.getCanonicalTerm(),
                validationService.serialize(rule),
                validationService.semanticPayloadHash(rule),
                0,
                userId,
                updateTime
        );
        requireOptimisticUpdate(updated);
        return toResponse(requireRuleChunk(chunkId, orgId), document);
    }

    public AiSemanticRuleResponse reviewRule(String chunkId,
                                             AiSemanticRuleReviewRequest request,
                                             String orgId,
                                             String userId) {
        AiKnowledgeChunk chunk = requireRuleChunk(chunkId, orgId);
        AiKnowledgeDocument document = requireEditableDocument(chunk.getDocumentId(), orgId);
        AiAgentSemanticRule rule = parseRule(chunk);
        if (rule.getVersion() != null && rule.getVersion() > 0) {
            throw new GenericException("已发布版本不能重新审核");
        }
        String status = StringUtils.upperCase(StringUtils.trim(request.getStatus()), Locale.ROOT);
        if (!REVIEWABLE_STATUSES.contains(status)) {
            throw new GenericException("审核状态只允许 APPROVED 或 REJECTED");
        }
        List<String> errors = validationService.validate(rule, normalizedMarkdown(document), true);
        rule.setValidationErrors(errors);
        if (StringUtils.equals(status, AiAgentSemanticRuleValidationService.REVIEW_APPROVED) && !errors.isEmpty()) {
            throw new GenericException("规则校验未通过，不能审核通过：" + String.join("；", errors));
        }
        resetReview(rule, status, userId, request.getComment());
        long updateTime = nextUpdateTime(request.getExpectedUpdateTime());
        int updated = knowledgeMapper.updateSemanticRuleChunkOptimistic(
                chunkId,
                orgId,
                request.getExpectedUpdateTime(),
                rule.getCanonicalTerm(),
                validationService.serialize(rule),
                validationService.semanticPayloadHash(rule),
                0,
                userId,
                updateTime
        );
        requireOptimisticUpdate(updated);
        return toResponse(requireRuleChunk(chunkId, orgId), document);
    }

    public List<AiSemanticRuleResponse> reviewRules(AiSemanticRuleBatchReviewRequest request,
                                                    String orgId,
                                                    String userId) {
        List<AiSemanticRuleResponse> result = new ArrayList<>();
        for (AiSemanticRuleBatchReviewRequest.Item item : request.getItems()) {
            result.add(reviewRule(item.getChunkId(), item, orgId, userId));
        }
        return result;
    }

    public AiSemanticSchemaOptionsResponse schemaOptions() {
        return validationService.schemaOptions();
    }

    public AiSemanticRuleStats ruleStats(String documentId, String orgId) {
        requireSemanticDocument(documentId, orgId);
        AiSemanticRuleStats stats = new AiSemanticRuleStats();
        for (AiKnowledgeChunk chunk : knowledgeMapper.listSemanticRuleChunksByDocument(documentId, orgId)) {
            stats.setTotal(stats.getTotal() + 1);
            String status = reviewStatus(parseRule(chunk));
            switch (status) {
                case AiAgentSemanticRuleValidationService.REVIEW_APPROVED -> stats.setApproved(stats.getApproved() + 1);
                case AiAgentSemanticRuleValidationService.REVIEW_REJECTED -> stats.setRejected(stats.getRejected() + 1);
                case AiAgentSemanticRuleValidationService.REVIEW_INVALID -> stats.setInvalid(stats.getInvalid() + 1);
                default -> stats.setPending(stats.getPending() + 1);
            }
        }
        return stats;
    }

    public String semanticStatus(AiKnowledgeDocument document, AiSemanticRuleStats stats) {
        if (!isSemanticDocument(document)) {
            return null;
        }
        if (StringUtils.equalsAny(document.getParseStatus(), "UPLOADED", "PARSING")) {
            return "PARSING";
        }
        if (StringUtils.equals(document.getParseStatus(), "FAILED")) {
            return "FAILED";
        }
        if (Objects.equals(document.getEnabled(), 1) && stats.getApproved() > 0) {
            return "ACTIVE";
        }
        return "INACTIVE";
    }

    public void publish(String documentId, String orgId, String userId) {
        acquireTransactionLock(orgId);
        AiKnowledgeDocument document = knowledgeMapper.getSemanticDocumentForUpdate(documentId, orgId);
        if (document == null) {
            throw new GenericException("语义规则文档不存在或无权限");
        }
        if (!StringUtils.equals(document.getParseStatus(), "PARSED")) {
            throw new GenericException("只有解析成功的知识文档才能生效");
        }
        List<AiKnowledgeChunk> chunks = knowledgeMapper.listSemanticRuleChunksByDocument(documentId, orgId);
        List<RuleChunk> approved = new ArrayList<>();
        for (AiKnowledgeChunk chunk : chunks) {
            AiAgentSemanticRule rule = parseRule(chunk);
            String status = reviewStatus(rule);
            if (StringUtils.equalsAny(status,
                    AiAgentSemanticRuleValidationService.REVIEW_PENDING,
                    AiAgentSemanticRuleValidationService.REVIEW_INVALID)) {
                throw new GenericException("存在未通过自动校验的规则，文档不能生效");
            }
            if (StringUtils.equals(status, AiAgentSemanticRuleValidationService.REVIEW_APPROVED)) {
                List<String> errors = validationService.validate(rule, normalizedMarkdown(document), true);
                if (!errors.isEmpty() || !validationService.isEffective(rule, System.currentTimeMillis())) {
                    throw new GenericException("规则生效校验失败：" + rule.getCanonicalTerm() + "："
                            + String.join("；", errors.isEmpty() ? List.of("规则不在有效期内") : errors));
                }
                approved.add(new RuleChunk(chunk, rule));
            }
        }
        if (approved.isEmpty()) {
            throw new GenericException("至少需要一条通过自动校验的规则才能生效");
        }
        validateConflicts(approved, knowledgeMapper.listPublishedSemanticRuleChunks(orgId));

        long now = System.currentTimeMillis();
        for (RuleChunk item : approved) {
            AiAgentSemanticRule rule = item.rule();
            if (rule.getVersion() == null || rule.getVersion() <= 0) {
                rule.setVersion(knowledgeMapper.maxSemanticRuleVersion(rule.getRuleId(), orgId) + 1);
            }
            knowledgeMapper.disableOtherSemanticRuleVersions(rule.getRuleId(), item.chunk().getId(), orgId, userId, now);
            int updated = knowledgeMapper.updateSemanticRuleChunk(
                    item.chunk().getId(), orgId, rule.getCanonicalTerm(), validationService.serialize(rule),
                    validationService.semanticPayloadHash(rule), 1, userId, now);
            if (updated != 1) {
                throw new GenericException("规则自动生效失败，请重试");
            }
        }
        for (AiKnowledgeChunk chunk : chunks) {
            if (approved.stream().noneMatch(item -> StringUtils.equals(item.chunk().getId(), chunk.getId()))) {
                knowledgeMapper.updateSemanticRuleChunkEnabled(chunk.getId(), orgId, 0, userId, now);
            }
        }
        if (knowledgeMapper.updateSemanticDocumentEnabled(documentId, orgId, 1, userId, now) != 1) {
            throw new GenericException("知识文档自动生效失败，请重试");
        }
        eventPublisher.publishEvent(new AiAgentSemanticRulesChangedEvent(orgId));
    }

    public void withdraw(String documentId, String orgId, String userId) {
        acquireTransactionLock(orgId);
        AiKnowledgeDocument document = knowledgeMapper.getSemanticDocumentForUpdate(documentId, orgId);
        if (document == null) {
            throw new GenericException("语义规则文档不存在或无权限");
        }
        long now = System.currentTimeMillis();
        knowledgeMapper.updateSemanticChunksEnabledByDocument(documentId, orgId, 0, userId, now);
        knowledgeMapper.updateSemanticDocumentEnabled(documentId, orgId, 0, userId, now);
        eventPublisher.publishEvent(new AiAgentSemanticRulesChangedEvent(orgId));
    }

    public Pager<List<AiSemanticRuleResponse>> pageVersions(AiSemanticRuleVersionPageRequest request, String orgId) {
        Page<Object> page = PageHelper.startPage(request.getCurrent(), request.getPageSize());
        List<AiSemanticRuleResponse> result = knowledgeMapper.listSemanticRuleVersions(request.getRuleId(), orgId).stream()
                .map(chunk -> toResponse(chunk, requireSemanticDocument(chunk.getDocumentId(), orgId)))
                .toList();
        return PageUtils.setPageInfo(page, result);
    }

    public AiSemanticRuleResponse switchVersion(AiSemanticRuleVersionSwitchRequest request,
                                                String orgId,
                                                String userId) {
        acquireTransactionLock(orgId);
        List<AiKnowledgeChunk> active = knowledgeMapper.listActiveSemanticRuleVersions(request.getRuleId(), orgId);
        if (active.size() > 1) {
            throw new GenericException("检测到多个生效版本，已拒绝自动切换");
        }
        Integer activeVersion = active.isEmpty() ? null : parseRule(active.get(0)).getVersion();
        if (!Objects.equals(activeVersion, request.getExpectedActiveVersion())) {
            throw conflict("当前生效版本已变化，请刷新后重试");
        }
        AiKnowledgeChunk target = knowledgeMapper.getSemanticRuleVersion(
                request.getRuleId(), request.getTargetVersion(), orgId);
        if (target == null) {
            throw new GenericException("目标规则版本不存在");
        }
        AiAgentSemanticRule rule = parseRule(target);
        if (!StringUtils.equals(reviewStatus(rule), AiAgentSemanticRuleValidationService.REVIEW_APPROVED)) {
            throw new GenericException("目标版本未审核通过");
        }
        AiKnowledgeDocument document = knowledgeMapper.getSemanticDocumentForUpdate(target.getDocumentId(), orgId);
        if (document == null) {
            throw new GenericException("目标版本来源文档不存在或无权限");
        }
        if (!StringUtils.equals(document.getParseStatus(), "PARSED")) {
            throw new GenericException("目标版本来源文档未解析成功，不能切换版本");
        }
        List<String> errors = validationService.validate(rule, normalizedMarkdown(document), true);
        if (!errors.isEmpty() || !validationService.isEffective(rule, System.currentTimeMillis())) {
            throw new GenericException("目标版本已失效或不再符合当前 schema");
        }
        List<AiKnowledgeChunk> remainingPublished = knowledgeMapper.listPublishedSemanticRuleChunks(orgId).stream()
                .filter(chunk -> !StringUtils.equals(parseRule(chunk).getRuleId(), request.getRuleId()))
                .toList();
        validateConflicts(List.of(new RuleChunk(target, rule)), remainingPublished);
        long now = System.currentTimeMillis();
        knowledgeMapper.disableOtherSemanticRuleVersions(request.getRuleId(), target.getId(), orgId, userId, now);
        if (knowledgeMapper.updateSemanticRuleChunkEnabled(target.getId(), orgId, 1, userId, now) != 1) {
            throw new GenericException("目标规则版本启用失败，请刷新后重试");
        }
        if (knowledgeMapper.updateSemanticDocumentEnabled(target.getDocumentId(), orgId, 1, userId, now) != 1) {
            throw new GenericException("目标规则来源文档启用失败，请刷新后重试");
        }
        eventPublisher.publishEvent(new AiAgentSemanticRulesChangedEvent(orgId));
        return toResponse(requireRuleChunk(target.getId(), orgId), document);
    }

    public AiAgentSemanticRule parseRule(AiKnowledgeChunk chunk) {
        try {
            return validationService.deserialize(chunk.getContent());
        } catch (RuntimeException e) {
            throw new GenericException("语义规则 JSON 无效，chunkId=" + chunk.getId());
        }
    }

    public boolean isSemanticDocument(AiKnowledgeDocument document) {
        return document != null && StringUtils.equals(document.getCategory(), AiAgentSemanticRuleValidationService.CATEGORY);
    }

    private AiKnowledgeDocument requireEditableDocument(String documentId, String orgId) {
        AiKnowledgeDocument document = knowledgeMapper.getSemanticDocumentForUpdate(documentId, orgId);
        if (document == null) {
            throw new GenericException("语义规则文档不存在或无权限");
        }
        if (Objects.equals(document.getEnabled(), 1)) {
            throw new GenericException("已发布语义文档不能原地编辑或重新审核，请先创建新版本");
        }
        return document;
    }

    private AiKnowledgeDocument requireSemanticDocument(String documentId, String orgId) {
        AiKnowledgeDocument document = documentMapper.selectByPrimaryKey(documentId);
        if (document == null || !Objects.equals(document.getOrganizationId(), orgId) || !isSemanticDocument(document)) {
            throw new GenericException("语义规则文档不存在或无权限");
        }
        return document;
    }

    private AiKnowledgeChunk requireRuleChunk(String chunkId, String orgId) {
        AiKnowledgeChunk chunk = knowledgeMapper.getSemanticRuleChunk(chunkId, orgId);
        if (chunk == null) {
            throw new GenericException("语义规则不存在或无权限");
        }
        return chunk;
    }

    private AiSemanticRuleResponse toResponse(AiKnowledgeChunk chunk, AiKnowledgeDocument document) {
        AiSemanticRuleResponse response = new AiSemanticRuleResponse();
        response.setChunkId(chunk.getId());
        response.setDocumentId(chunk.getDocumentId());
        response.setDocumentName(document == null ? null : document.getName());
        response.setChunkIndex(chunk.getChunkIndex());
        response.setEnabled(chunk.getEnabled());
        response.setCreateTime(chunk.getCreateTime());
        response.setUpdateTime(chunk.getUpdateTime());
        response.setRule(parseRule(chunk));
        return response;
    }

    private String normalizedMarkdown(AiKnowledgeDocument document) {
        Path original = Path.of(document.getStoragePath());
        Path markdown = StringUtils.equals(document.getFileType(), "md")
                ? original
                : original.resolveSibling("normalized.md");
        try {
            return Files.readString(markdown, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new GenericException("规范化 Markdown 不存在或无法读取");
        }
    }

    private void resetReview(AiAgentSemanticRule rule, String status, String reviewerId, String comment) {
        AiAgentSemanticRule.Review review = rule.getReview() == null
                ? new AiAgentSemanticRule.Review() : rule.getReview();
        review.setStatus(status);
        review.setReviewerId(reviewerId);
        review.setReviewedAt(reviewerId == null ? null : System.currentTimeMillis());
        review.setComment(StringUtils.trimToNull(comment));
        rule.setReview(review);
    }

    private String reviewStatus(AiAgentSemanticRule rule) {
        return rule.getReview() == null
                ? AiAgentSemanticRuleValidationService.REVIEW_PENDING
                : StringUtils.defaultIfBlank(rule.getReview().getStatus(), AiAgentSemanticRuleValidationService.REVIEW_PENDING);
    }

    private long nextUpdateTime(long expectedUpdateTime) {
        return Math.max(System.currentTimeMillis(), expectedUpdateTime + 1);
    }

    private void requireOptimisticUpdate(int updated) {
        if (updated != 1) {
            throw conflict("规则已被其他用户修改，请刷新后重试");
        }
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private void acquireTransactionLock(String orgId) {
        if (redisson == null) {
            throw new GenericException("知识规则生效锁服务不可用，已拒绝本次操作");
        }
        RLock lock = redisson.getLock(LOCK_PREFIX + orgId);
        boolean locked;
        try {
            locked = lock.tryLock(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GenericException("等待知识规则生效锁时被中断");
        }
        if (!locked) {
            throw conflict("其他知识文档正在生效，请稍后重试");
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            lock.unlock();
            throw new GenericException("知识规则生效必须在事务中执行");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        });
    }

    private void validateConflicts(List<RuleChunk> candidates, List<AiKnowledgeChunk> publishedChunks) {
        List<AiAgentSemanticRule> published = publishedChunks.stream().map(this::parseRule).toList();
        Set<String> candidateRuleIds = new LinkedHashSet<>();
        for (RuleChunk candidate : candidates) {
            if (!candidateRuleIds.add(candidate.rule().getRuleId())) {
                throw new GenericException("同一文档存在重复 ruleId：" + candidate.rule().getRuleId());
            }
        }
        Map<String, AiAgentSemanticRule> terms = new HashMap<>();
        for (AiAgentSemanticRule rule : concatRules(candidates, published)) {
            for (String term : allTerms(rule)) {
                String key = normalizeTerm(term) + "|" + rule.getScope() + "|" + rule.getPriority();
                AiAgentSemanticRule previous = terms.putIfAbsent(key, rule);
                if (previous != null
                        && !StringUtils.equals(previous.getRuleId(), rule.getRuleId())
                        && !sameTarget(previous, rule)) {
                    throw new GenericException("术语映射冲突：" + term);
                }
            }
        }
        for (RuleChunk candidate : candidates) {
            AiAgentSemanticRule rule = candidate.rule();
            for (AiAgentSemanticRule other : concatRules(candidates, published)) {
                if (StringUtils.equals(rule.getRuleId(), other.getRuleId())) {
                    continue;
                }
                if (termsOverlap(rule, other) && sameConflictLevel(rule, other)
                        && (isForbiddenBy(rule, other) || isForbiddenBy(other, rule))) {
                    throw new GenericException("规则目标与禁止映射冲突：" + rule.getCanonicalTerm());
                }
            }
        }
    }

    private List<AiAgentSemanticRule> concatRules(List<RuleChunk> candidates, List<AiAgentSemanticRule> published) {
        List<AiAgentSemanticRule> result = new ArrayList<>(candidates.stream().map(RuleChunk::rule).toList());
        result.addAll(published);
        return result;
    }

    private Set<String> allTerms(AiAgentSemanticRule rule) {
        Set<String> terms = new LinkedHashSet<>();
        terms.add(rule.getCanonicalTerm());
        terms.addAll(rule.getAliases());
        return terms;
    }

    private boolean sameTarget(AiAgentSemanticRule left, AiAgentSemanticRule right) {
        return left.getMapping() != null && right.getMapping() != null
                && StringUtils.equals(left.getMapping().getEntity(), right.getMapping().getEntity())
                && StringUtils.equals(left.getMapping().getField(), right.getMapping().getField());
    }

    private boolean isForbiddenBy(AiAgentSemanticRule owner, AiAgentSemanticRule target) {
        if (owner.getForbiddenMappings() == null || target.getMapping() == null) {
            return false;
        }
        return owner.getForbiddenMappings().stream().anyMatch(forbidden ->
                StringUtils.equals(forbidden.getEntity(), target.getMapping().getEntity())
                        && (StringUtils.isBlank(forbidden.getField())
                        || StringUtils.equals(forbidden.getField(), target.getMapping().getField())));
    }

    private boolean termsOverlap(AiAgentSemanticRule left, AiAgentSemanticRule right) {
        Set<String> leftTerms = new LinkedHashSet<>();
        allTerms(left).stream().map(this::normalizeTerm).forEach(leftTerms::add);
        return allTerms(right).stream().map(this::normalizeTerm).anyMatch(leftTerms::contains);
    }

    private boolean sameConflictLevel(AiAgentSemanticRule left, AiAgentSemanticRule right) {
        return StringUtils.equals(left.getScope(), right.getScope())
                && Objects.equals(left.getPriority(), right.getPriority());
    }

    private String normalizeTerm(String value) {
        return StringUtils.lowerCase(StringUtils.trim(StringUtils.defaultString(value)), Locale.ROOT);
    }

    private record RuleChunk(AiKnowledgeChunk chunk, AiAgentSemanticRule rule) {
    }
}
