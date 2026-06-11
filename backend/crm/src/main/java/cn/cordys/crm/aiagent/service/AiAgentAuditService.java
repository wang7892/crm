package cn.cordys.crm.aiagent.service;

import cn.cordys.common.uid.IDGenerator;
import cn.cordys.common.util.JSON;
import cn.cordys.context.OrganizationContext;
import cn.cordys.crm.aiagent.domain.AiAgentFeedback;
import cn.cordys.crm.aiagent.domain.AiAgentMessage;
import cn.cordys.crm.aiagent.domain.AiAgentSession;
import cn.cordys.crm.aiagent.domain.AiAgentToolCallLog;
import cn.cordys.crm.aiagent.dto.request.AiAgentFeedbackRequest;
import cn.cordys.crm.aiagent.dto.response.AiAgentMessageResponse;
import cn.cordys.crm.aiagent.dto.response.AiAgentSessionResponse;
import cn.cordys.crm.aiagent.dto.response.AiAgentToolCallDTO;
import cn.cordys.crm.aiagent.mapper.AiAgentInternalMapper;
import cn.cordys.mybatis.BaseMapper;
import cn.cordys.security.SessionUtils;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
@Transactional(rollbackFor = Exception.class)
public class AiAgentAuditService {

    private static final int SESSION_TITLE_LENGTH = 30;

    @Resource
    private BaseMapper<AiAgentSession> aiAgentSessionMapper;
    @Resource
    private BaseMapper<AiAgentMessage> aiAgentMessageMapper;
    @Resource
    private BaseMapper<AiAgentToolCallLog> aiAgentToolCallLogMapper;
    @Resource
    private BaseMapper<AiAgentFeedback> aiAgentFeedbackMapper;
    @Resource
    private AiAgentInternalMapper aiAgentInternalMapper;

    public AiAgentSession ensureSession(String sessionId, String question, String userId, String orgId) {
        if (StringUtils.isNotBlank(sessionId)) {
            AiAgentSession existing = aiAgentSessionMapper.selectByPrimaryKey(sessionId);
            if (existing != null && Objects.equals(existing.getUserId(), userId)
                    && Objects.equals(existing.getOrganizationId(), orgId)) {
                touch(existing.getId(), userId);
                return existing;
            }
        }

        long now = System.currentTimeMillis();
        AiAgentSession session = new AiAgentSession();
        session.setId(IDGenerator.nextStr());
        session.setOrganizationId(orgId);
        session.setUserId(userId);
        session.setTitle(buildTitle(question));
        session.setCreateUser(userId);
        session.setUpdateUser(userId);
        session.setCreateTime(now);
        session.setUpdateTime(now);
        aiAgentSessionMapper.insert(session);
        return session;
    }

    public AiAgentMessage saveMessage(String sessionId, String role, String content, String intent,
                                      Object evidence, String userId) {
        long now = System.currentTimeMillis();
        AiAgentMessage message = new AiAgentMessage();
        message.setId(IDGenerator.nextStr());
        message.setSessionId(sessionId);
        message.setRole(role);
        message.setContent(StringUtils.defaultString(content));
        message.setIntent(intent);
        message.setEvidenceJson(evidence == null ? null : JSON.toJSONString(evidence));
        message.setCreateUser(userId);
        message.setUpdateUser(userId);
        message.setCreateTime(now);
        message.setUpdateTime(now);
        aiAgentMessageMapper.insert(message);
        return message;
    }

    public void saveToolLogs(String messageId, List<AiAgentToolCallDTO> toolCalls, String userId) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (AiAgentToolCallDTO toolCall : toolCalls) {
            AiAgentToolCallLog log = new AiAgentToolCallLog();
            log.setId(IDGenerator.nextStr());
            log.setMessageId(messageId);
            log.setToolName(toolCall.getName());
            log.setInputJson(null);
            log.setOutputJson(JSON.toJSONString(toolCall));
            log.setStatus(toolCall.getStatus());
            log.setDurationMs(toolCall.getDurationMs());
            log.setCreateUser(userId);
            log.setUpdateUser(userId);
            log.setCreateTime(now);
            log.setUpdateTime(now);
            aiAgentToolCallLogMapper.insert(log);
        }
    }

    public void saveFeedback(AiAgentFeedbackRequest request) {
        long now = System.currentTimeMillis();
        String userId = SessionUtils.getUserId();
        AiAgentFeedback feedback = new AiAgentFeedback();
        feedback.setId(IDGenerator.nextStr());
        feedback.setMessageId(request.getMessageId());
        feedback.setUserId(userId);
        feedback.setRating(request.getRating());
        feedback.setComment(request.getComment());
        feedback.setCorrectAnswer(request.getCorrectAnswer());
        feedback.setCreateUser(userId);
        feedback.setUpdateUser(userId);
        feedback.setCreateTime(now);
        feedback.setUpdateTime(now);
        aiAgentFeedbackMapper.insert(feedback);
    }

    public List<AiAgentSessionResponse> listCurrentUserSessions() {
        return aiAgentInternalMapper.listSessions(OrganizationContext.getOrganizationId(), SessionUtils.getUserId(), 50);
    }

    public List<AiAgentMessageResponse> listCurrentUserMessages(String sessionId) {
        return aiAgentInternalMapper.listMessages(sessionId, OrganizationContext.getOrganizationId(), SessionUtils.getUserId())
                .stream()
                .map(this::toMessageResponse)
                .toList();
    }

    public void deleteCurrentUserSession(String sessionId) {
        if (StringUtils.isBlank(sessionId)) {
            return;
        }
        String orgId = OrganizationContext.getOrganizationId();
        String userId = SessionUtils.getUserId();
        aiAgentInternalMapper.deleteToolLogsBySession(sessionId, orgId, userId);
        aiAgentInternalMapper.deleteFeedbackBySession(sessionId, orgId, userId);
        aiAgentInternalMapper.deleteMessagesBySession(sessionId, orgId, userId);
        aiAgentInternalMapper.deleteSession(sessionId, orgId, userId);
    }

    public void touch(String sessionId, String userId) {
        AiAgentSession session = new AiAgentSession();
        session.setId(sessionId);
        session.setUpdateUser(userId);
        session.setUpdateTime(System.currentTimeMillis());
        aiAgentSessionMapper.update(session);
    }

    public void recordAnswerableQuestionHit(String orgId, String intent, String question, String answer,
                                            String toolName, List<String> dataSources, String userId) {
        if (StringUtils.isAnyBlank(orgId, intent)) {
            return;
        }
        long now = System.currentTimeMillis();
        String trimmedQuestion = truncate(question, 1024);
        int updated = aiAgentInternalMapper.updateAnswerableQuestionHit(
                orgId,
                intent,
                trimmedQuestion,
                now,
                userId
        );
        if (updated > 0 || StringUtils.isBlank(trimmedQuestion)) {
            return;
        }
        aiAgentInternalMapper.insertAnswerableQuestion(
                IDGenerator.nextStr(),
                orgId,
                trimmedQuestion,
                truncate(normalizeQuestion(trimmedQuestion), 1024),
                truncate(StringUtils.defaultString(answer), 1024),
                intent,
                truncate(StringUtils.defaultString(toolName), 128),
                JSON.toJSONString(dataSources == null ? List.of() : dataSources),
                now,
                userId
        );
    }

    public void recordUnansweredQuestion(String orgId, String userId, String sessionId, String messageId,
                                         String question, String missReason) {
        String normalizedQuestion = normalizeQuestion(question);
        if (StringUtils.isAnyBlank(orgId, normalizedQuestion)) {
            return;
        }
        long now = System.currentTimeMillis();
        String trimmedQuestion = truncate(StringUtils.defaultString(question).trim(), 1024);
        String trimmedNormalizedQuestion = truncate(normalizedQuestion, 1024);
        int updated = aiAgentInternalMapper.updateUnansweredQuestion(
                orgId,
                userId,
                sessionId,
                messageId,
                trimmedQuestion,
                trimmedNormalizedQuestion,
                StringUtils.defaultIfBlank(missReason, "NO_MATCH"),
                now
        );
        if (updated > 0) {
            return;
        }
        aiAgentInternalMapper.insertUnansweredQuestion(
                IDGenerator.nextStr(),
                orgId,
                userId,
                sessionId,
                messageId,
                trimmedQuestion,
                trimmedNormalizedQuestion,
                StringUtils.defaultIfBlank(missReason, "NO_MATCH"),
                now
        );
    }

    private String buildTitle(String question) {
        String text = StringUtils.defaultString(question).trim().replaceAll("\\s+", " ");
        if (text.length() <= SESSION_TITLE_LENGTH) {
            return StringUtils.defaultIfBlank(text, "新聊天");
        }
        return text.substring(0, SESSION_TITLE_LENGTH) + "...";
    }

    private AiAgentMessageResponse toMessageResponse(AiAgentMessage message) {
        AiAgentMessageResponse response = new AiAgentMessageResponse();
        response.setId(message.getId());
        response.setRole(message.getRole());
        response.setContent(message.getContent());
        response.setIntent(message.getIntent());
        response.setEvidenceJson(message.getEvidenceJson());
        response.setCreateTime(message.getCreateTime());
        return response;
    }

    public Map<String, Object> responseEvidenceSnapshot(Object response) {
        return Map.of("response", response);
    }

    private String normalizeQuestion(String question) {
        return StringUtils.defaultString(question)
                .trim()
                .replaceAll("[\\s，。？！?；;：:、,.`\"'“”‘’<>《》（）()【】\\[\\]{}]+", "")
                .toLowerCase(Locale.ROOT);
    }

    private String truncate(String text, int maxLength) {
        String value = StringUtils.defaultString(text);
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
