package cn.cordys.crm.aiagent.mapper;

import cn.cordys.crm.aiagent.dto.datasource.AiAgentDatasource.Stored;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;

class AiAgentDatasourceMapperTest {
    @Test void migrationAndMapperEnforceOwnershipAndOptimisticVersioning() throws Exception {
        JdbcDataSource ds = new JdbcDataSource(); ds.setURL("jdbc:h2:mem:agent_sources;MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (var input = getClass().getClassLoader().getResourceAsStream("migration/1.6.0/ddl/V1.6.0_53__ai_agent_datasource.sql");
             var db = ds.getConnection(); var statement = db.createStatement()) {
            assertThat(input).isNotNull(); statement.execute(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        Configuration config = new Configuration(new Environment("test", new JdbcTransactionFactory(), ds));
        config.setMapUnderscoreToCamelCase(true); config.addMapper(AiAgentDatasourceMapper.class);
        try (var session = new SqlSessionFactoryBuilder().build(config).openSession(true)) {
            var mapper = session.getMapper(AiAgentDatasourceMapper.class);
            Stored s = new Stored(); s.setId("one"); s.setName("DB"); s.setType("MYSQL"); s.setOrganizationId("org"); s.setOwnerId("user");
            s.setConfigJson("{}"); s.setTablesJson("[]"); s.setSelectedJson("[]"); s.setRevision(1); s.setUpdateTime(123);
            assertThat(mapper.insert(s)).isEqualTo(1);
            assertThat(mapper.get("one", "org", "user").getUpdateTime()).isEqualTo(123);
            assertThat(mapper.get("one", "other", "user")).isNull();
            assertThat(mapper.get("one", "org", "other")).isNull();
            assertThat(mapper.list("other", "user")).isEmpty();
            assertThat(mapper.list("org", "other")).isEmpty();
            s.setRevision(2); assertThat(mapper.update(s, 1)).isEqualTo(1);
            assertThat(mapper.update(s, 1)).isZero();
            assertThat(mapper.delete("one", "org", "other")).isZero();
            assertThat(mapper.delete("one", "org", "user")).isEqualTo(1);
        }
    }
}
