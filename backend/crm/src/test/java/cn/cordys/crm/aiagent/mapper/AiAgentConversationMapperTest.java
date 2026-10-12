package cn.cordys.crm.aiagent.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AiAgentConversationMapperTest {
    @Test
    void recentHistoryQueryBindsSessionOrganizationAndUserAndHasALimit() throws Exception {
        Configuration configuration = new Configuration();
        String resource = "cn/cordys/crm/aiagent/mapper/AiAgentInternalMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertThat(input).isNotNull();
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        var statement = configuration.getMappedStatement(AiAgentInternalMapper.class.getName() + ".listRecentConversationMessages");
        var sql = statement.getBoundSql(Map.of("sessionId", "s", "orgId", "o", "userId", "u", "limit", 21));
        assertThat(sql.getSql().replaceAll("\\s+", " "))
                .contains("m.session_id = ?", "s.organization_id = ?", "s.user_id = ?", "LIMIT ?",
                        "m.role IN ('user', 'assistant')", "ORDER BY m.create_time DESC, m.id DESC");
        assertThat(sql.getParameterMappings()).extracting("property").containsExactly("sessionId", "orgId", "userId", "limit");
    }
}
