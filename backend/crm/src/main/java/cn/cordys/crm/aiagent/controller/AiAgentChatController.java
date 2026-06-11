package cn.cordys.crm.aiagent.controller;

import cn.cordys.common.constants.PermissionConstants;
import cn.cordys.crm.aiagent.dto.request.AiAgentChatRequest;
import cn.cordys.crm.aiagent.dto.request.AiAgentFeedbackRequest;
import cn.cordys.crm.aiagent.dto.response.AiAgentChatResponse;
import cn.cordys.crm.aiagent.dto.response.AiAgentMessageResponse;
import cn.cordys.crm.aiagent.dto.response.AiAgentSessionResponse;
import cn.cordys.crm.aiagent.service.AiAgentAuditService;
import cn.cordys.crm.aiagent.service.AiAgentChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "自研智能问答Agent")
@Validated
@RestController
@RequestMapping("/ai-agent")
public class AiAgentChatController {

    @Resource
    private AiAgentChatService aiAgentChatService;
    @Resource
    private AiAgentAuditService aiAgentAuditService;

    @PostMapping("/chat")
    @RequiresPermissions(PermissionConstants.CUSTOMER_MANAGEMENT_READ)
    @Operation(summary = "智能问答")
    public AiAgentChatResponse chat(@Valid @RequestBody AiAgentChatRequest request) {
        return aiAgentChatService.chat(request);
    }

    @PostMapping("/feedback")
    @RequiresPermissions(PermissionConstants.CUSTOMER_MANAGEMENT_READ)
    @Operation(summary = "智能问答反馈")
    public void feedback(@Valid @RequestBody AiAgentFeedbackRequest request) {
        aiAgentAuditService.saveFeedback(request);
    }

    @GetMapping("/sessions")
    @RequiresPermissions(PermissionConstants.CUSTOMER_MANAGEMENT_READ)
    @Operation(summary = "智能问答会话列表")
    public List<AiAgentSessionResponse> sessions() {
        return aiAgentAuditService.listCurrentUserSessions();
    }

    @GetMapping("/sessions/{sessionId}/messages")
    @RequiresPermissions(PermissionConstants.CUSTOMER_MANAGEMENT_READ)
    @Operation(summary = "智能问答消息列表")
    public List<AiAgentMessageResponse> messages(@PathVariable String sessionId) {
        return aiAgentAuditService.listCurrentUserMessages(sessionId);
    }
    @DeleteMapping("/sessions/{sessionId}")
    @RequiresPermissions(PermissionConstants.CUSTOMER_MANAGEMENT_READ)
    @Operation(summary = "删除智能问答会话")
    public void deleteSession(@PathVariable String sessionId) {
        aiAgentAuditService.deleteCurrentUserSession(sessionId);
    }
}
