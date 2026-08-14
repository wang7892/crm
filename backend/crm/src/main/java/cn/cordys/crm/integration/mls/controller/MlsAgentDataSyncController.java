package cn.cordys.crm.integration.mls.controller;

import cn.cordys.common.constants.PermissionConstants;
import cn.cordys.context.OrganizationContext;
import cn.cordys.crm.integration.mls.dto.MlsAgentDataSyncRequest;
import cn.cordys.crm.integration.mls.dto.MlsAgentDataSyncResult;
import cn.cordys.crm.integration.mls.service.MlsAgentDataSyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "MLS 外部数据库同步")
@RestController
@RequestMapping("/integration/mls-agent-data")
public class MlsAgentDataSyncController {

    @Resource
    private MlsAgentDataSyncService syncService;

    @PostMapping("/sync")
    @RequiresPermissions(PermissionConstants.SYSTEM_SETTING_UPDATE)
    @Operation(summary = "手动同步 MLS 客户、合同和订单")
    public MlsAgentDataSyncResult sync(@RequestBody(required = false) MlsAgentDataSyncRequest request) {
        Integer pageSize = request == null ? null : request.getPageSize();
        return syncService.sync(OrganizationContext.getOrganizationId(), "MANUAL", pageSize);
    }
}
