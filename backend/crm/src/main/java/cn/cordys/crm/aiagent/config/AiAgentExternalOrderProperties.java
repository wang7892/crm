package cn.cordys.crm.aiagent.config;

import lombok.Data;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "crm.ai-agent.external-order")
public class AiAgentExternalOrderProperties {
    private boolean enabled = false;
    private String organizationId;
    private String url;
    private String username;
    private String password;
    private int maximumPoolSize = 3;
    private long connectionTimeout = 30000;
    private long socketTimeout = 120000;
    private int queryTimeoutSeconds = 120;

    public boolean allowsOrganization(String currentOrganizationId) {
        return enabled
                && StringUtils.isNotBlank(organizationId)
                && StringUtils.equals(organizationId, currentOrganizationId);
    }
}
