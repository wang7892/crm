package cn.cordys.crm.task.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShipmentTaskGenerationServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void shouldGenerateOneTaskPerOrderNumberAndShipmentDay() throws Exception {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        TaskService taskService = mock(TaskService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-08-12T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
        long shipDate = Instant.parse("2026-08-11T01:00:00Z").toEpochMilli();
        ResultSet row = shipmentRow("SO-001", "user-1", "customer-1", shipDate, 1);
        when(jdbcTemplate.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    RowMapper<Object> rowMapper = invocation.getArgument(2);
                    return List.of(rowMapper.mapRow(row, 0));
                });
        when(taskService.createShipmentTask(any(TaskService.ShipmentTaskCommand.class))).thenReturn(true);
        ShipmentTaskGenerationService service = new ShipmentTaskGenerationService(jdbcTemplate, taskService, clock);

        ShipmentTaskGenerationService.GenerationResult result = service.generateYesterdayTasks("org-1");

        ArgumentCaptor<MapSqlParameterSource> parameters = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbcTemplate).query(anyString(), parameters.capture(), any(RowMapper.class));
        assertThat(parameters.getValue().getValue("startTime")).isEqualTo(
                Instant.parse("2026-08-10T16:00:00Z").toEpochMilli());
        assertThat(parameters.getValue().getValue("endTime")).isEqualTo(
                Instant.parse("2026-08-11T16:00:00Z").toEpochMilli());
        assertThat(result).isEqualTo(new ShipmentTaskGenerationService.GenerationResult(1, 1, 0, 0));
        ArgumentCaptor<TaskService.ShipmentTaskCommand> command =
                ArgumentCaptor.forClass(TaskService.ShipmentTaskCommand.class);
        verify(taskService).createShipmentTask(command.capture());
        assertThat(command.getValue().orderNo()).isEqualTo("SO-001");
        assertThat(command.getValue().ownerId()).isEqualTo("user-1");
        assertThat(command.getValue().customerId()).isEqualTo("customer-1");
        assertThat(command.getValue().businessKey()).endsWith(":2026-08-11");
        assertThat(command.getValue().lookupIssue()).isNull();
    }

    @Test
    void shouldUseDifferentBusinessKeysForDifferentShipmentDays() {
        String first = ShipmentTaskGenerationService.shipmentBusinessKey("SO-001", LocalDate.of(2026, 8, 11));
        String sameDay = ShipmentTaskGenerationService.shipmentBusinessKey("SO-001", LocalDate.of(2026, 8, 11));
        String nextDay = ShipmentTaskGenerationService.shipmentBusinessKey("SO-001", LocalDate.of(2026, 8, 12));

        assertThat(first).isEqualTo(sameDay);
        assertThat(first).isNotEqualTo(nextDay);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldSendMissingAndDuplicateContractsToManagerAssignment() throws Exception {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        TaskService taskService = mock(TaskService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-08-12T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
        long shipDate = Instant.parse("2026-08-11T01:00:00Z").toEpochMilli();
        ResultSet missing = shipmentRow("SO-001", null, null, shipDate, 0);
        ResultSet duplicate = shipmentRow("SO-002", null, null, shipDate, 2);
        when(jdbcTemplate.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    RowMapper<Object> rowMapper = invocation.getArgument(2);
                    return List.of(rowMapper.mapRow(missing, 0), rowMapper.mapRow(duplicate, 1));
                });
        when(taskService.createShipmentTask(any(TaskService.ShipmentTaskCommand.class))).thenReturn(true);
        ShipmentTaskGenerationService service = new ShipmentTaskGenerationService(jdbcTemplate, taskService, clock);

        service.generateYesterdayTasks("org-1");

        ArgumentCaptor<TaskService.ShipmentTaskCommand> commands =
                ArgumentCaptor.forClass(TaskService.ShipmentTaskCommand.class);
        verify(taskService, times(2)).createShipmentTask(commands.capture());
        assertThat(commands.getAllValues()).extracting(TaskService.ShipmentTaskCommand::lookupIssue)
                .containsExactly(
                        "待分配原因：未找到合同编号与订单号对应的合同，无法确定客户和联系专员。",
                        "待分配原因：同一合同编号匹配到多个合同，无法确定客户和联系专员。");
    }

    private ResultSet shipmentRow(String orderNo, String ownerId, String customerId, long shipDate,
                                  int contractCount) throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString("order_no")).thenReturn(orderNo);
        when(resultSet.getString("owner")).thenReturn(ownerId);
        when(resultSet.getString("customer_id")).thenReturn(customerId);
        when(resultSet.getLong("warehouse_actual_ship_date")).thenReturn(shipDate);
        when(resultSet.getInt("contract_count")).thenReturn(contractCount);
        return resultSet;
    }
}
