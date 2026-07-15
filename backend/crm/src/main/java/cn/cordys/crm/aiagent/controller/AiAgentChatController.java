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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

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

    @PostMapping("/chat")
    @RequiresPermissions(PermissionConstants.AGENT_READ)
    @Operation(summary = "智能问答")
    public AiAgentChatResponse chat(@Valid @RequestBody AiAgentChatRequest request) {
        return aiAgentChatService.chat(request);
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
