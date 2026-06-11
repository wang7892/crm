package cn.cordys.crm.aiagent.mapper;

import cn.cordys.common.dto.DeptDataPermissionDTO;
import cn.cordys.crm.aiagent.domain.AiAgentMessage;
import cn.cordys.crm.aiagent.dto.internal.AiAgentCommunicationRow;
import cn.cordys.crm.aiagent.dto.internal.AiAgentCustomerRow;
import cn.cordys.crm.aiagent.dto.internal.AiAgentFollowRecordRow;
import cn.cordys.crm.aiagent.dto.internal.AiAgentUnansweredQuestionRow;
import cn.cordys.crm.aiagent.dto.response.AiAgentSessionResponse;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface AiAgentInternalMapper {

    String findUserNameById(@Param("userId") String userId);

    List<String> findUserNamesByDataPermission(@Param("orgId") String orgId,
                                               @Param("userId") String userId,
                                               @Param("dataPermission") DeptDataPermissionDTO dataPermission);

    List<AiAgentCustomerRow> findCustomersBySpecialist(@Param("orgId") String orgId,
                                                       @Param("userId") String userId,
                                                       @Param("specialistName") String specialistName,
                                                       @Param("dataPermission") DeptDataPermissionDTO dataPermission,
                                                       @Param("limit") int limit);

    List<AiAgentCustomerRow> searchCustomers(@Param("orgId") String orgId,
                                             @Param("userId") String userId,
                                             @Param("keyword") String keyword,
                                             @Param("dataPermission") DeptDataPermissionDTO dataPermission,
                                             @Param("limit") int limit);

    List<AiAgentCommunicationRow> communicationBySpecialist(@Param("orgId") String orgId,
                                                            @Param("userId") String userId,
                                                            @Param("specialistName") String specialistName,
                                                            @Param("startTime") long startTime,
                                                            @Param("endTime") long endTime,
                                                            @Param("dataPermission") DeptDataPermissionDTO dataPermission,
                                                            @Param("limit") int limit);

    List<AiAgentCommunicationRow> communicationByCustomer(@Param("orgId") String orgId,
                                                          @Param("userId") String userId,
                                                          @Param("customerName") String customerName,
                                                          @Param("startTime") long startTime,
                                                          @Param("endTime") long endTime,
                                                          @Param("dataPermission") DeptDataPermissionDTO dataPermission,
                                                          @Param("limit") int limit);

    List<AiAgentCommunicationRow> listVisibleCustomerCommunicationSummary(@Param("orgId") String orgId,
                                                                          @Param("userId") String userId,
                                                                          @Param("startTime") long startTime,
                                                                          @Param("endTime") long endTime,
                                                                          @Param("dataPermission") DeptDataPermissionDTO dataPermission,
                                                                          @Param("limit") int limit);

    List<AiAgentFollowRecordRow> listCustomerFollowRecords(@Param("orgId") String orgId,
                                                           @Param("userId") String userId,
                                                           @Param("customerName") String customerName,
                                                           @Param("dataPermission") DeptDataPermissionDTO dataPermission,
                                                           @Param("limit") int limit);

    List<AiAgentUnansweredQuestionRow> listPendingUnansweredQuestions(@Param("orgId") String orgId,
                                                                      @Param("limit") int limit);

    List<AiAgentSessionResponse> listSessions(@Param("orgId") String orgId,
                                              @Param("userId") String userId,
                                              @Param("limit") int limit);

    List<AiAgentMessage> listMessages(@Param("sessionId") String sessionId,
                                      @Param("orgId") String orgId,
                                      @Param("userId") String userId);

    int deleteToolLogsBySession(@Param("sessionId") String sessionId,
                                @Param("orgId") String orgId,
                                @Param("userId") String userId);

    int deleteFeedbackBySession(@Param("sessionId") String sessionId,
                                @Param("orgId") String orgId,
                                @Param("userId") String userId);

    int deleteMessagesBySession(@Param("sessionId") String sessionId,
                                @Param("orgId") String orgId,
                                @Param("userId") String userId);

    int deleteSession(@Param("sessionId") String sessionId,
                      @Param("orgId") String orgId,
                      @Param("userId") String userId);

    int updateAnswerableQuestionHit(@Param("orgId") String orgId,
                                    @Param("intent") String intent,
                                    @Param("question") String question,
                                    @Param("now") long now,
                                    @Param("userId") String userId);

    int insertAnswerableQuestion(@Param("id") String id,
                                 @Param("orgId") String orgId,
                                 @Param("question") String question,
                                 @Param("normalizedQuestion") String normalizedQuestion,
                                 @Param("answer") String answer,
                                 @Param("intent") String intent,
                                 @Param("toolName") String toolName,
                                 @Param("dataSources") String dataSources,
                                 @Param("now") long now,
                                 @Param("userId") String userId);

    int updateUnansweredQuestion(@Param("orgId") String orgId,
                                 @Param("userId") String userId,
                                 @Param("sessionId") String sessionId,
                                 @Param("messageId") String messageId,
                                 @Param("question") String question,
                                 @Param("normalizedQuestion") String normalizedQuestion,
                                 @Param("missReason") String missReason,
                                 @Param("now") long now);

    int insertUnansweredQuestion(@Param("id") String id,
                                 @Param("orgId") String orgId,
                                 @Param("userId") String userId,
                                 @Param("sessionId") String sessionId,
                                 @Param("messageId") String messageId,
                                 @Param("question") String question,
                                 @Param("normalizedQuestion") String normalizedQuestion,
                                 @Param("missReason") String missReason,
                                 @Param("now") long now);
}
