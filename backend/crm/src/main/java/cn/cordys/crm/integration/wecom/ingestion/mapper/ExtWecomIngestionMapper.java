package cn.cordys.crm.integration.wecom.ingestion.mapper;

import cn.cordys.crm.integration.wecom.ingestion.domain.WecomIngestionEvent;
import cn.cordys.crm.integration.wecom.ingestion.domain.WecomIngestionSessionDay;
import cn.cordys.crm.integration.wecom.ingestion.dto.response.WecomIngestionSessionRow;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface ExtWecomIngestionMapper {

    long countSessions(@Param("organizationId") String organizationId);

    List<WecomIngestionSessionRow> listSessions(@Param("organizationId") String organizationId,
                                                @Param("offset") long offset,
                                                @Param("limit") int limit);

    long countMessagesBySession(@Param("organizationId") String organizationId, @Param("sessionKey") String sessionKey);

    List<WecomIngestionEvent> listMessagesBySession(@Param("organizationId") String organizationId,
                                                    @Param("sessionKey") String sessionKey,
                                                    @Param("offset") long offset,
                                                    @Param("limit") int limit);

    List<WecomIngestionEvent> listUnsyncedMessagesBySession(@Param("organizationId") String organizationId,
                                                            @Param("sessionKey") String sessionKey,
                                                            @Param("offset") long offset,
                                                            @Param("limit") int limit);

    List<WecomIngestionEvent> listMessagesByIds(@Param("organizationId") String organizationId,
                                                @Param("ids") List<String> ids);

    void markMessagesSuccess(@Param("organizationId") String organizationId,
                             @Param("ids") List<String> ids,
                             @Param("followRecordId") String followRecordId,
                             @Param("operatorUserId") String operatorUserId,
                             @Param("now") long now);

    void insertMessageFollowRecords(@Param("organizationId") String organizationId,
                                    @Param("messageIds") List<String> messageIds,
                                    @Param("followRecordId") String followRecordId,
                                    @Param("operatorUserId") String operatorUserId,
                                    @Param("now") long now);

    void refreshSessionDaySyncState(@Param("organizationId") String organizationId,
                                    @Param("sessionDayId") String sessionDayId,
                                    @Param("latestFollowRecordId") String latestFollowRecordId,
                                    @Param("operatorUserId") String operatorUserId,
                                    @Param("now") long now);

    int deleteMessageFollowRecordsBySession(@Param("organizationId") String organizationId,
                                            @Param("sessionDayId") String sessionDayId);

    void deleteMediaBySession(@Param("organizationId") String organizationId,
                              @Param("sessionDayId") String sessionDayId);

    int deleteMessagesBySession(@Param("organizationId") String organizationId,
                                @Param("sessionDayId") String sessionDayId);

    int deleteSessionDay(@Param("organizationId") String organizationId,
                         @Param("sessionDayId") String sessionDayId);

    List<String> listPendingAutoFollowIds(@Param("limit") int limit,
                                          @Param("includeCurrentDay") boolean includeCurrentDay);

    List<WecomIngestionSessionDay> listPendingAutoFollow(@Param("limit") int limit,
                                                         @Param("includeCurrentDay") boolean includeCurrentDay);
}
