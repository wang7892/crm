package cn.cordys.crm.aiagent.mapper;

import cn.cordys.crm.aiagent.domain.AiKnowledgeChunk;
import cn.cordys.crm.aiagent.domain.AiKnowledgeDocument;
import cn.cordys.crm.aiagent.dto.request.AiKnowledgeDocumentPageRequest;
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

    int deleteChunksByDocumentId(@Param("documentId") String documentId,
                                 @Param("orgId") String orgId);
}
