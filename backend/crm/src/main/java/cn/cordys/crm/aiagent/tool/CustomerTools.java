package cn.cordys.crm.aiagent.tool;

import cn.cordys.crm.aiagent.dto.AiAgentContext;
import cn.cordys.crm.aiagent.dto.internal.AiAgentCustomerRow;
import cn.cordys.crm.aiagent.mapper.AiAgentInternalMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CustomerTools {

    @Resource
    private AiAgentInternalMapper aiAgentInternalMapper;

    public List<AiAgentCustomerRow> findCustomersBySpecialist(AiAgentContext context, String specialistName, int limit) {
        return aiAgentInternalMapper.findCustomersBySpecialist(
                context.getOrganizationId(),
                context.getUserId(),
                specialistName,
                context.getDataPermission(),
                limit
        );
    }

    public List<AiAgentCustomerRow> searchCustomers(AiAgentContext context, String keyword, int limit) {
        return aiAgentInternalMapper.searchCustomers(
                context.getOrganizationId(),
                context.getUserId(),
                keyword,
                context.getDataPermission(),
                limit
        );
    }
}
