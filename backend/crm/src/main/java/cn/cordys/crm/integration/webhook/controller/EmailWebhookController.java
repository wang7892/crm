package cn.cordys.crm.integration.webhook.controller;

import cn.cordys.common.context.OrganizationContextWebFilter;
import cn.cordys.common.security.ApiKeyHandler;
import cn.cordys.crm.integration.webhook.dto.request.EmailWebhookRequest;
import cn.cordys.crm.integration.webhook.dto.response.EmailWebhookResponse;
import cn.cordys.crm.integration.webhook.service.EmailWebhookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;

@Tag(name = "邮件Webhook")
@Slf4j
@RestController
@RequestMapping("/api/webhook")
public class EmailWebhookController {
    @Resource
    private EmailWebhookService emailWebhookService;

    @PostMapping("/email-log")
    @Operation(summary = "接收邮件事件Webhook")
    public ResponseEntity<EmailWebhookResponse> emailLog(
            @RequestBody EmailWebhookRequest request,
            @RequestHeader(value = OrganizationContextWebFilter.ORGANIZATION_ID_HEADER, required = false) String organizationId,
            HttpServletRequest httpServletRequest) {
        String apiKeyUserId = ApiKeyHandler.getUser(httpServletRequest);
        String messageId = request == null ? null : request.getMessageId();
        String sourceMailbox = request == null ? null : request.getSourceMailbox();
        String matchedTargetMailbox = request == null ? null : request.getMatchedTargetMailbox();
        log.info("email webhook received, messageId={}, sourceMailbox={}, matchedTargetMailbox={}, orgHeader={}, apiKeyUserId={}",
                messageId, sourceMailbox, matchedTargetMailbox, organizationId, apiKeyUserId);
        try {
            ResponseEntity<EmailWebhookResponse> response = emailWebhookService.handle(request, organizationId, apiKeyUserId);
            EmailWebhookResponse body = response == null ? null : response.getBody();
            log.info("email webhook handled, messageId={}, httpStatus={}, success={}, code={}",
                    messageId,
                    response == null ? "null" : response.getStatusCode().value(),
                    body == null ? null : body.isSuccess(),
                    body == null ? null : body.getCode());
            if (response == null || body == null) {
                // Keep client contract stable: webhook caller expects JSON with success/eventId.
                return ResponseEntity.internalServerError()
                        .body(new EmailWebhookResponse(false, null, "EMPTY_RESPONSE", "webhook response body is empty"));
            }
            return response;
        } catch (Exception e) {
            log.error("email webhook controller failed, messageId={}, orgHeader={}", messageId, organizationId, e);
            return ResponseEntity.internalServerError()
                    .body(new EmailWebhookResponse(false, null, "INTERNAL_ERROR", "internal server error"));
        }
    }
}

