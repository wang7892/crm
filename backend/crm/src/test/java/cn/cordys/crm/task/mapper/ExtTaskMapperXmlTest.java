package cn.cordys.crm.task.mapper;

import cn.cordys.crm.task.dto.request.TaskPageRequest;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class ExtTaskMapperXmlTest {

    private static final String RESOURCE = "cn/cordys/crm/task/mapper/ExtTaskMapper.xml";
    private static final String STATEMENT = ExtTaskMapper.class.getName() + ".selectTaskPage";

    private Configuration configuration;

    @BeforeEach
    void setUp() throws Exception {
        configuration = new Configuration();
        try (InputStream input = Objects.requireNonNull(
                getClass().getClassLoader().getResourceAsStream(RESOURCE), RESOURCE)) {
            new XMLMapperBuilder(input, configuration, RESOURCE, configuration.getSqlFragments()).parse();
        }
    }

    @Test
    void shouldFilterManagerTasksBySelectedAssignee() {
        TaskPageRequest request = new TaskPageRequest();
        request.setAssigneeId("user-2");

        BoundSql boundSql = boundSql(request, true);

        assertThat(normalize(boundSql.getSql())).contains("AND t.assignee_id = ?");
        assertThat(boundSql.getParameterMappings())
                .extracting(mapping -> mapping.getProperty())
                .contains("request.assigneeId")
                .doesNotContain("userId");
    }

    @Test
    void shouldFilterManagerTasksWithoutAssignee() {
        TaskPageRequest request = new TaskPageRequest();
        request.setAssigneeId("__UNASSIGNED__");

        BoundSql boundSql = boundSql(request, true);

        assertThat(normalize(boundSql.getSql())).contains("AND t.assignee_id IS NULL");
        assertThat(boundSql.getParameterMappings())
                .extracting(mapping -> mapping.getProperty())
                .doesNotContain("request.assigneeId", "userId");
    }

    @Test
    void shouldKeepOrdinaryUserRestrictedToCurrentUser() {
        TaskPageRequest request = new TaskPageRequest();
        request.setAssigneeId("user-2");

        BoundSql boundSql = boundSql(request, false);

        assertThat(normalize(boundSql.getSql())).contains("AND t.assignee_id = ?");
        assertThat(boundSql.getParameterMappings())
                .extracting(mapping -> mapping.getProperty())
                .contains("userId")
                .doesNotContain("request.assigneeId");
    }

    private BoundSql boundSql(TaskPageRequest request, boolean manager) {
        Map<String, Object> params = new HashMap<>();
        params.put("request", request);
        params.put("organizationId", "org-1");
        params.put("userId", "user-1");
        params.put("manager", manager);
        params.put("now", 1L);
        return configuration.getMappedStatement(STATEMENT).getBoundSql(params);
    }

    private String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }
}
