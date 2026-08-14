package cn.cordys.crm.task.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;

@Slf4j
@Service
public class ShipmentTaskGenerationService {

    static final ZoneId BUSINESS_ZONE_ID = ZoneId.of("Asia/Shanghai");
    private static final String SHIPPED_ORDER_SQL = """
            SELECT so.order_no,
                   MIN(so.warehouse_actual_ship_date) AS warehouse_actual_ship_date,
                   COUNT(DISTINCT c.id) AS contract_count,
                   CASE WHEN COUNT(DISTINCT c.id) = 1 THEN MAX(c.owner) END AS owner,
                   CASE WHEN COUNT(DISTINCT c.id) = 1 THEN MAX(c.customer_id) END AS customer_id
            FROM sales_order so
            LEFT JOIN contract c ON c.organization_id = so.organization_id
                                AND c.number = so.order_no
            WHERE so.organization_id = :organizationId
              AND so.warehouse_actual_ship_date >= :startTime
              AND so.warehouse_actual_ship_date < :endTime
              AND so.order_no IS NOT NULL
              AND TRIM(so.order_no) != ''
            GROUP BY so.order_no
            ORDER BY MIN(so.warehouse_actual_ship_date), so.order_no
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final TaskService taskService;
    private final Clock clock;

    @Autowired
    public ShipmentTaskGenerationService(@Qualifier("dataSource") DataSource dataSource,
                                         TaskService taskService) {
        this(new NamedParameterJdbcTemplate(dataSource), taskService, Clock.system(BUSINESS_ZONE_ID));
    }

    ShipmentTaskGenerationService(NamedParameterJdbcTemplate jdbcTemplate, TaskService taskService, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.taskService = taskService;
        this.clock = clock;
    }

    public GenerationResult generateYesterdayTasks(String organizationId) {
        LocalDate today = LocalDate.now(clock);
        long startTime = today.minusDays(1).atStartOfDay(BUSINESS_ZONE_ID).toInstant().toEpochMilli();
        long endTime = today.atStartOfDay(BUSINESS_ZONE_ID).toInstant().toEpochMilli();
        long createTime = Instant.now(clock).toEpochMilli();
        List<ShippedOrder> orders = findShippedOrders(organizationId, startTime, endTime);
        int created = 0;
        int skipped = 0;
        int failed = 0;
        for (ShippedOrder order : orders) {
            try {
                LocalDate shipDay = Instant.ofEpochMilli(order.shipDate())
                        .atZone(BUSINESS_ZONE_ID).toLocalDate();
                String businessKey = shipmentBusinessKey(order.orderNo(), shipDay);
                String lookupIssue = contractLookupIssue(order.contractCount());
                boolean inserted = taskService.createShipmentTask(new TaskService.ShipmentTaskCommand(
                        organizationId, businessKey, order.orderNo(), order.ownerId(), order.customerId(),
                        order.shipDate(), createTime, lookupIssue));
                if (inserted) {
                    created++;
                } else {
                    skipped++;
                }
            } catch (RuntimeException e) {
                failed++;
                log.error("Failed to create shipment task, organizationId={}, orderNo={}",
                        organizationId, order.orderNo(), e);
            }
        }
        return new GenerationResult(orders.size(), created, skipped, failed);
    }

    private List<ShippedOrder> findShippedOrders(String organizationId, long startTime, long endTime) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("organizationId", organizationId)
                .addValue("startTime", startTime)
                .addValue("endTime", endTime);
        return jdbcTemplate.query(SHIPPED_ORDER_SQL, parameters, (rs, rowNum) -> new ShippedOrder(
                rs.getString("order_no"), rs.getString("owner"), rs.getString("customer_id"),
                rs.getLong("warehouse_actual_ship_date"), rs.getInt("contract_count")));
    }

    static String shipmentBusinessKey(String orderNo, LocalDate shipDay) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(orderNo.trim().getBytes(StandardCharsets.UTF_8));
            return "SHIPMENT_ORDER:" + HexFormat.of().formatHex(digest) + ":" + shipDay;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private String contractLookupIssue(int contractCount) {
        if (contractCount == 0) {
            return "待分配原因：未找到合同编号与订单号对应的合同，无法确定客户和联系专员。";
        }
        if (contractCount > 1) {
            return "待分配原因：同一合同编号匹配到多个合同，无法确定客户和联系专员。";
        }
        return null;
    }

    private record ShippedOrder(String orderNo, String ownerId, String customerId, long shipDate,
                                int contractCount) {
    }

    public record GenerationResult(int scanned, int created, int skipped, int failed) {
    }
}
