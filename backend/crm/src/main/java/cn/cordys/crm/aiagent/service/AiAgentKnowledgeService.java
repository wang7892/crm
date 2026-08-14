package cn.cordys.crm.aiagent.service;

import cn.cordys.common.exception.GenericException;
import cn.cordys.common.pager.PageUtils;
import cn.cordys.common.pager.Pager;
import cn.cordys.common.uid.IDGenerator;
import cn.cordys.common.util.JSON;
import cn.cordys.crm.aiagent.domain.AiKnowledgeChunk;
import cn.cordys.crm.aiagent.domain.AiKnowledgeDocument;
import cn.cordys.crm.aiagent.domain.AiKnowledgeParseJob;
import cn.cordys.crm.aiagent.domain.AiKnowledgeQueryLog;
import cn.cordys.crm.aiagent.dto.request.AiKnowledgeChunkPageRequest;
import cn.cordys.crm.aiagent.dto.request.AiKnowledgeDocumentPageRequest;
import cn.cordys.crm.aiagent.dto.response.AiKnowledgeChunkResponse;
import cn.cordys.crm.aiagent.dto.response.AiKnowledgeDocumentResponse;
import cn.cordys.crm.aiagent.dto.response.AiKnowledgeSearchMatchResponse;
import cn.cordys.crm.aiagent.dto.response.AiKnowledgeSearchTestResponse;
import cn.cordys.crm.aiagent.dto.response.AiSemanticRuleMatchResponse;
import cn.cordys.crm.aiagent.dto.response.AiSemanticRuleStats;
import cn.cordys.crm.aiagent.dto.semantic.AiAgentSemanticRule;
import cn.cordys.crm.aiagent.dto.semantic.AiAgentSemanticRuleContext;
import cn.cordys.crm.aiagent.dto.semantic.AiAgentSemanticRuleMatch;
import cn.cordys.crm.aiagent.mapper.AiAgentKnowledgeMapper;
import cn.cordys.mybatis.BaseMapper;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import jakarta.annotation.Resource;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@Transactional(rollbackFor = Exception.class)
public class AiAgentKnowledgeService {

    private static final long MAX_FILE_SIZE = 50L * 1024 * 1024;
    private static final int CHUNK_SIZE = 800;
    private static final int CHUNK_OVERLAP = 150;
    private static final int MAX_SEARCH_KEYWORDS = 64;
    private static final String DEFAULT_CATEGORY = "GENERAL";
    private static final Pattern WORD_SPLIT_PATTERN = Pattern.compile("[\\s，。？！；：、,.?;:()（）【】\\[\\]{}<>《》\"'“”‘’]+");
    private static final Pattern ASCII_TERM_PATTERN = Pattern.compile("[A-Za-z0-9_\\-]{2,}");
    private static final Pattern CHINESE_TERM_PATTERN = Pattern.compile("[\\u4e00-\\u9fff]{2,}");
    private static final Set<String> SEARCH_STOP_KEYWORDS = Set.of(
            "什么", "哪些", "怎么", "怎样", "是否", "可以", "这个", "那个",
            "现在", "最近", "一下", "进行", "回答", "问题", "多少", "有没有", "为什么");
    private static final List<String> ALLOWED_TYPES = List.of(
            "jpg", "jpeg", "png", "webp", "pdf", "docx", "xls", "xlsx", "txt", "md");

    @Resource
    private BaseMapper<AiKnowledgeDocument> documentMapper;
    @Resource
    private BaseMapper<AiKnowledgeParseJob> parseJobMapper;
    @Resource
    private BaseMapper<AiKnowledgeQueryLog> queryLogMapper;
    @Resource
    private AiAgentKnowledgeMapper aiAgentKnowledgeMapper;
    @Resource
    private AiAgentSemanticRuleExtractionService semanticRuleExtractionService;
    @Resource
    private AiAgentSemanticRuleValidationService semanticRuleValidationService;
    @Resource
    private AiAgentSemanticRuleService semanticRuleService;
    @Resource
    private AiAgentSemanticRuleRetrievalService semanticRuleRetrievalService;
    @Resource
    private AiAgentSemanticContextBuilder semanticContextBuilder;
    @Resource
    private AiAgentFileContentService aiAgentFileContentService;
    @Resource
    private ApplicationEventPublisher eventPublisher;

    public Pager<List<AiKnowledgeDocumentResponse>> pageDocuments(AiKnowledgeDocumentPageRequest request, String orgId) {
        Page<Object> page = PageHelper.startPage(request.getCurrent(), request.getPageSize());
        List<AiKnowledgeDocumentResponse> list = aiAgentKnowledgeMapper.listDocuments(request, orgId)
                .stream()
                .map(this::toDocumentResponse)
                .toList();
        return PageUtils.setPageInfo(page, list);
    }

    public AiKnowledgeDocumentResponse uploadDocument(MultipartFile file,
                                                      String remark,
                                                      String orgId,
                                                      String userId) {
        if (file == null || file.isEmpty()) {
            throw new GenericException("请选择要上传的文件");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new GenericException("文件不能超过 50MB");
        }

        String originalName = StringUtils.defaultString(file.getOriginalFilename(), "knowledge-file");
        String fileType = normalizeFileType(FilenameUtils.getExtension(originalName));
        if (!ALLOWED_TYPES.contains(fileType)) {
            throw new GenericException("暂只支持图片、pdf、docx、xls、xlsx、txt、md 文件");
        }

        long now = System.currentTimeMillis();
        String documentId = IDGenerator.nextStr();
        Path documentDir = knowledgeRoot().resolve(orgId).resolve(documentId).normalize();
        Path storagePath = documentDir.resolve("original." + fileType).normalize();
        try {
            Files.createDirectories(documentDir);
            file.transferTo(storagePath);
        } catch (IOException e) {
            throw new GenericException("文件保存失败：" + e.getMessage());
        }

        AiKnowledgeDocument document = new AiKnowledgeDocument();
        document.setId(documentId);
        document.setOrganizationId(orgId);
        document.setName(cleanFileName(originalName));
        document.setOriginalName(originalName);
        document.setFileType(fileType);
        document.setFileSize(file.getSize());
        document.setStoragePath(storagePath.toString());
        document.setCategory(DEFAULT_CATEGORY);
        document.setParseStatus("UPLOADED");
        document.setParseError(null);
        document.setChunkCount(0);
        document.setEnabled(1);
        document.setRemark(StringUtils.trimToNull(remark));
        document.setCreateUser(userId);
        document.setUpdateUser(userId);
        document.setCreateTime(now);
        document.setUpdateTime(now);
        documentMapper.insert(document);

        enqueueParse(document, userId);
        return getDocument(documentId, orgId);
    }

    public AiKnowledgeDocumentResponse getDocument(String id, String orgId) {
        return toDocumentResponse(requireDocument(id, orgId));
    }

    public Pager<List<AiKnowledgeChunkResponse>> pageChunks(AiKnowledgeChunkPageRequest request, String orgId) {
        requireDocument(request.getDocumentId(), orgId);
        Page<Object> page = PageHelper.startPage(request.getCurrent(), request.getPageSize());
        List<AiKnowledgeChunkResponse> list = aiAgentKnowledgeMapper.listChunks(request.getDocumentId(), orgId)
                .stream()
                .map(chunk -> toChunkResponse(chunk, null))
                .toList();
        return PageUtils.setPageInfo(page, list);
    }

    public void reparseDocument(String id, String orgId, String userId) {
        AiKnowledgeDocument document = aiAgentKnowledgeMapper.getKnowledgeDocumentForUpdate(id, orgId);
        if (document == null) {
            throw new GenericException("文档不存在或无权限");
        }
        enqueueParse(document, userId);
    }

    public void setEnabled(String id, String orgId, String userId, boolean enabled) {
        AiKnowledgeDocument document = requireDocument(id, orgId);
        if (semanticRuleService.isSemanticDocument(document)) {
            if (enabled) {
                semanticRuleService.publish(id, orgId, userId);
            } else {
                semanticRuleService.withdraw(id, orgId, userId);
            }
            return;
        }
        document.setEnabled(enabled ? 1 : 0);
        document.setUpdateUser(userId);
        document.setUpdateTime(System.currentTimeMillis());
        documentMapper.update(document);
    }

    public void deleteDocument(String id, String orgId) {
        AiKnowledgeDocument document = requireDocument(id, orgId);
        aiAgentKnowledgeMapper.deleteChunksByDocumentId(id, orgId);
        documentMapper.deleteByPrimaryKey(id);
        deleteQuietly(Path.of(document.getStoragePath()).getParent());
    }

    public ResponseEntity<org.springframework.core.io.Resource> downloadDocument(String id, String orgId) {
        AiKnowledgeDocument document = requireDocument(id, orgId);
        File file = Path.of(document.getStoragePath()).toFile();
        if (!file.exists() || !file.isFile()) {
            throw new GenericException("文件不存在");
        }
        String encodedName = URLEncoder.encode(document.getOriginalName(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName)
                .body(new FileSystemResource(file));
    }

    public AiKnowledgeSearchTestResponse searchTest(String question, int topK, String orgId) {
        return searchDocument(question, topK, orgId);
    }

    public AiKnowledgeSearchTestResponse searchTest(String question, int topK, String mode, String orgId) {
        String retrievalMode = StringUtils.upperCase(StringUtils.defaultIfBlank(mode, "AUTO"), Locale.ROOT);
        if (StringUtils.equals(retrievalMode, "DOCUMENT")) {
            return searchDocument(question, topK, orgId);
        }
        AiAgentSemanticRuleRetrievalService.RetrievalResult semanticResult =
                semanticRuleRetrievalService.retrieve(StringUtils.trimToEmpty(question), orgId);
        if (StringUtils.equals(retrievalMode, "SEMANTIC_RULE")
                || semanticResult.conflict()
                || !semanticResult.matches().isEmpty()) {
            return semanticSearchResponse(question, semanticResult, orgId);
        }
        AiKnowledgeSearchTestResponse response = searchDocument(question, topK, orgId);
        response.setFallbackReason(semanticResult.fallbackReason());
        return response;
    }

    private AiKnowledgeSearchTestResponse searchDocument(String question, int topK, String orgId) {
        String normalizedQuestion = StringUtils.trimToEmpty(question);
        List<String> keywords = extractKeywords(normalizedQuestion);
        List<AiKnowledgeChunk> chunks = aiAgentKnowledgeMapper.searchChunks(
                normalizedQuestion,
                keywords,
                orgId,
                Math.max(1, Math.min(topK * 3, 60))
        );
        Map<String, AiKnowledgeDocument> documents = loadDocuments(chunks, orgId);
        List<AiKnowledgeSearchMatchResponse> matches = chunks.stream()
                .map(chunk -> toMatchResponse(chunk, documents.get(chunk.getDocumentId()), normalizedQuestion, keywords))
                .sorted(Comparator.comparingDouble(AiKnowledgeSearchMatchResponse::getScore).reversed())
                .limit(topK)
                .toList();

        AiKnowledgeSearchTestResponse response = new AiKnowledgeSearchTestResponse();
        response.setQuestion(normalizedQuestion);
        response.setRewriteQuestion(buildRewriteQuestion(normalizedQuestion, matches));
        response.setMatches(matches);
        response.setAnswerPreview(buildAnswerPreview(normalizedQuestion, matches));
        response.setRetrievalMode("KEYWORD");
        response.setMatchedRules(List.of());
        saveQueryLog(normalizedQuestion, response, orgId);
        return response;
    }

    private AiKnowledgeSearchTestResponse semanticSearchResponse(
            String question,
            AiAgentSemanticRuleRetrievalService.RetrievalResult result,
            String orgId) {
        String normalizedQuestion = StringUtils.trimToEmpty(question);
        AiKnowledgeSearchTestResponse response = new AiKnowledgeSearchTestResponse();
        response.setQuestion(normalizedQuestion);
        response.setRewriteQuestion(normalizedQuestion);
        response.setMatches(List.of());
        response.setRetrievalMode("SEMANTIC_EXACT");
        response.setMatchedRules(result.matches().stream().map(this::toSemanticMatchResponse).toList());
        AiAgentSemanticRuleContext context = result.conflict()
                ? new AiAgentSemanticRuleContext()
                : semanticContextBuilder.build(result.matches());
        response.setInjectedContextPreview(context);
        response.setFallbackReason(result.fallbackReason());
        if (result.conflict()) {
            response.setAnswerPreview("命中的业务术语规则存在冲突，本次不能注入查询解析器。");
        } else if (result.matches().isEmpty()) {
            response.setAnswerPreview("未命中已上传并生效的业务知识规则。");
        } else {
            response.setAnswerPreview("已命中 " + result.matches().size() + " 条已生效业务知识规则。");
        }
        saveQueryLog(normalizedQuestion, response, orgId);
        return response;
    }

    private AiSemanticRuleMatchResponse toSemanticMatchResponse(AiAgentSemanticRuleMatch match) {
        AiSemanticRuleMatchResponse response = new AiSemanticRuleMatchResponse();
        response.setRuleId(match.getRuleId());
        response.setVersion(match.getVersion());
        response.setTerm(match.getCanonicalTerm());
        response.setMatchedBy(match.getMatchedBy());
        response.setScore(match.getScore());
        response.setTarget(match.getTarget());
        response.setDocumentId(match.getDocumentId());
        response.setChunkId(match.getChunkId());
        response.setPageNo(match.getPageNo());
        response.setSectionPath(match.getSectionPath());
        return response;
    }

    private void enqueueParse(AiKnowledgeDocument document, String userId) {
        long now = System.currentTimeMillis();
        document.setParseStatus("UPLOADED");
        document.setParseError(null);
        document.setUpdateUser(userId);
        document.setUpdateTime(now);
        if (aiAgentKnowledgeMapper.updateKnowledgeDocumentParseState(
                document.getId(), document.getOrganizationId(), "UPLOADED", null,
                null, userId, now) != 1) {
            throw new GenericException("更新文档解析状态失败");
        }

        AiKnowledgeParseJob job = new AiKnowledgeParseJob();
        job.setId(IDGenerator.nextStr());
        job.setOrganizationId(document.getOrganizationId());
        job.setDocumentId(document.getId());
        job.setStatus(AiAgentKnowledgeParsePersistenceService.STATUS_PENDING);
        job.setStep(AiAgentKnowledgeParsePersistenceService.STEP_RETRY_PREFIX + "0");
        job.setMessage("等待后台解析");
        job.setCreateUser(userId);
        job.setUpdateUser(userId);
        job.setCreateTime(now);
        job.setUpdateTime(now);
        if (!Objects.equals(parseJobMapper.insert(job), 1)) {
            throw new GenericException("创建文档解析任务失败");
        }
        eventPublisher.publishEvent(new AiKnowledgeParseRequestedEvent(job.getId()));
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PreparedParse prepareParse(AiKnowledgeDocument document, String orgId, String userId) throws IOException {
        List<ParsedSection> sections = parseFile(document);
        writeMarkdownFile(document, sections);
        List<AiKnowledgeChunk> chunks = semanticRuleService.isSemanticDocument(document)
                ? buildSemanticRuleChunks(sections, document, orgId, userId)
                : buildChunks(sections, document, orgId, userId);
        return new PreparedParse(chunks);
    }

    private List<ParsedSection> parseFile(AiKnowledgeDocument document) throws IOException {
        Path path = Path.of(document.getStoragePath());
        if (!Files.exists(path)) {
            throw new GenericException("文件不存在");
        }
        return aiAgentFileContentService
                .parse(path, document.getFileType(), document.getName(), null)
                .stream()
                .map(section -> new ParsedSection(
                        section.title(), section.sectionPath(), section.pageNo(), section.content()))
                .toList();
    }

    void writeMarkdownFile(AiKnowledgeDocument document, List<ParsedSection> sections) throws IOException {
        if ("md".equals(document.getFileType())) {
            return;
        }
        Path documentDir = Path.of(document.getStoragePath()).getParent();
        if (documentDir == null) {
            throw new GenericException("文档存储路径无效");
        }
        Files.writeString(
                documentDir.resolve("normalized.md"),
                renderMarkdown(document, sections),
                StandardCharsets.UTF_8
        );
    }

    String renderMarkdown(AiKnowledgeDocument document, List<ParsedSection> sections) {
        String title = StringUtils.defaultIfBlank(
                FilenameUtils.getBaseName(document.getOriginalName()),
                "知识文档"
        );
        StringBuilder markdown = new StringBuilder()
                .append("# ")
                .append(cleanMarkdownHeading(title))
                .append("\n\n");

        for (ParsedSection section : sections) {
            String heading = null;
            if (section.pageNo() != null) {
                heading = "第 " + section.pageNo() + " 页";
            } else if (StringUtils.isNotBlank(section.sectionPath())) {
                heading = section.sectionPath();
            } else if (sections.size() > 1 && StringUtils.isNotBlank(section.title())) {
                heading = section.title();
            }
            if (StringUtils.isNotBlank(heading)) {
                markdown.append("## ")
                        .append(cleanMarkdownHeading(heading))
                        .append("\n\n");
            }
            markdown.append(section.content().trim()).append("\n\n");
        }
        return markdown.toString();
    }

    private String cleanMarkdownHeading(String value) {
        return StringUtils.defaultString(value)
                .replaceAll("[\\r\\n]+", " ")
                .replaceAll("^#+\\s*", "")
                .trim();
    }

    private List<AiKnowledgeChunk> buildChunks(List<ParsedSection> sections,
                                               AiKnowledgeDocument document,
                                               String orgId,
                                               String userId) {
        List<AiKnowledgeChunk> chunks = new ArrayList<>();
        int index = 0;
        long now = System.currentTimeMillis();
        for (ParsedSection section : sections) {
            for (String content : splitContent(section.content())) {
                AiKnowledgeChunk chunk = new AiKnowledgeChunk();
                chunk.setId(IDGenerator.nextStr());
                chunk.setOrganizationId(orgId);
                chunk.setDocumentId(document.getId());
                chunk.setChunkIndex(index++);
                chunk.setTitle(section.title());
                chunk.setContent(content);
                chunk.setContentHash(DigestUtils.sha256Hex(content));
                chunk.setPageNo(section.pageNo());
                chunk.setSectionPath(section.sectionPath());
                chunk.setTokenCount(Math.max(1, content.length() / 2));
                chunk.setEmbeddingStatus("NONE");
                chunk.setEmbeddingId(null);
                chunk.setEnabled(1);
                chunk.setCreateUser(userId);
                chunk.setUpdateUser(userId);
                chunk.setCreateTime(now);
                chunk.setUpdateTime(now);
                chunks.add(chunk);
            }
        }
        if (chunks.isEmpty()) {
            throw new GenericException("文档未生成有效知识片段");
        }
        return chunks;
    }

    private List<AiKnowledgeChunk> buildSemanticRuleChunks(List<ParsedSection> sections,
                                                           AiKnowledgeDocument document,
                                                           String orgId,
                                                           String userId) throws IOException {
        String normalizedMarkdown = "md".equals(document.getFileType())
                ? Files.readString(Path.of(document.getStoragePath()), StandardCharsets.UTF_8)
                : renderMarkdown(document, sections);
        List<AiAgentSemanticRuleExtractionService.SourceFragment> fragments = sections.stream()
                .map(section -> new AiAgentSemanticRuleExtractionService.SourceFragment(
                        section.pageNo(),
                        StringUtils.defaultIfBlank(section.sectionPath(), section.title()),
                        section.content()))
                .toList();
        List<AiAgentSemanticRule> rules = semanticRuleExtractionService.extract(
                document, orgId, normalizedMarkdown, fragments);
        List<AiKnowledgeChunk> chunks = new ArrayList<>();
        long now = System.currentTimeMillis();
        int index = 0;
        for (AiAgentSemanticRule rule : rules) {
            if (rule.getValidationErrors() != null && !rule.getValidationErrors().isEmpty()) {
                continue;
            }
            String content = semanticRuleValidationService.serialize(rule);
            AiKnowledgeChunk chunk = new AiKnowledgeChunk();
            chunk.setId(IDGenerator.nextStr());
            chunk.setOrganizationId(orgId);
            chunk.setDocumentId(document.getId());
            chunk.setChunkIndex(index++);
            chunk.setTitle(rule.getCanonicalTerm());
            chunk.setContent(content);
            chunk.setContentHash(semanticRuleValidationService.semanticPayloadHash(rule));
            chunk.setPageNo(rule.getSource() == null ? null : rule.getSource().getPageNo());
            chunk.setSectionPath(rule.getSource() == null ? null : rule.getSource().getSectionPath());
            chunk.setTokenCount(Math.max(1, content.length() / 2));
            chunk.setEmbeddingStatus("NONE");
            chunk.setEmbeddingId(null);
            chunk.setEnabled(0);
            chunk.setCreateUser(userId);
            chunk.setUpdateUser(userId);
            chunk.setCreateTime(now);
            chunk.setUpdateTime(now);
            chunks.add(chunk);
        }
        if (chunks.isEmpty()) {
            throw new GenericException("语义规则文档未生成候选规则");
        }
        return chunks;
    }

    private List<String> splitContent(String text) {
        String content = cleanText(text);
        if (StringUtils.isBlank(content)) {
            return List.of();
        }
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < content.length()) {
            int end = Math.min(content.length(), start + CHUNK_SIZE);
            int preferredEnd = findPreferredEnd(content, start, end);
            String chunk = content.substring(start, preferredEnd).trim();
            if (StringUtils.isNotBlank(chunk)) {
                chunks.add(chunk);
            }
            if (preferredEnd >= content.length()) {
                break;
            }
            start = Math.max(preferredEnd - CHUNK_OVERLAP, start + 1);
        }
        return chunks;
    }

    private int findPreferredEnd(String content, int start, int end) {
        if (end >= content.length()) {
            return content.length();
        }
        int best = -1;
        for (String mark : List.of("\n", "。", "；", "！", "？")) {
            int position = content.lastIndexOf(mark, end);
            if (position > start + CHUNK_SIZE / 2) {
                best = Math.max(best, position + mark.length());
            }
        }
        return best > 0 ? best : end;
    }

    private AiKnowledgeDocument requireDocument(String id, String orgId) {
        AiKnowledgeDocument document = documentMapper.selectByPrimaryKey(id);
        if (document == null || !Objects.equals(document.getOrganizationId(), orgId)) {
            throw new GenericException("文档不存在或无权限");
        }
        return document;
    }

    private Map<String, AiKnowledgeDocument> loadDocuments(List<AiKnowledgeChunk> chunks, String orgId) {
        Map<String, AiKnowledgeDocument> result = new LinkedHashMap<>();
        for (AiKnowledgeChunk chunk : chunks) {
            if (!result.containsKey(chunk.getDocumentId())) {
                AiKnowledgeDocument document = documentMapper.selectByPrimaryKey(chunk.getDocumentId());
                if (document != null && Objects.equals(document.getOrganizationId(), orgId)) {
                    result.put(document.getId(), document);
                }
            }
        }
        return result;
    }

    private AiKnowledgeDocumentResponse toDocumentResponse(AiKnowledgeDocument document) {
        AiKnowledgeDocumentResponse response = new AiKnowledgeDocumentResponse();
        response.setId(document.getId());
        response.setOrganizationId(document.getOrganizationId());
        response.setName(document.getName());
        response.setOriginalName(document.getOriginalName());
        response.setFileType(document.getFileType());
        response.setFileSize(document.getFileSize());
        response.setCategory(document.getCategory());
        response.setParseStatus(document.getParseStatus());
        response.setParseError(document.getParseError());
        response.setChunkCount(document.getChunkCount());
        response.setEnabled(document.getEnabled());
        response.setRemark(document.getRemark());
        if (semanticRuleService.isSemanticDocument(document)) {
            AiSemanticRuleStats stats = semanticRuleService.ruleStats(document.getId(), document.getOrganizationId());
            response.setRuleStats(stats);
            response.setSemanticStatus(semanticRuleService.semanticStatus(document, stats));
        }
        response.setCreateUser(document.getCreateUser());
        response.setUpdateUser(document.getUpdateUser());
        response.setCreateTime(document.getCreateTime());
        response.setUpdateTime(document.getUpdateTime());
        return response;
    }

    private AiKnowledgeChunkResponse toChunkResponse(AiKnowledgeChunk chunk, AiKnowledgeDocument document) {
        AiKnowledgeChunkResponse response = new AiKnowledgeChunkResponse();
        response.setId(chunk.getId());
        response.setDocumentId(chunk.getDocumentId());
        response.setDocumentName(document == null ? null : document.getName());
        response.setChunkIndex(chunk.getChunkIndex());
        response.setTitle(chunk.getTitle());
        response.setContent(chunk.getContent());
        response.setPageNo(chunk.getPageNo());
        response.setSectionPath(chunk.getSectionPath());
        response.setTokenCount(chunk.getTokenCount());
        response.setEmbeddingStatus(chunk.getEmbeddingStatus());
        response.setEnabled(chunk.getEnabled());
        response.setCreateTime(chunk.getCreateTime());
        response.setUpdateTime(chunk.getUpdateTime());
        return response;
    }

    private AiKnowledgeSearchMatchResponse toMatchResponse(AiKnowledgeChunk chunk,
                                                           AiKnowledgeDocument document,
                                                           String question,
                                                           List<String> keywords) {
        AiKnowledgeSearchMatchResponse response = new AiKnowledgeSearchMatchResponse();
        response.setDocumentId(chunk.getDocumentId());
        response.setDocumentName(document == null ? null : document.getName());
        response.setChunkId(chunk.getId());
        response.setChunkIndex(chunk.getChunkIndex());
        response.setPageNo(chunk.getPageNo());
        response.setSectionPath(chunk.getSectionPath());
        response.setScore(score(chunk, question, keywords));
        response.setContent(chunk.getContent());
        return response;
    }

    private double score(AiKnowledgeChunk chunk, String question, List<String> keywords) {
        String content = StringUtils.defaultString(chunk.getContent()).toLowerCase(Locale.ROOT);
        double score = 0;
        if (StringUtils.isNotBlank(question) && content.contains(question.toLowerCase(Locale.ROOT))) {
            score += 4;
        }
        for (String keyword : keywords) {
            if (content.contains(keyword.toLowerCase(Locale.ROOT))) {
                score += 1;
            }
        }
        return score;
    }

    List<String> extractKeywords(String question) {
        LinkedHashSet<String> keywords = new LinkedHashSet<>();
        List<String> segments = WORD_SPLIT_PATTERN.splitAsStream(StringUtils.defaultString(question))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .toList();
        segments.stream().filter(item -> item.length() >= 2).forEach(keywords::add);

        var asciiMatcher = ASCII_TERM_PATTERN.matcher(StringUtils.defaultString(question));
        while (asciiMatcher.find()) {
            keywords.add(asciiMatcher.group());
        }

        var chineseMatcher = CHINESE_TERM_PATTERN.matcher(StringUtils.defaultString(question));
        while (chineseMatcher.find()) {
            String text = chineseMatcher.group();
            int maxGramLength = Math.min(8, text.length());
            for (int gramLength = 2; gramLength <= maxGramLength; gramLength++) {
                for (int start = 0; start + gramLength <= text.length(); start++) {
                    String candidate = text.substring(start, start + gramLength);
                    if (!SEARCH_STOP_KEYWORDS.contains(candidate)) {
                        keywords.add(candidate);
                    }
                }
            }
        }
        return keywords.stream().limit(MAX_SEARCH_KEYWORDS).toList();
    }

    private String buildRewriteQuestion(String question, List<AiKnowledgeSearchMatchResponse> matches) {
        if (matches.isEmpty()) {
            return question;
        }
        return question;
    }

    private String buildAnswerPreview(String question, List<AiKnowledgeSearchMatchResponse> matches) {
        if (matches.isEmpty()) {
            return "公司知识库中暂未找到与“" + question + "”相关的内容。";
        }
        AiKnowledgeSearchMatchResponse first = matches.get(0);
        return "已命中《" + StringUtils.defaultIfBlank(first.getDocumentName(), "未命名文档")
                + "》中的知识片段，可作为智能体回答前的公司业务依据。";
    }

    private void saveQueryLog(String question, AiKnowledgeSearchTestResponse response, String orgId) {
        AiKnowledgeQueryLog log = new AiKnowledgeQueryLog();
        long now = System.currentTimeMillis();
        log.setId(IDGenerator.nextStr());
        log.setOrganizationId(orgId);
        log.setQuestion(question);
        log.setRewriteQuestion(response.getRewriteQuestion());
        log.setRetrievalMode(StringUtils.defaultIfBlank(response.getRetrievalMode(), "KEYWORD"));
        Map<String, Object> matches = new LinkedHashMap<>();
        matches.put("documentChunks", response.getMatches());
        matches.put("semanticRules", response.getMatchedRules());
        matches.put("fallbackReason", response.getFallbackReason());
        log.setMatchedChunks(JSON.toJSONString(matches));
        boolean hasDocumentMatch = response.getMatches() != null && !response.getMatches().isEmpty();
        boolean hasRuleMatch = response.getMatchedRules() != null && !response.getMatchedRules().isEmpty();
        log.setAnswerMode(hasDocumentMatch || hasRuleMatch ? "DOC" : "CLARIFY");
        log.setCreateTime(now);
        log.setUpdateTime(now);
        queryLogMapper.insert(log);
    }

    private Path knowledgeRoot() {
        return Path.of("runtime", "uploads", "knowledge").toAbsolutePath().normalize();
    }

    private String cleanFileName(String value) {
        return StringUtils.defaultIfBlank(value, "knowledge-file").replaceAll("[\\\\/:*?\"<>|]+", "_");
    }

    private String normalizeFileType(String value) {
        return StringUtils.defaultString(value).trim().toLowerCase(Locale.ROOT);
    }

    private String cleanText(String value) {
        return StringUtils.defaultString(value)
                .replace('\u00A0', ' ')
                .replaceAll("[\\t\\x0B\\f\\r]+", " ")
                .replaceAll(" *\\n+ *", "\n")
                .replaceAll("[ ]{2,}", " ")
                .trim();
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            FileUtils.deleteDirectory(path.toFile());
        } catch (IOException ignored) {
        }
    }

    record ParsedSection(String title, String sectionPath, Integer pageNo, String content) {
    }

    public record PreparedParse(List<AiKnowledgeChunk> chunks) {
    }
}
