package cn.cordys.crm.aiagent.service;

import cn.cordys.common.constants.InternalUserView;
import cn.cordys.common.constants.PermissionConstants;
import cn.cordys.common.dto.DeptDataPermissionDTO;
import cn.cordys.crm.aiagent.controller.AiAgentChatController;
import cn.cordys.crm.aiagent.dto.AiAgentContext;
import cn.cordys.crm.aiagent.dto.request.AiAgentChatRequest;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiAgentChatServiceDataPermissionTest {

    @Test
    void shouldGrantFullDataContextToUsersWhoCanUseTheAgent() {
        AiAgentChatService.DataPermissions permissions =
                new AiAgentChatService().unrestrictedDataPermissions();

        assertFullPermission(permissions.customer());
        assertFullPermission(permissions.contract());
        assertFullPermission(permissions.order());
    }

    @Test
    void shouldIgnoreNarrowRequestedScopeAndPopulateEveryContextPermissionAsFull() {
        AiAgentChatRequest request = new AiAgentChatRequest();
        request.setQuestion("查询客户数据");
        request.setTimeRange("30d");
        request.setDataScope("mine");

        AiAgentContext context = new AiAgentChatService().createContext("user-1", "org-1", request);

        assertThat(context.getDataScope()).isEqualTo("all");
        assertThat(List.of(
                context.getDataPermission(),
                context.getCustomerDataPermission(),
                context.getContractDataPermission(),
                context.getOrderDataPermission()))
                .allSatisfy(permission -> {
                    assertThat(permission.getAll()).isTrue();
                    assertThat(permission.getViewId()).isEqualTo(InternalUserView.ALL.name());
                });
    }

    @Test
    void shouldPreferExplicitChineseMonthOverDefaultRequestTimeRange() {
        AiAgentChatRequest request = new AiAgentChatRequest();
        request.setQuestion("2026年五月份新增的公司客户有哪些？");
        request.setTimeRange("30d");

        AiAgentContext context = new AiAgentChatService().createContext("user-1", "org-1", request);
        ZoneId zone = ZoneId.of("Asia/Shanghai");

        assertThat(context.getTimeWindow().label()).isEqualTo("2026年5月");
        assertThat(Instant.ofEpochMilli(context.getTimeWindow().startTime()).atZone(zone).toLocalDate())
                .isEqualTo(LocalDate.of(2026, 5, 1));
        assertThat(Instant.ofEpochMilli(context.getTimeWindow().endTime()).atZone(zone).toLocalDate())
                .isEqualTo(LocalDate.of(2026, 6, 1));
    }

    @Test
    void shouldKeepAgentReadAsTheChatAuthorizationBoundary() throws Exception {
        Method chat = AiAgentChatController.class.getDeclaredMethod("chat", AiAgentChatRequest.class);

        RequiresPermissions annotation = chat.getAnnotation(RequiresPermissions.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).containsExactly(PermissionConstants.AGENT_READ);
    }

    private void assertFullPermission(DeptDataPermissionDTO permission) {
        assertThat(permission).isNotNull();
        assertThat(permission.getViewId()).isEqualTo(InternalUserView.ALL.name());
        assertThat(permission.getAll()).isTrue();
        assertThat(permission.getSelf()).isFalse();
        assertThat(permission.getVisible()).isFalse();
        assertThat(permission.getDeptIds()).isEmpty();
    }
}
