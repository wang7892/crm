package cn.cordys.crm.integration.webhook.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
public class EmailWebhookRequest {

    @Schema(description = "组织ID")
    private String organizationId;

    @Schema(description = "被监控邮箱")
    private String sourceMailbox;

    @Schema(description = "邮件唯一标识")
    private String messageId;

    @Schema(description = "线程ID")
    private String threadId;

    @Schema(description = "发件地址")
    private String fromAddress;

    @Schema(description = "收件地址列表")
    private List<String> toAddresses;

    @Schema(description = "抄送地址列表")
    private List<String> ccAddresses;

    @Schema(description = "邮件主题")
    private String subject;

    @Schema(description = "邮件正文纯文本")
    private String contentText;

    @Schema(description = "发送时间毫秒")
    private Long sendTime;

    @Schema(description = "命中的目标邮箱")
    private String matchedTargetMailbox;

    @Schema(description = "附件列表")
    private List<EmailWebhookAttachmentRequest> attachments;
}

