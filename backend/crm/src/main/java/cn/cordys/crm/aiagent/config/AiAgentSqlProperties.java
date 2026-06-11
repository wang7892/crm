package cn.cordys.crm.aiagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@Data
@ConfigurationProperties(prefix = "crm.ai-agent.sql")
public class AiAgentSqlProperties {
    private boolean enabled = false;
    private int maxRows = 100;
    private boolean auditEnabled = true;
    private List<String> allowedTables = List.of(
            "customer",
            "sys_user",
            "sys_organization_user",
            "customer_collaboration",
            "contract",
            "follow_up_record",
            "wecom_ingestion_session_day",
            "wecom_ingestion_message",
            "wecom_ingestion_media",
            "wecom_ingestion_message_follow_record",
            "email_webhook_event",
            "email_webhook_attachment",
            "contract_info",
            "mls_agent_data.contract_info"
    );
}
