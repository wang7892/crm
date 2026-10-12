package cn.cordys.crm.aiagent.tool;

import cn.cordys.common.dto.DeptDataPermissionDTO;
import cn.cordys.crm.aiagent.dto.AiAgentContext;
import cn.cordys.crm.aiagent.dto.AiAgentTimeWindow;
import cn.cordys.crm.aiagent.mapper.AiAgentInternalMapper;
import cn.cordys.crm.aiagent.service.AiAgentResultWindow;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExternalOrderPaginationTest {
    @Test
    void customerMatchingHasNoDisplayLimitAndContinuationRechecksPermissions() {
        var jdbc = new NamedParameterJdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.getJdbcTemplate().execute("CREATE TABLE contract_info(id INT PRIMARY KEY, order_no VARCHAR(50), product_name VARCHAR(50), manager VARCHAR(50), customer VARCHAR(50), order_status VARCHAR(50))");
        for (int id = 1; id <= 468; id++) {
            jdbc.getJdbcTemplate().update("INSERT INTO contract_info VALUES (?, ?, '产品', ?, '客户201', '进行中')",
                    id, "ORDER_" + id, id <= 467 ? "赵芳" : "赵芳芳");
        }
        var mapper = mock(AiAgentInternalMapper.class);
        List<String> customerNames = IntStream.rangeClosed(1, 201).mapToObj(i -> "客户" + i).toList();
        when(mapper.findCustomerNamesForOrderQuery(eq("org"), eq("user"), eq("赵芳"), isNull(), any()))
                .thenReturn(customerNames);
        var tools = new ExternalOrderTools();
        ReflectionTestUtils.setField(tools, "jdbcTemplate", jdbc);
        ReflectionTestUtils.setField(tools, "aiAgentInternalMapper", mapper);
        var context = new AiAgentContext(); context.setUserId("user"); context.setOrganizationId("org");
        var permission = new DeptDataPermissionDTO(); permission.setAll(true);
        context.setDataPermission(permission); context.setContractDataPermission(permission);
        context.setTimeWindow(new AiAgentTimeWindow(0, Long.MAX_VALUE, "全部"));
        var first = tools.findOrdersForSales(context, "赵芳", false, AiAgentResultWindow.requestedSize("赵芳负责的订单前200条"));
        assertThat(first.getTotalRows()).isEqualTo(467);
        assertThat(first.getSearchedCustomers()).hasSize(201);
        assertThat(first.getRows()).hasSize(200);
        var second = tools.continueQuery(context, first.getQuery(), 200, 200);
        var last = tools.continueQuery(context, first.getQuery(), 200, 400);
        Set<String> ids = new HashSet<>();
        List.of(first, second, last).forEach(page -> page.getRows().forEach(row -> assertThat(ids.add(row.getId())).isTrue()));
        assertThat(second.getRows()).hasSize(200);
        assertThat(ids).hasSize(467);
        assertThat(last.getRows()).hasSize(67);
        permission.setAll(false);
        when(mapper.findUserNamesByDataPermission(anyString(), anyString(), any())).thenReturn(List.of("其他负责人"));
        assertThat(tools.continueQuery(context, first.getQuery(), 200, 200).getRows()).isEmpty();
    }
}
