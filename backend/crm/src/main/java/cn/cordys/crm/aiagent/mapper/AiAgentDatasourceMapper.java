package cn.cordys.crm.aiagent.mapper;

import cn.cordys.crm.aiagent.dto.datasource.AiAgentDatasource.Stored;
import org.apache.ibatis.annotations.*;
import java.util.List;

public interface AiAgentDatasourceMapper {
    @Select("SELECT * FROM ai_agent_datasource WHERE organization_id=#{org} AND owner_id=#{user} ORDER BY update_time DESC")
    List<Stored> list(@Param("org") String org, @Param("user") String user);

    @Select("SELECT * FROM ai_agent_datasource WHERE id=#{id} AND organization_id=#{org} AND owner_id=#{user}")
    Stored get(@Param("id") String id, @Param("org") String org, @Param("user") String user);

    @Insert("""
            INSERT INTO ai_agent_datasource (id,organization_id,owner_id,name,type,config_json,tables_json,selected_json,revision,update_time)
            VALUES (#{id},#{organizationId},#{ownerId},#{name},#{type},#{configJson},#{tablesJson},#{selectedJson},#{revision},#{updateTime})
            """)
    int insert(Stored source);

    @Update("""
            UPDATE ai_agent_datasource SET name=#{s.name},config_json=#{s.configJson},tables_json=#{s.tablesJson},
            selected_json=#{s.selectedJson},revision=#{s.revision},update_time=#{s.updateTime}
            WHERE id=#{s.id} AND organization_id=#{s.organizationId} AND owner_id=#{s.ownerId} AND revision=#{previous}
            """)
    int update(@Param("s") Stored source, @Param("previous") long previous);

    @Delete("DELETE FROM ai_agent_datasource WHERE id=#{id} AND organization_id=#{org} AND owner_id=#{user}")
    int delete(@Param("id") String id, @Param("org") String org, @Param("user") String user);
}
