package cn.cordys.crm.aiagent.mapper;

import cn.cordys.crm.aiagent.dto.knowledge.AiAgentQueryKnowledge.Stored;
import org.apache.ibatis.annotations.*;
import java.util.List;

public interface AiAgentQueryKnowledgeMapper {
    @Select("SELECT * FROM ai_agent_query_knowledge WHERE organization_id=#{org} ORDER BY update_time DESC,id LIMIT 2001")
    List<Stored> list(String org);

    @Select("""
            SELECT * FROM ai_agent_query_knowledge
            WHERE enabled=true AND id>#{afterId}
              AND (embedding_json IS NULL OR embedding_key IS NULL
                   OR embedding_key<>CASE WHEN kind='TERM' THEN #{termKey} ELSE #{key} END)
            ORDER BY id LIMIT #{limit}
            """)
    List<Stored> pendingIndexes(@Param("key") String key, @Param("termKey") String termKey,
                               @Param("afterId") String afterId, @Param("limit") int limit);

    @Select("SELECT * FROM ai_agent_query_knowledge WHERE organization_id=#{org} AND id=#{id}")
    Stored get(@Param("org") String org, @Param("id") String id);

    @Insert("INSERT IGNORE INTO ai_agent_query_knowledge_lock (organization_id) VALUES (#{org})")
    void ensureLock(String org);

    @Select("SELECT organization_id FROM ai_agent_query_knowledge_lock WHERE organization_id=#{org} FOR UPDATE")
    String lock(String org);

    @Insert("""
            INSERT INTO ai_agent_query_knowledge
            (id,organization_id,kind,name,content,synonyms_json,all_datasources,datasource_ids_json,enabled,revision,
             embedding_json,embedding_key,create_user,create_time,update_time)
            VALUES (#{id},#{organizationId},#{kind},#{name},#{content},#{synonymsJson},#{allDatasources},
                    #{datasourceIdsJson},#{enabled},#{revision},#{embeddingJson},#{embeddingKey},#{createUser},#{createTime},#{updateTime})
            """)
    int insert(Stored value);

    @Update("""
            UPDATE ai_agent_query_knowledge SET name=#{s.name},content=#{s.content},synonyms_json=#{s.synonymsJson},
            all_datasources=#{s.allDatasources},datasource_ids_json=#{s.datasourceIdsJson},enabled=#{s.enabled},
            revision=#{s.revision},embedding_json=#{s.embeddingJson},embedding_key=#{s.embeddingKey},update_time=#{s.updateTime}
            WHERE id=#{s.id} AND organization_id=#{s.organizationId} AND revision=#{previous}
            """)
    int update(@Param("s") Stored value, @Param("previous") long previous);

    @Update("UPDATE ai_agent_query_knowledge SET embedding_json=#{json},embedding_key=#{key} WHERE organization_id=#{org} AND id=#{id} AND revision=#{revision}")
    int updateEmbedding(@Param("org") String org, @Param("id") String id, @Param("revision") long revision,
                        @Param("json") String json, @Param("key") String key);

    @Delete("DELETE FROM ai_agent_query_knowledge WHERE organization_id=#{org} AND id=#{id} AND revision=#{revision}")
    int delete(@Param("org") String org, @Param("id") String id, @Param("revision") long revision);
}
