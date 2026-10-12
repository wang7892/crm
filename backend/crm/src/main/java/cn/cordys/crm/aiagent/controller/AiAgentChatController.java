package cn.cordys.crm.aiagent.controller;

import cn.cordys.common.constants.PermissionConstants;
import cn.cordys.common.util.JSON;
import cn.cordys.crm.aiagent.dto.request.AiAgentChatRequest;
import cn.cordys.crm.aiagent.dto.request.AiAgentFeedbackRequest;
import cn.cordys.crm.aiagent.dto.response.AiAgentAudioTranscriptionResponse;
import cn.cordys.crm.aiagent.dto.response.AiAgentChatResponse;
import cn.cordys.crm.aiagent.dto.response.AiAgentMessageResponse;
import cn.cordys.crm.aiagent.dto.response.AiAgentSessionResponse;
import cn.cordys.crm.aiagent.service.AiAgentAudioTranscriptionService;
import cn.cordys.crm.aiagent.service.AiAgentAuditService;
import cn.cordys.crm.aiagent.service.AiAgentChatService;
import cn.cordys.crm.aiagent.service.AiAgentRequestCancellationService;
import cn.cordys.crm.aiagent.service.AiAgentRequestCancelledException;
import cn.cordys.crm.aiagent.service.AiAgentProgress;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import cn.cordys.security.SessionUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@Tag(name = "自研智能问答Agent")
@Validated
@RestController
@RequestMapping("/ai-agent")
public class AiAgentChatController {

    @Resource
    private AiAgentChatService aiAgentChatService;
    @Resource
    private AiAgentAuditService aiAgentAuditService;
    @Resource
    private AiAgentAudioTranscriptionService aiAgentAudioTranscriptionService;
    @Resource
    private AiAgentRequestCancellationService aiAgentRequestCancellationService;

    @PostMapping("/chat")
    @RequiresPermissions(PermissionConstants.AGENT_READ)
    @Operation(summary = "智能问答")
    public AiAgentChatResponse chat(@Valid @RequestBody AiAgentChatRequest request) {
        return withCancellation(request, () -> aiAgentChatService.chat(request));
    }

    @PostMapping("/result/export")
    @RequiresPermissions(PermissionConstants.AGENT_READ)
    public void exportResult(@Valid @RequestBody AiAgentChatRequest request, HttpServletResponse response) throws IOException {
        byte[] data = aiAgentChatService.exportResult(request);
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=query-result.xlsx");
        response.getOutputStream().write(data);
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RequiresPermissions(PermissionConstants.AGENT_READ)
    public void stream(@Valid @RequestBody AiAgentChatRequest request, HttpServletResponse response) {
        response.setContentType("text/event-stream;charset=UTF-8");
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        try (var scope = AiAgentProgress.open((event, data) -> {
            try {
                response.getOutputStream().write(("event: " + event + "\ndata: " + JSON.toJSONString(data) + "\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                response.flushBuffer();
            } catch (IOException e) { throw new AiAgentRequestCancelledException(); }
        })) {
            AiAgentProgress.stage("request", "开始处理问题");
            AiAgentChatResponse result = withCancellation(request, () -> aiAgentChatService.chat(request));
            if (result == null) AiAgentProgress.send("cancelled", Map.of("message", "已停止回答"));
            else AiAgentProgress.send("result", result);
        } catch (AiAgentRequestCancelledException ignored) {
            // A disconnected client must not cause a second JSON response or more model calls.
        } catch (RuntimeException e) {
            try (var scope = AiAgentProgress.open((event, data) -> {
                try {
                    response.getOutputStream().write(("event: " + event + "\ndata: " + JSON.toJSONString(data) + "\n\n")
                            .getBytes(StandardCharsets.UTF_8)); response.flushBuffer();
                } catch (IOException ignored) { }
            })) {
                AiAgentProgress.send("error", Map.of("message", "问答处理失败，请检查配置或稍后重试"));
            }
        }
    }

    @PostMapping(value = "/chat/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresPermissions(PermissionConstants.AGENT_READ)
    @Operation(summary = "带附件的智能问答")
    public AiAgentChatResponse chatWithAttachments(@Valid @RequestPart("request") AiAgentChatRequest request,
                                                   @RequestPart("files") List<MultipartFile> files) {
        return withCancellation(request, () -> aiAgentChatService.chatWithAttachments(request, files));
    }

    @PostMapping("/chat/cancel/{requestId}")
    @RequiresPermissions(PermissionConstants.AGENT_READ)
    @Operation(summary = "停止当前智能问答")
    public void cancelChat(@PathVariable String requestId) {
        aiAgentRequestCancellationService.cancel(requestId, SessionUtils.getUserId());
    }

    @PostMapping("/audio/transcriptions")
    @RequiresPermissions(PermissionConstants.AGENT_READ)
    @Operation(summary = "音频转文字")
    public AiAgentAudioTranscriptionResponse transcribeAudio(@RequestParam("file") MultipartFile file,
                                                             @RequestParam(value = "language", required = false)
                                                             String language,
                                                             @RequestParam(value = "request", required = false)
                                                             String request) {
        return aiAgentAudioTranscriptionService.transcribe(file, resolveAudioLanguage(language, request));
    }

    @GetMapping("/audio/transcriptions/{taskId}")
    @RequiresPermissions(PermissionConstants.AGENT_READ)
    @Operation(summary = "查询音频转文字结果")
    public AiAgentAudioTranscriptionResponse queryAudioTranscription(@PathVariable String taskId,
                                                                     @RequestParam(value = "language", required = false)
                                                                     String language) {
        return aiAgentAudioTranscriptionService.query(taskId, language);
    }

    @PostMapping("/feedback")
    @RequiresPermissions(PermissionConstants.AGENT_READ)
    @Operation(summary = "智能问答反馈")
    public void feedback(@Valid @RequestBody AiAgentFeedbackRequest request) {
        aiAgentAuditService.saveFeedback(request);
    }

    @GetMapping("/sessions")
    @RequiresPermissions(PermissionConstants.AGENT_READ)
    @Operation(summary = "智能问答会话列表")
    public List<AiAgentSessionResponse> sessions() {
        return aiAgentAuditService.listCurrentUserSessions();
    }

    @GetMapping("/sessions/{sessionId}/messages")
    @RequiresPermissions(PermissionConstants.AGENT_READ)
    @Operation(summary = "智能问答消息列表")
    public List<AiAgentMessageResponse> messages(@PathVariable String sessionId) {
        return aiAgentAuditService.listCurrentUserMessages(sessionId);
    }
    @DeleteMapping("/sessions/{sessionId}")
    @RequiresPermissions(PermissionConstants.AGENT_READ)
    @Operation(summary = "删除智能问答会话")
    public void deleteSession(@PathVariable String sessionId) {
        aiAgentAuditService.deleteCurrentUserSession(sessionId);
    }

    private AiAgentChatResponse withCancellation(AiAgentChatRequest request,
                                                 Supplier<AiAgentChatResponse> action) {
        String requestId = request.getRequestId();
        aiAgentRequestCancellationService.register(requestId, SessionUtils.getUserId());
        try {
            return action.get();
        } catch (AiAgentRequestCancelledException ignored) {
            return null;
        } finally {
            aiAgentRequestCancellationService.unregister(requestId);
        }
    }

    private String resolveAudioLanguage(String language, String request) {
        if (language != null && !language.isBlank()) {
            return language;
        }
        if (request == null || request.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> requestMap = JSON.parseToMap(request);
            Object languageValue = requestMap.get("language");
            return languageValue == null ? null : String.valueOf(languageValue);
        } catch (Exception e) {
            return null;
        }
    }
}
