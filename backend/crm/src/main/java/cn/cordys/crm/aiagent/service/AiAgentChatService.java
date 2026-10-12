package cn.cordys.crm.aiagent.service;

import cn.cordys.common.constants.InternalUserView;
import cn.cordys.common.constants.PermissionConstants;
import cn.cordys.common.dto.DeptDataPermissionDTO;
import cn.cordys.common.service.DataScopeService;
import cn.cordys.common.util.JSON;
import cn.cordys.context.OrganizationContext;
import cn.cordys.crm.aiagent.config.AiAgentLlmProperties;
import cn.cordys.crm.aiagent.domain.AiAgentMessage;
import cn.cordys.crm.aiagent.domain.AiAgentSession;
import cn.cordys.crm.aiagent.dto.*;
import cn.cordys.crm.aiagent.dto.request.AiAgentChatRequest;
import cn.cordys.crm.aiagent.dto.response.*;
import cn.cordys.security.SessionUtils;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import java.time.*;
import java.util.*;
import java.util.regex.*;

/** No database transaction spans model calls; audit writes use short separate transactions. */
@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class AiAgentChatService {
    private static final ZoneId AGENT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Pattern EXPLICIT_YEAR_MONTH_PATTERN = Pattern.compile("(20\\d{2})\\s*年\\s*(1[0-2]|0?[1-9]|十一|十二|十|[一二三四五六七八九])\\s*月(?:份)?");
    private static final Pattern EXPLICIT_YEAR_PATTERN = Pattern.compile("(20\\d{2})\\s*年");
    private static final Pattern RECENT_MONTHS_PATTERN = Pattern.compile("(?:最近|近)\\s*(\\d{1,2}|十一|十二|十|[一二三四五六七八九])\\s*个?月");
    private static final Pattern RECENT_DAYS_PATTERN = Pattern.compile("(?:最近|近)\\s*(\\d{1,3})\\s*天");

    @Resource private AiAgentAuditService aiAgentAuditService;
    @Resource private LlmAiAgentQuestionParser llmAiAgentQuestionParser;
    @Resource private AiAgentLlmProperties aiAgentLlmProperties;
    @Resource private AiAgentSqlQueryService aiAgentSqlQueryService;
    @Resource private AiAgentAttachmentService aiAgentAttachmentService;
    @Resource private AiAgentRequestCancellationService aiAgentRequestCancellationService;
    @Resource private DataScopeService dataScopeService;
    @Resource private AiAgentConversationService conversationService;
    @Resource private AiAgentDatasourceService datasourceService;
    @Resource private AiAgentSqlBotResultService sqlBotResults;

    public AiAgentChatResponse chat(AiAgentChatRequest request) {
        try (AiAgentProgress progress = AiAgentProgress.captureIfAbsent(); AiAgentLlmUsage usage = AiAgentLlmUsage.begin()) {
            if (AiAgentProgress.steps().isEmpty()) AiAgentProgress.stage("request", "开始处理问题");
            String userId = SessionUtils.getUserId(), orgId = OrganizationContext.getOrganizationId();
            AiAgentSession session = aiAgentAuditService.ensureSession(request.getSessionId(), request.getQuestion(), userId, orgId);
            AiAgentContext context = createContext(userId, orgId, request);
            String action = StringUtils.defaultIfBlank(request.getAction(), "QUERY");
            conversationService.prepareSqlHistory(session.getId(), context,
                    "REGENERATE".equals(action) ? request.getReferenceMessageId() : null);
            aiAgentAuditService.saveMessage(session.getId(), "user", request.getQuestion(), null, null, userId);
            aiAgentRequestCancellationService.throwIfCancellationRequested(request.getRequestId());
            AiAgentQueryEvidence evidence = null;
            AiAgentChatResponse response;
            try {
                if (!"QUERY".equals(action)) {
                    if (StringUtils.isBlank(request.getReferenceMessageId())) throw new IllegalArgumentException("请指定要操作的查询结果。");
                    var previous = aiAgentAuditService.loadQueryResponse(request.getReferenceMessageId(), session.getId(), context);
                    context.setQueryQuestion(previous.getQueryQuestion());
                    if ("REGENERATE".equals(action)) {
                        context.setQueryError(previous.getQueryError());
                        evidence = runQuery(context); response = evidence.getResponse();
                    } else response = sqlBotResults.analyze(previous, context, "PREDICT".equals(action));
                } else {
                    if (StringUtils.isBlank(request.getContinueFromMessageId())
                            && request.getQuestion().trim().matches("(?:继续查看|下一页|查看下一页|继续加载)[。！!]?"))
                        request.setContinueFromMessageId(StringUtils.defaultIfBlank(aiAgentAuditService.latestContinuationMessage(session.getId(), context), "no_previous_page"));
                    evidence = StringUtils.isBlank(request.getContinueFromMessageId()) ? runQuery(context)
                            : continueQuery(request, session.getId(), context);
                    response = evidence.getResponse();
                }
            } catch (AiAgentRequestCancelledException e) { throw e; }
              catch (IllegalArgumentException e) { response = failure(e.getMessage(), context); }
            response.setProcessSteps(AiAgentProgress.finish(StringUtils.isNotBlank(response.getQueryError()))); usage.applyTo(response);
            aiAgentRequestCancellationService.throwIfCancellationRequested(request.getRequestId());
            String mode = Set.of("ANALYSIS", "PREDICT").contains(action) ? action : "QUERY";
            var outcome = conversationService.complete(request.getQuestion(), mode, context, response, evidence);
            AiAgentMessage message = aiAgentAuditService.saveMessage(session.getId(), "assistant", response.getAnswer(),
                    response.getIntent(), outcome.evidenceSnapshot(), userId);
            response.setSessionId(session.getId()); response.setMessageId(message.getId());
            aiAgentAuditService.saveToolLogs(message.getId(), response.getTools(), userId);
            aiAgentAuditService.touch(session.getId(), userId);
            return response;
        }
    }

    private AiAgentQueryEvidence runQuery(AiAgentContext context) {
        var response = route(context.getQueryQuestion(), context);
        var evidence = new AiAgentQueryEvidence(); evidence.setId(UUID.randomUUID().toString());
        evidence.setQuestion(context.getQueryQuestion()); evidence.setQueriedAt(System.currentTimeMillis());
        evidence.setResponse(response); evidence.setData(context.getAnalysisData());
        evidence.setContinuation(context.getContinuation()); evidence.setTimeWindow(context.getTimeWindow());
        return evidence;
    }
    private AiAgentChatResponse route(String question, AiAgentContext context) {
        if (!aiAgentLlmProperties.isEnabled() || !aiAgentSqlQueryService.isTextToSqlEnabled())
            return failure("大模型 SQL 查询未开启，请检查模型配置。", context);
        context.setLlmParseAttempted(true);
        ParsedAiAgentQuestion parsed = llmAiAgentQuestionParser.parse(question, context.getLlmProvider(), context);
        context.setLlmParsedQuestion(parsed);
        if (parsed == null) return failure("未能生成 SQL，可重新生成或检查模型配置。", context);
        if (parsed.isNeedClarification() || StringUtils.isBlank(parsed.getCandidateSql()))
            return failure(StringUtils.defaultIfBlank(parsed.getClarificationQuestion(), "当前表结构无法回答这个问题。"), context);
        context.setRequestedChartType(parsed.getChartType());
        try {
            AiAgentChatResponse response = aiAgentSqlQueryService.answer(parsed.getCandidateSql(), context);
            if (context.getTableSelectionSummary() != null) response.getTools().add(tool("table_selection", "SUCCESS", context.getTableSelectionSummary(), 0L));
            return response;
        } catch (IllegalArgumentException e) {
            // Preserve execution feedback for an explicit regenerate or subsequent question. No automatic repair loop.
            AiAgentProgress.stage("query_failed", "SQL 校验或执行失败，可重新生成");
            AiAgentChatResponse response = failure(e.getMessage(), context);
            response.getSqlQueries().add(parsed.getCandidateSql()); return response;
        }
    }
    private AiAgentQueryEvidence continueQuery(AiAgentChatRequest request, String sessionId, AiAgentContext context) {
        var previous = aiAgentAuditService.loadContinuation(request.getContinueFromMessageId(), sessionId, context);
        if (StringUtils.isBlank(previous.getGeneratedSql())) throw new IllegalArgumentException("此历史结果需要重新查询。");
        context.setQueryQuestion(previous.getQuestion()); context.setTimeWindow(previous.getTimeWindow()); context.setContinuingQuery(true);
        var response = aiAgentSqlQueryService.answer(previous.getGeneratedSql(), context, previous.getPageSize(), previous.getNextOffset());
        var evidence = new AiAgentQueryEvidence(); evidence.setId(UUID.randomUUID().toString()); evidence.setQuestion(previous.getQuestion());
        evidence.setQueriedAt(System.currentTimeMillis()); evidence.setResponse(response); evidence.setData(context.getAnalysisData());
        evidence.setContinuation(context.getContinuation()); return evidence;
    }
    private AiAgentChatResponse failure(String reason, AiAgentContext context) {
        var response = new AiAgentChatResponse(); response.setIntent("CRM_DATABASE_QUERY_REJECTED");
        response.setQueryQuestion(context.getQueryQuestion());
        response.setQueryError(StringUtils.defaultIfBlank(reason, "SQL 查询失败"));
        response.setAnswer(response.getQueryError());
        response.getTools().add(tool("text_to_sql_query", "REJECTED", response.getQueryError(), 0L));
        return response;
    }

    public byte[] exportResult(AiAgentChatRequest request) {
        var context = createContext(SessionUtils.getUserId(), OrganizationContext.getOrganizationId(), request);
        var response = aiAgentAuditService.loadQueryResponse(request.getReferenceMessageId(), request.getSessionId(), context);
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(); var output = new java.io.ByteArrayOutputStream()) {
            if (response.getResultTable() != null) writeSheet(workbook, "查询结果", response.getResultTable());
            if (response.getPredictionTable() != null) writeSheet(workbook, "预测数据", response.getPredictionTable());
            if (workbook.getNumberOfSheets() == 0) throw new IllegalArgumentException("该记录没有可导出的数据。");
            workbook.write(output); return output.toByteArray();
        } catch (java.io.IOException e) { throw new IllegalStateException("导出失败", e); }
    }
    private void writeSheet(org.apache.poi.xssf.usermodel.XSSFWorkbook workbook, String name, AiAgentResultTable table) {
        var sheet = workbook.createSheet(name); var header = sheet.createRow(0);
        for (int i = 0; i < table.getColumns().size(); i++) header.createCell(i).setCellValue(table.getColumns().get(i).label());
        for (int i = 0; i < table.getRows().size(); i++) {
            var row = sheet.createRow(i + 1);
            for (int j = 0; j < table.getColumns().size(); j++) {
                Object value = table.getRows().get(i).get(table.getColumns().get(j).key());
                var cell = row.createCell(j);
                if (value instanceof Number n && Double.isFinite(n.doubleValue()) && n.toString().replaceAll("[^0-9]", "").length() <= 15) cell.setCellValue(n.doubleValue());
                else if (value instanceof Boolean b) cell.setCellValue(b);
                else if (value != null) cell.setCellValue(String.valueOf(value));
            }
        }
        sheet.createFreezePane(0, 1);
    }
    private AiAgentToolCallDTO tool(String name, String status, String summary, Long durationMs) {
        var tool = new AiAgentToolCallDTO(); tool.setName(name); tool.setStatus(status); tool.setSummary(summary);
        tool.setDurationMs(durationMs); tool.setEvidenceId("ev_" + name); return tool;
    }
    private boolean containsAny(String text, String... words) {
        return Arrays.stream(words).anyMatch(word -> StringUtils.defaultString(text).contains(word));
    }

    public AiAgentChatResponse chatWithAttachments(AiAgentChatRequest request, List<MultipartFile> files) {
        try (AiAgentProgress progress = AiAgentProgress.captureIfAbsent(); AiAgentLlmUsage usage = AiAgentLlmUsage.begin()) {
            AiAgentProgress.stage("attachment", "解析附件并生成回答");
            return chatWithAttachments(request, files, usage);
        }
    }

    private AiAgentChatResponse chatWithAttachments(AiAgentChatRequest request, List<MultipartFile> files,
                                                    AiAgentLlmUsage usage) {
        String userId = SessionUtils.getUserId();
        String orgId = OrganizationContext.getOrganizationId();
        aiAgentAttachmentService.validate(files);
        AiAgentAttachmentService.AttachmentMode mode = aiAgentAttachmentService.resolveMode(request.getQuestion());
        AiAgentContext datasourceContext = StringUtils.isNotBlank(request.getDatasourceId())
                ? createContext(userId, orgId, request) : null;

        AiAgentSession session = aiAgentAuditService.ensureSession(
                request.getSessionId(), request.getQuestion(), userId, orgId);
        aiAgentAuditService.saveMessage(
                session.getId(),
                "user",
                request.getQuestion(),
                null,
                aiAgentAttachmentService.evidenceSnapshot(files, mode),
                userId
        );
        aiAgentRequestCancellationService.throwIfCancellationRequested(request.getRequestId());

        AiAgentChatResponse response = aiAgentAttachmentService.handle(
                request.getQuestion(), request.getLlmProvider(), files, orgId, userId, mode);
        if (datasourceContext != null) {
            response.setDatasourceId(datasourceContext.getDatasourceId());
            response.setDatasourceName(datasourceContext.getDatasourceName());
            response.setDatasourceType(datasourceContext.getDatasourceType());
        }
        response.setProcessSteps(AiAgentProgress.finish(StringUtils.isNotBlank(response.getQueryError())));
        usage.applyTo(response);
        aiAgentRequestCancellationService.throwIfCancellationRequested(request.getRequestId());
        AiAgentMessage assistantMessage = aiAgentAuditService.saveMessage(
                session.getId(),
                "assistant",
                response.getAnswer(),
                response.getIntent(),
                aiAgentAuditService.responseEvidenceSnapshot(response),
                userId
        );
        response.setSessionId(session.getId());
        response.setMessageId(assistantMessage.getId());
        aiAgentAuditService.saveToolLogs(assistantMessage.getId(), response.getTools(), userId);
        aiAgentAuditService.touch(session.getId(), userId);
        return response;
    }
    private AiAgentTimeWindow resolveTimeWindow(String question, String requestedRange) {
        LocalDate today = LocalDate.now(AGENT_ZONE);
        AiAgentTimeWindow questionWindow = resolveQuestionTimeWindow(question, today);
        if (questionWindow != null) {
            return questionWindow;
        }
        LocalDate startDate;
        String label;
        if (StringUtils.equals(requestedRange, "7d")) {
            startDate = today.minusDays(7);
            label = "近 7 天";
        } else if (StringUtils.equals(requestedRange, "month")) {
            startDate = today.withDayOfMonth(1);
            label = "本月";
        } else if (StringUtils.equals(requestedRange, "quarter") || question.contains("本季度")) {
            int firstMonth = ((today.getMonthValue() - 1) / 3) * 3 + 1;
            startDate = LocalDate.of(today.getYear(), firstMonth, 1);
            label = "本季度";
        } else if (StringUtils.equals(requestedRange, "year") || question.contains("今年")) {
            startDate = LocalDate.of(today.getYear(), 1, 1);
            label = "本年度";
        } else if (StringUtils.equals(requestedRange, "30d")) {
            startDate = today.minusDays(30);
            label = "近 30 天";
        } else if (StringUtils.equalsAnyIgnoreCase(StringUtils.defaultString(requestedRange), "all", "全部")) {
            return allTimeWindow();
        } else {
            return allTimeWindow();
        }
        long start = startDate.atStartOfDay(AGENT_ZONE).toInstant().toEpochMilli();
        long end = today.plusDays(1).atStartOfDay(AGENT_ZONE).toInstant().toEpochMilli();
        return new AiAgentTimeWindow(start, end, label);
    }

    private AiAgentTimeWindow resolveQuestionTimeWindow(String question, LocalDate today) {
        String text = StringUtils.defaultString(question);
        AiAgentTimeWindow explicitRange = AiAgentDateRangeParser.resolve(text, today);
        if (explicitRange != null) return explicitRange;
        Matcher monthMatcher = EXPLICIT_YEAR_MONTH_PATTERN.matcher(text);
        if (monthMatcher.find()) {
            int year = Integer.parseInt(monthMatcher.group(1));
            int month = parseMonthNumber(monthMatcher.group(2));
            if (month >= 1 && month <= 12) {
                LocalDate start = LocalDate.of(year, month, 1);
                return timeWindow(start, start.plusMonths(1), year + "年" + month + "月");
            }
        }

        if (containsAny(text, "这个月", "本月")) {
            return timeWindow(today.withDayOfMonth(1), today.plusMonths(1).withDayOfMonth(1), "本月");
        }
        if (containsAny(text, "上个月", "上月")) {
            return timeWindow(today.minusMonths(1).withDayOfMonth(1), today.withDayOfMonth(1), "上月");
        }
        if (containsAny(text, "去年", "上一年")) {
            return timeWindow(LocalDate.of(today.getYear() - 1, 1, 1), LocalDate.of(today.getYear(), 1, 1), "去年");
        }
        if (containsAny(text, "本周", "这周")) {
            LocalDate start = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            return timeWindow(start, start.plusWeeks(1), "本周");
        }
        if (text.contains("今天")) {
            return timeWindow(today, today.plusDays(1), "今天");
        }
        if (text.contains("昨天")) {
            return timeWindow(today.minusDays(1), today, "昨天");
        }

        if (containsAny(text, "本季度", "这季度", "当前季度")) {
            int firstMonth = ((today.getMonthValue() - 1) / 3) * 3 + 1;
            LocalDate start = LocalDate.of(today.getYear(), firstMonth, 1);
            return timeWindow(start, start.plusMonths(3), "本季度");
        }

        if (containsAny(text, "今年", "本年度", "当前年度")) {
            LocalDate start = LocalDate.of(today.getYear(), 1, 1);
            return timeWindow(start, start.plusYears(1), "本年度");
        }

        Matcher recentDaysMatcher = RECENT_DAYS_PATTERN.matcher(text);
        if (recentDaysMatcher.find()) {
            int days = Integer.parseInt(recentDaysMatcher.group(1));
            if (days >= 1 && days <= 3660) {
                return timeWindow(today.minusDays(days), today.plusDays(1), "近 " + days + " 天");
            }
        }

        if (containsAny(text, "近7天", "近 7 天", "最近7天", "最近 7 天")) {
            return timeWindow(today.minusDays(7), today.plusDays(1), "近 7 天");
        }

        if (containsAny(text, "近30天", "近 30 天", "最近30天", "最近 30 天")) {
            return timeWindow(today.minusDays(30), today.plusDays(1), "近 30 天");
        }

        Matcher recentMonthsMatcher = RECENT_MONTHS_PATTERN.matcher(text);
        if (recentMonthsMatcher.find()) {
            int months = parseMonthNumber(recentMonthsMatcher.group(1));
            if (months >= 1 && months <= 24) {
                return timeWindow(today.minusMonths(months), today.plusDays(1), "近" + months + "个月");
            }
        }

        Matcher yearMatcher = EXPLICIT_YEAR_PATTERN.matcher(text);
        if (yearMatcher.find()) {
            int year = Integer.parseInt(yearMatcher.group(1));
            return timeWindow(LocalDate.of(year, 1, 1), LocalDate.of(year + 1, 1, 1), year + "年");
        }
        if (text.contains("最近")) {
            return timeWindow(today.minusDays(30), today.plusDays(1), "近 30 天");
        }
        return null;
    }

    private AiAgentTimeWindow allTimeWindow() {
        return new AiAgentTimeWindow(0L, Long.MAX_VALUE, "全部");
    }

    private int parseMonthNumber(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return switch (StringUtils.defaultString(value)) {
                case "一" -> 1;
                case "二" -> 2;
                case "三" -> 3;
                case "四" -> 4;
                case "五" -> 5;
                case "六" -> 6;
                case "七" -> 7;
                case "八" -> 8;
                case "九" -> 9;
                case "十" -> 10;
                case "十一" -> 11;
                case "十二" -> 12;
                default -> -1;
            };
        }
    }

    private AiAgentTimeWindow timeWindow(LocalDate start, LocalDate end, String label) {
        return new AiAgentTimeWindow(
                start.atStartOfDay(AGENT_ZONE).toInstant().toEpochMilli(),
                end.atStartOfDay(AGENT_ZONE).toInstant().toEpochMilli(),
                label);
    }
    DataPermissions unrestrictedDataPermissions() {
        DeptDataPermissionDTO permission = new DeptDataPermissionDTO();
        permission.setViewId(InternalUserView.ALL.name());
        permission.setAll(true);
        permission.setSelf(false);
        permission.setVisible(false);
        return new DataPermissions(permission, permission, permission);
    }

    AiAgentContext createContext(String userId, String orgId, AiAgentChatRequest request) {
        AiAgentContext context = new AiAgentContext();
        context.setUserId(userId);
        context.setOrganizationId(orgId);
        if (StringUtils.isNotBlank(request.getDatasourceId())) {
            datasourceService.populateContext(request.getDatasourceId(), context);
            context.setDataScope("datasource_account");
        } else {
            DataPermissions dataPermissions = resolveDataPermissions(userId, orgId);
            context.setDataScope(deriveDataScope(dataPermissions));
            context.setDataPermission(dataPermissions.customer());
            context.setCustomerDataPermission(dataPermissions.customer());
            context.setContractDataPermission(dataPermissions.contract());
            context.setOrderDataPermission(dataPermissions.order());
        }
        context.setLlmProvider(request.getLlmProvider());
        context.setQueryQuestion(request.getQuestion());
        context.setLimitRows(!Boolean.FALSE.equals(request.getLimitRows()));
        try {
            context.setTimeWindow(resolveTimeWindow(request.getQuestion(), request.getTimeRange()));
        } catch (java.time.DateTimeException | IllegalArgumentException e) {
            context.setTimeRangeError("无法可靠解析日期范围，请明确有效的开始和结束日期。");
        }
        return context;
    }

    private DataPermissions resolveDataPermissions(String userId, String orgId) {
        DeptDataPermissionDTO customer = dataScopeService.getDeptDataPermission(
                userId, orgId, InternalUserView.ALL.name(), PermissionConstants.CUSTOMER_MANAGEMENT_READ);
        DeptDataPermissionDTO contract = dataScopeService.getDeptDataPermission(
                userId, orgId, InternalUserView.ALL.name(), PermissionConstants.CONTRACT_READ);
        DeptDataPermissionDTO order = dataScopeService.getDeptDataPermission(
                userId, orgId, InternalUserView.ALL.name(), PermissionConstants.ORDER_READ);
        return new DataPermissions(customer, contract, order);
    }

    private String deriveDataScope(DataPermissions permissions) {
        if (Boolean.TRUE.equals(permissions.customer().getAll())
                && Boolean.TRUE.equals(permissions.contract().getAll())
                && Boolean.TRUE.equals(permissions.order().getAll())) {
            return "all";
        }
        if (Boolean.TRUE.equals(permissions.customer().getSelf())
                && Boolean.TRUE.equals(permissions.contract().getSelf())
                && Boolean.TRUE.equals(permissions.order().getSelf())) {
            return "self";
        }
        return "partial";
    }

    record DataPermissions(DeptDataPermissionDTO customer, DeptDataPermissionDTO contract,
                           DeptDataPermissionDTO order) {
    }
}
