package cn.cordys.crm.aiagent.service;

import cn.cordys.common.dto.DeptDataPermissionDTO;
import cn.cordys.common.util.JSON;
import cn.cordys.crm.aiagent.domain.AiAgentMessage;
import cn.cordys.crm.aiagent.domain.AiAgentSession;
import cn.cordys.crm.aiagent.dto.*;
import cn.cordys.crm.aiagent.dto.query.*;
import cn.cordys.crm.aiagent.dto.request.AiAgentChatRequest;
import cn.cordys.crm.aiagent.dto.response.AiAgentChatResponse;
import cn.cordys.mybatis.BaseMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiAgentQueryContinuationTest {
    @Test
    @SuppressWarnings("unchecked")
    void storedCursorIsAvailableOnlyToSameUserOrganizationSessionAndPermissions() {
        var sessions = (BaseMapper<AiAgentSession>) mock(BaseMapper.class);
        var messages = (BaseMapper<AiAgentMessage>) mock(BaseMapper.class);
        var audit = new AiAgentAuditService();
        ReflectionTestUtils.setField(audit, "aiAgentSessionMapper", sessions);
        ReflectionTestUtils.setField(audit, "aiAgentMessageMapper", messages);
        var context = context();
        var session = new AiAgentSession(); session.setId("session"); session.setUserId("user"); session.setOrganizationId("org");
        var snapshot = new AiAgentConversationSnapshot();
        snapshot.setPermissionFingerprint(AiAgentConversationService.permissionFingerprint(context)); snapshot.setContinuation(cursor());
        var message = new AiAgentMessage(); message.setSessionId("session"); message.setRole("assistant");
        message.setEvidenceJson(JSON.toJSONString(Map.of("conversation", snapshot)));
        when(sessions.selectByPrimaryKey("session")).thenReturn(session);
        when(messages.selectByPrimaryKey("message")).thenReturn(message);
        assertThat(audit.loadContinuation("message", "session", context).getNextOffset()).isEqualTo(30);
        context.setUserId("someone-else");
        assertThatThrownBy(() -> audit.loadContinuation("message", "session", context)).hasMessageContaining("该会话");
        context.setUserId("user"); context.setOrganizationId("other-org");
        assertThatThrownBy(() -> audit.loadContinuation("message", "session", context)).hasMessageContaining("该会话");
        context.setOrganizationId("org"); message.setSessionId("different-session");
        assertThatThrownBy(() -> audit.loadContinuation("message", "session", context)).hasMessageContaining("未找到");
        message.setSessionId("session"); context.getCustomerDataPermission().setAll(false);
        assertThatThrownBy(() -> audit.loadContinuation("message", "session", context)).hasMessageContaining("权限已变化");
    }


    @Test
    @SuppressWarnings("unchecked")
    void resultReferenceForAnalysisRegenerateAndExportChecksUserOrganizationSessionAndDatasourceRevision() {
        var sessions=(BaseMapper<AiAgentSession>)mock(BaseMapper.class);
        var messages=(BaseMapper<AiAgentMessage>)mock(BaseMapper.class);
        var audit=new AiAgentAuditService(); ReflectionTestUtils.setField(audit,"aiAgentSessionMapper",sessions);
        ReflectionTestUtils.setField(audit,"aiAgentMessageMapper",messages);
        var c=context(); c.setDatasourceId("source"); c.setDatasourceRevision(1);
        var session=new AiAgentSession(); session.setUserId("user"); session.setOrganizationId("org");
        var snapshot=new AiAgentConversationSnapshot(); snapshot.setPermissionFingerprint(AiAgentConversationService.permissionFingerprint(c));
        var response=new AiAgentChatResponse(); response.setQueryQuestion("每月金额"); response.setSqlQueries(List.of("SELECT 1 AS n"));
        var message=new AiAgentMessage(); message.setSessionId("session"); message.setRole("assistant");
        message.setEvidenceJson(JSON.toJSONString(Map.of("response",response,"conversation",snapshot)));
        when(sessions.selectByPrimaryKey("session")).thenReturn(session); when(messages.selectByPrimaryKey("message")).thenReturn(message);
        assertThat(audit.loadQueryResponse("message","session",c).getQueryQuestion()).isEqualTo("每月金额");
        c.setUserId("other"); assertThatThrownBy(() -> audit.loadQueryResponse("message","session",c)).hasMessageContaining("无法访问");
        c.setUserId("user"); c.setOrganizationId("other"); assertThatThrownBy(() -> audit.loadQueryResponse("message","session",c)).hasMessageContaining("无法访问");
        c.setOrganizationId("org"); message.setSessionId("other"); assertThatThrownBy(() -> audit.loadQueryResponse("message","session",c)).hasMessageContaining("无法访问");
        message.setSessionId("session"); c.setDatasourceRevision(2); assertThatThrownBy(() -> audit.loadQueryResponse("message","session",c)).hasMessageContaining("已变化");
    }
    private AiAgentQueryContinuation cursor() {
        var cursor=new AiAgentQueryContinuation(); cursor.setGeneratedSql("SELECT name AS n FROM customer");
        cursor.setNextOffset(30); cursor.setPageSize(30); return cursor;
    }
    private AiAgentContext context() {
        var context = new AiAgentContext(); context.setUserId("user"); context.setOrganizationId("org");
        var permission = new DeptDataPermissionDTO(); permission.setAll(true);
        context.setCustomerDataPermission(permission); context.setDataPermission(permission);
        return context;
    }
}
