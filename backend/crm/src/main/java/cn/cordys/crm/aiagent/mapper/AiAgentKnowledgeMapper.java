package cn.cordys.crm.aiagent.mapper;

import cn.cordys.crm.aiagent.domain.AiKnowledgeChunk;
import cn.cordys.crm.aiagent.domain.AiKnowledgeDocument;
import cn.cordys.crm.aiagent.domain.AiKnowledgeParseJob;
import cn.cordys.crm.aiagent.dto.request.AiKnowledgeDocumentPageRequest;
import cn.cordys.crm.aiagent.dto.request.AiSemanticRulePageRequest;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface AiAgentKnowledgeMapper {
    List<AiKnowledgeDocument> listDocuments(@Param("request") AiKnowledgeDocumentPageRequest request,
                                            @Param("orgId") String orgId);

    List<AiKnowledgeChunk> listChunks(@Param("documentId") String documentId,
                                      @Param("orgId") String orgId);

    List<AiKnowledgeChunk> searchChunks(@Param("question") String question,
                                        @Param("keywords") List<String> keywords,
                                        @Param("orgId") String orgId,
                                        @Param("limit") int limit);

    AiKnowledgeParseJob getParseJobForUpdate(@Param("jobId") String jobId);

    AiKnowledgeParseJob getLatestParseJobForUpdate(@Param("documentId") String documentId,
                                                    @Param("orgId") String orgId);

    AiKnowledgeDocument getKnowledgeDocumentForUpdate(@Param("documentId") String documentId,
                                                       @Param("orgId") String orgId);

    int claimParseJob(@Param("jobId") String jobId,
                      @Param("now") long now);

    int updateParseJobState(@Param("jobId") String jobId,
                            @Param("orgId") String orgId,
                            @Param("status") String status,
                            @Param("step") String step,
                            @Param("message") String message,
                            @Param("errorStack") String errorStack,
                            @Param("startTime") Long startTime,
                            @Param("finishTime") Long finishTime,
                            @Param("updateTime") long updateTime);

    int updateKnowledgeDocumentParseState(@Param("documentId") String documentId,
                                          @Param("orgId") String orgId,
                                          @Param("parseStatus") String parseStatus,
                                          @Param("parseError") String parseError,
                                          @Param("chunkCount") Integer chunkCount,
                                          @Param("updateUser") String updateUser,
                                          @Param("updateTime") long updateTime);

    List<String> listPendingParseJobIds(@Param("limit") int limit);

    List<String> listStaleRunningParseJobIds(@Param("staleBefore") long staleBefore,
                                             @Param("limit") int limit);

    List<AiKnowledgeChunk> listSemanticRuleChunks(@Param("request") AiSemanticRulePageRequest request,
                                                  @Param("orgId") String orgId);

    AiKnowledgeChunk getSemanticRuleChunk(@Param("chunkId") String chunkId,
                                          @Param("orgId") String orgId);

    AiKnowledgeDocument getSemanticDocumentForUpdate(@Param("documentId") String documentId,
                                                      @Param("orgId") String orgId);

    int updateSemanticRuleChunkOptimistic(@Param("chunkId") String chunkId,
                                          @Param("orgId") String orgId,
                                          @Param("expectedUpdateTime") long expectedUpdateTime,
                                          @Param("title") String title,
                                          @Param("content") String content,
                                          @Param("contentHash") String contentHash,
                                          @Param("enabled") int enabled,
                                          @Param("updateUser") String updateUser,
                                          @Param("updateTime") long updateTime);

    int updateSemanticRuleChunk(@Param("chunkId") String chunkId,
                                @Param("orgId") String orgId,
                                @Param("title") String title,
                                @Param("content") String content,
                                @Param("contentHash") String contentHash,
                                @Param("enabled") int enabled,
                                @Param("updateUser") String updateUser,
                                @Param("updateTime") long updateTime);

    int updateSemanticRuleChunkEnabled(@Param("chunkId") String chunkId,
                                       @Param("orgId") String orgId,
                                       @Param("enabled") int enabled,
                                       @Param("updateUser") String updateUser,
                                       @Param("updateTime") long updateTime);

    int updateSemanticDocumentEnabled(@Param("documentId") String documentId,
                                      @Param("orgId") String orgId,
                                      @Param("enabled") int enabled,
                                      @Param("updateUser") String updateUser,
                                      @Param("updateTime") long updateTime);

    int updateSemanticChunksEnabledByDocument(@Param("documentId") String documentId,
                                              @Param("orgId") String orgId,
                                              @Param("enabled") int enabled,
                                              @Param("updateUser") String updateUser,
                                              @Param("updateTime") long updateTime);

    List<AiKnowledgeChunk> listSemanticRuleChunksByDocument(@Param("documentId") String documentId,
                                                            @Param("orgId") String orgId);

    List<AiKnowledgeChunk> listPublishedSemanticRuleChunks(@Param("orgId") String orgId);

    List<AiKnowledgeChunk> revalidatePublishedSemanticRuleChunks(@Param("orgId") String orgId,
                                                                 @Param("chunkIds") List<String> chunkIds);

    List<AiKnowledgeChunk> listSemanticRuleVersions(@Param("ruleId") String ruleId,
                                                    @Param("orgId") String orgId);

    AiKnowledgeChunk getSemanticRuleVersion(@Param("ruleId") String ruleId,
                                            @Param("version") int version,
                                            @Param("orgId") String orgId);

    List<AiKnowledgeChunk> listActiveSemanticRuleVersions(@Param("ruleId") String ruleId,
                                                          @Param("orgId") String orgId);

    int maxSemanticRuleVersion(@Param("ruleId") String ruleId,
                               @Param("orgId") String orgId);

    int disableOtherSemanticRuleVersions(@Param("ruleId") String ruleId,
                                         @Param("exceptChunkId") String exceptChunkId,
                                         @Param("orgId") String orgId,
                                         @Param("updateUser") String updateUser,
                                         @Param("updateTime") long updateTime);

    int deleteChunksByDocumentId(@Param("documentId") String documentId,
                                 @Param("orgId") String orgId);
}
