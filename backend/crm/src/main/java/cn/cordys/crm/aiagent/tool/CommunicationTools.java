package cn.cordys.crm.aiagent.tool;

import cn.cordys.crm.aiagent.dto.AiAgentContext;
import cn.cordys.crm.aiagent.dto.internal.AiAgentCommunicationRow;
import cn.cordys.crm.aiagent.mapper.AiAgentInternalMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CommunicationTools {

    @Resource
    private AiAgentInternalMapper aiAgentInternalMapper;

    public List<AiAgentCommunicationRow> salesCommunicationSummary(AiAgentContext context, String specialistName, int limit) {
        return aiAgentInternalMapper.communicationBySpecialist(
                context.getOrganizationId(),
                context.getUserId(),
                specialistName,
                context.getTimeWindow().startTime(),
                context.getTimeWindow().endTime(),
                context.getDataPermission(),
                limit
        );
    }

    public List<AiAgentCommunicationRow> customerCommunicationSummary(AiAgentContext context, String customerName, int limit) {
        return aiAgentInternalMapper.communicationByCustomer(
                context.getOrganizationId(),
                context.getUserId(),
                customerName,
                context.getTimeWindow().startTime(),
                context.getTimeWindow().endTime(),
                context.getDataPermission(),
                limit
        );
    }

    public List<AiAgentCommunicationRow> visibleCustomerCommunicationSummary(AiAgentContext context, int limit) {
        return aiAgentInternalMapper.listVisibleCustomerCommunicationSummary(
                context.getOrganizationId(),
                context.getUserId(),
                context.getTimeWindow().startTime(),
                context.getTimeWindow().endTime(),
                context.getDataPermission(),
                limit
        );
    }
}
