package cn.cordys.crm.aiagent.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class AiAgentKnowledgeMapperXmlTest {

    private static final String RESOURCE = "cn/cordys/crm/aiagent/mapper/AiAgentKnowledgeMapper.xml";
    private static final String NAMESPACE = AiAgentKnowledgeMapper.class.getName();

    @Test
    void shouldParseAsyncJobStatementsAndClearNullableState() throws Exception {
        Configuration configuration = new Configuration();
        try (InputStream input = Objects.requireNonNull(
                getClass().getClassLoader().getResourceAsStream(RESOURCE), RESOURCE)) {
            new XMLMapperBuilder(input, configuration, RESOURCE, configuration.getSqlFragments()).parse();
        }

        assertThat(configuration.hasStatement(NAMESPACE + ".claimParseJob")).isTrue();
        assertThat(configuration.hasStatement(NAMESPACE + ".getLatestParseJobForUpdate")).isTrue();
        assertThat(configuration.hasStatement(NAMESPACE + ".listStaleRunningParseJobIds")).isTrue();

        Map<String, Object> params = new HashMap<>();
        params.put("documentId", "doc-1");
        params.put("orgId", "org-1");
        params.put("parseStatus", "UPLOADED");
        params.put("parseError", null);
        params.put("chunkCount", null);
        params.put("updateUser", "user-1");
        params.put("updateTime", 1L);
        BoundSql withoutCount = configuration
                .getMappedStatement(NAMESPACE + ".updateKnowledgeDocumentParseState")
                .getBoundSql(params);
        assertThat(normalize(withoutCount.getSql()))
                .contains("parse_error = ?")
                .doesNotContain("chunk_count = ?");

        params.put("chunkCount", 2);
        BoundSql withCount = configuration
                .getMappedStatement(NAMESPACE + ".updateKnowledgeDocumentParseState")
                .getBoundSql(params);
        assertThat(normalize(withCount.getSql())).contains("chunk_count = ?");
    }

    private String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }
}
