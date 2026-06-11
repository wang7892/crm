package cn.cordys.crm.integration.wecom.ingestion.controller;

import cn.cordys.common.constants.PermissionConstants;
import cn.cordys.common.pager.PagerWithOption;
import cn.cordys.context.OrganizationContext;
import cn.cordys.crm.follow.domain.FollowUpRecord;
import cn.cordys.crm.integration.wecom.ingestion.dto.request.WecomIngestionDeleteSessionRequest;
import cn.cordys.crm.integration.wecom.ingestion.dto.request.WecomIngestionMessagesPageRequest;
import cn.cordys.crm.integration.wecom.ingestion.dto.request.WecomIngestionSessionPageRequest;
import cn.cordys.crm.integration.wecom.ingestion.dto.request.WecomIngestionSyncFollowRequest;
import cn.cordys.crm.integration.wecom.ingestion.dto.response.WecomIngestionMessageRowResponse;
import cn.cordys.crm.integration.wecom.ingestion.dto.response.WecomIngestionSessionResponse;
import cn.cordys.crm.integration.wecom.ingestion.service.WecomIngestionService;
import cn.cordys.security.SessionUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.apache.shiro.authz.annotation.Logical;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "企业微信监控缓冲")
@RestController
@RequestMapping("/wecom/ingestion")
public class WecomIngestionController {

    @Resource
    private WecomIngestionService wecomIngestionService;

    @PostMapping("/session/page")
    @RequiresPermissions(value = {PermissionConstants.CUSTOMER_MANAGEMENT_READ, PermissionConstants.CUSTOMER_MANAGEMENT_POOL_READ}, logical = Logical.OR)
    @Operation(summary = "分页查询企微会话列表")
    public PagerWithOption<List<WecomIngestionSessionResponse>> sessionPage(@Validated @RequestBody WecomIngestionSessionPageRequest request) {
        return wecomIngestionService.pageSessions(request, OrganizationContext.getOrganizationId());
    }

    @PostMapping("/message/page")
    @RequiresPermissions(value = {PermissionConstants.CUSTOMER_MANAGEMENT_READ, PermissionConstants.CUSTOMER_MANAGEMENT_POOL_READ}, logical = Logical.OR)
    @Operation(summary = "分页查询会话消息")
    public PagerWithOption<List<WecomIngestionMessageRowResponse>> messagePage(@Validated @RequestBody WecomIngestionMessagesPageRequest request) {
        return wecomIngestionService.pageMessages(request, OrganizationContext.getOrganizationId());
    }

    @PostMapping("/sync-follow")
    @RequiresPermissions(PermissionConstants.CUSTOMER_MANAGEMENT_UPDATE)
    @Operation(summary = "将选中消息同步为客户跟进记录")
    public FollowUpRecord syncFollow(@Validated @RequestBody WecomIngestionSyncFollowRequest request) {
        return wecomIngestionService.syncFollow(request, OrganizationContext.getOrganizationId(), SessionUtils.getUserId());
    }

    @PostMapping("/session/delete")
    @RequiresPermissions(PermissionConstants.CUSTOMER_MANAGEMENT_UPDATE)
    @Operation(summary = "删除企业微信监控会话缓冲")
    public void deleteSession(@Validated @RequestBody WecomIngestionDeleteSessionRequest request) {
        wecomIngestionService.deleteSession(request, OrganizationContext.getOrganizationId());
    }
}
