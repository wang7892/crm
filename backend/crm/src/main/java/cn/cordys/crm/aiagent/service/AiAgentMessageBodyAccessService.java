package cn.cordys.crm.aiagent.service;

import cn.cordys.crm.aiagent.config.AiAgentMessageBodyProperties;
import cn.cordys.crm.aiagent.dto.response.AiAgentChatResponse;
import cn.cordys.security.SessionUtils;
import org.springframework.stereotype.Service;

@Service
public class AiAgentMessageBodyAccessService {

    private final AiAgentMessageBodyProperties properties;

    public AiAgentMessageBodyAccessService(AiAgentMessageBodyProperties properties) {
        this.properties = properties;
    }

    public boolean isMessageBodyQuestion(String question) {
        return question != null && (question.contains("聊天正文") || question.contains("邮件正文")
                || question.contains("聊天内容") || question.contains("邮件内容"));
    }

    public AiAgentChatResponse explainAccessPolicy() {
        AiAgentChatResponse response = new AiAgentChatResponse();
        response.setIntent("MESSAGE_BODY_ACCESS_POLICY");
        response.getEvidence().add("wecom_ingestion_message");
        response.getEvidence().add("email_webhook_event");
        if (!properties.isEnabled()) {
            response.setAnswer("聊天正文和邮件正文查看能力当前未开启。可以返回沟通统计；如需老板或管理员查看正文，需要先开启正文查看配置并接入权限审计。");
            response.getWarnings().add("配置项：crm.ai-agent.message-body.enabled=false。");
            return response;
        }
        response.setAnswer("聊天正文和邮件正文需要通过授权角色、数据范围、脱敏和审计后才能返回。当前用户 ID："
                + SessionUtils.getUserId() + "。");
        response.getWarnings().add("当前版本已接入正文查看策略说明，正文查询工具需要继续按角色和客户权限实现。");
        return response;
    }
}
