package cn.cordys.crm.aiagent.service;

import cn.cordys.common.constants.InternalUserView;
import cn.cordys.common.constants.PermissionConstants;
import cn.cordys.common.dto.DeptDataPermissionDTO;
import cn.cordys.crm.aiagent.controller.AiAgentChatController;
import cn.cordys.crm.aiagent.dto.request.AiAgentChatRequest;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

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
    void shouldDeriveSelfScopeWhenAllPermissionsAreSelf() {
        DeptDataPermissionDTO selfPermission = new DeptDataPermissionDTO();
        selfPermission.setViewId(InternalUserView.ALL.name());
        selfPermission.setSelf(true);

        AiAgentChatService service = new AiAgentChatService();
        AiAgentChatService.DataPermissions permissions =
                new AiAgentChatService.DataPermissions(selfPermission, selfPermission, selfPermission);

        // Use reflection to call private deriveDataScope
        try {
            Method method = AiAgentChatService.class.getDeclaredMethod("deriveDataScope",
                    AiAgentChatService.DataPermissions.class);
            method.setAccessible(true);
            String scope = (String) method.invoke(service, permissions);
            assertThat(scope).isEqualTo("self");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void shouldDerivePartialScopeWhenPermissionsAreMixed() {
        DeptDataPermissionDTO allPermission = new DeptDataPermissionDTO();
        allPermission.setAll(true);

        DeptDataPermissionDTO selfPermission = new DeptDataPermissionDTO();
        selfPermission.setSelf(true);

        AiAgentChatService service = new AiAgentChatService();
        AiAgentChatService.DataPermissions permissions =
                new AiAgentChatService.DataPermissions(allPermission, selfPermission, selfPermission);

        try {
            Method method = AiAgentChatService.class.getDeclaredMethod("deriveDataScope",
                    AiAgentChatService.DataPermissions.class);
            method.setAccessible(true);
            String scope = (String) method.invoke(service, permissions);
            assertThat(scope).isEqualTo("partial");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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
