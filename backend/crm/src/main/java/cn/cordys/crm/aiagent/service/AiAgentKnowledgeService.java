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
import cn.cordys.crm.aiagent.mapper.AiAgentKnowledgeMapper;
import cn.cordys.mybatis.BaseMapper;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import jakarta.annotation.Resource;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
@Transactional(rollbackFor = Exception.class)
public class AiAgentKnowledgeService {

    private static final long MAX_FILE_SIZE = 50L * 1024 * 1024;
    private static final int CHUNK_SIZE = 800;
    private static final int CHUNK_OVERLAP = 150;
    private static final Pattern WORD_SPLIT_PATTERN = Pattern.compile("[\\s，。？！；：、,.?;:()（）【】\\[\\]{}<>《》\"'“”‘’]+");
    private static final List<String> ALLOWED_TYPES = List.of("pdf", "docx", "txt", "md");

    @Resource
    private BaseMapper<AiKnowledgeDocument> documentMapper;
    @Resource
    private BaseMapper<AiKnowledgeChunk> chunkMapper;
    @Resource
    private BaseMapper<AiKnowledgeParseJob> parseJobMapper;
    @Resource
    private BaseMapper<AiKnowledgeQueryLog> queryLogMapper;
    @Resource
    private AiAgentKnowledgeMapper aiAgentKnowledgeMapper;

    public Pager<List<AiKnowledgeDocumentResponse>> pageDocuments(AiKnowledgeDocumentPageRequest request, String orgId) {
        Page<Object> page = PageHelper.startPage(request.getCurrent(), request.getPageSize());
        List<AiKnowledgeDocumentResponse> list = aiAgentKnowledgeMapper.listDocuments(request, orgId)
                .stream()
                .map(this::toDocumentResponse)
                .toList();
        return PageUtils.setPageInfo(page, list);
    }

    public AiKnowledgeDocumentResponse uploadDocument(MultipartFile file,
                                                      String category,
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
            throw new GenericException("暂只支持 pdf、docx、txt、md 文件");
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
        document.setCategory(StringUtils.trimToNull(category));
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

        parseDocument(documentId, orgId, userId);
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
        requireDocument(id, orgId);
        parseDocument(id, orgId, userId);
    }

    public void setEnabled(String id, String orgId, String userId, boolean enabled) {
        AiKnowledgeDocument document = requireDocument(id, orgId);
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
        saveQueryLog(normalizedQuestion, response, orgId);
        return response;
    }

    private void parseDocument(String id, String orgId, String userId) {
        AiKnowledgeDocument document = requireDocument(id, orgId);
        long now = System.currentTimeMillis();
        AiKnowledgeParseJob job = new AiKnowledgeParseJob();
        job.setId(IDGenerator.nextStr());
        job.setOrganizationId(orgId);
        job.setDocumentId(id);
        job.setStatus("RUNNING");
        job.setStep("PARSE");
        job.setMessage("文档解析中");
        job.setStartTime(now);
        job.setCreateUser(userId);
        job.setUpdateUser(userId);
        job.setCreateTime(now);
        job.setUpdateTime(now);
        parseJobMapper.insert(job);

        document.setParseStatus("PARSING");
        document.setParseError(null);
        document.setUpdateUser(userId);
        document.setUpdateTime(now);
        documentMapper.update(document);

        try {
            List<ParsedSection> sections = parseFile(document);
            List<AiKnowledgeChunk> chunks = buildChunks(sections, document, orgId, userId);
            aiAgentKnowledgeMapper.deleteChunksByDocumentId(id, orgId);
            for (AiKnowledgeChunk chunk : chunks) {
                chunkMapper.insert(chunk);
            }

            document.setParseStatus("PARSED");
            document.setParseError(null);
            document.setChunkCount(chunks.size());
            document.setUpdateUser(userId);
            document.setUpdateTime(System.currentTimeMillis());
            documentMapper.update(document);

            job.setStatus("SUCCESS");
            job.setStep("DONE");
            job.setMessage("解析成功，共生成 " + chunks.size() + " 个知识片段");
            job.setFinishTime(System.currentTimeMillis());
            job.setUpdateTime(System.currentTimeMillis());
            parseJobMapper.update(job);
        } catch (Exception e) {
            document.setParseStatus("FAILED");
            document.setParseError(StringUtils.left(e.getMessage(), 2000));
            document.setChunkCount(0);
            document.setUpdateUser(userId);
            document.setUpdateTime(System.currentTimeMillis());
            documentMapper.update(document);

            job.setStatus("FAILED");
            job.setStep("FAILED");
            job.setMessage(StringUtils.left(e.getMessage(), 1000));
            job.setErrorStack(StringUtils.left(stackTrace(e), 6000));
            job.setFinishTime(System.currentTimeMillis());
            job.setUpdateTime(System.currentTimeMillis());
            parseJobMapper.update(job);
        }
    }

    private List<ParsedSection> parseFile(AiKnowledgeDocument document) throws IOException {
        Path path = Path.of(document.getStoragePath());
        if (!Files.exists(path)) {
            throw new GenericException("文件不存在");
        }
        return switch (document.getFileType()) {
            case "pdf" -> parsePdf(path);
            case "docx" -> parseDocx(path);
            case "txt", "md" -> parseText(path, document.getName());
            default -> throw new GenericException("暂不支持该文件类型：" + document.getFileType());
        };
    }

    private List<ParsedSection> parsePdf(Path path) throws IOException {
        List<ParsedSection> sections = new ArrayList<>();
        try (PDDocument pdf = Loader.loadPDF(path.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            for (int i = 1; i <= pdf.getNumberOfPages(); i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                String text = cleanText(stripper.getText(pdf));
                if (StringUtils.isNotBlank(text)) {
                    sections.add(new ParsedSection("第 " + i + " 页", null, i, text));
                }
            }
        }
        if (sections.isEmpty()) {
            throw new GenericException("PDF 未解析出文本，扫描件 PDF 需要 OCR 后续支持");
        }
        return sections;
    }

    private List<ParsedSection> parseDocx(Path path) throws IOException {
        String xml = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(Files.readAllBytes(path)))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("word/document.xml".equals(entry.getName())) {
                    xml = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                    break;
                }
            }
        }
        if (StringUtils.isBlank(xml)) {
            throw new GenericException("Word 文档内容为空或格式不支持");
        }
        String text = xml
                .replaceAll("</w:p>", "\n")
                .replaceAll("</w:tr>", "\n")
                .replaceAll("</w:tc>", " ")
                .replaceAll("<[^>]+>", "")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&apos;", "'");
        text = cleanText(text);
        if (StringUtils.isBlank(text)) {
            throw new GenericException("Word 文档未解析出文本");
        }
        return List.of(new ParsedSection("Word 文档", "正文", null, text));
    }

    private List<ParsedSection> parseText(Path path, String title) throws IOException {
        String text = cleanText(Files.readString(path, StandardCharsets.UTF_8));
        if (StringUtils.isBlank(text)) {
            throw new GenericException("文档内容为空");
        }
        return List.of(new ParsedSection(title, null, null, text));
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

    private List<String> extractKeywords(String question) {
        return WORD_SPLIT_PATTERN.splitAsStream(StringUtils.defaultString(question))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .filter(item -> item.length() >= 2)
                .distinct()
                .limit(12)
                .toList();
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
        log.setRetrievalMode("KEYWORD");
        log.setMatchedChunks(JSON.toJSONString(response.getMatches()));
        log.setAnswerMode(response.getMatches().isEmpty() ? "CLARIFY" : "DOC");
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

    private String stackTrace(Exception e) {
        StringWriter writer = new StringWriter();
        e.printStackTrace(new PrintWriter(writer));
        return writer.toString();
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

    private record ParsedSection(String title, String sectionPath, Integer pageNo, String content) {
    }
}
