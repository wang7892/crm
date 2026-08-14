package cn.cordys.crm.aiagent.tool;

import cn.cordys.common.dto.DeptDataPermissionDTO;
import cn.cordys.crm.aiagent.dto.AiAgentContext;
import cn.cordys.crm.aiagent.dto.internal.AiAgentCustomerRow;
import cn.cordys.crm.aiagent.dto.internal.ExternalOrderQueryResult;
import cn.cordys.crm.aiagent.dto.internal.ExternalOrderRow;
import cn.cordys.crm.aiagent.mapper.AiAgentInternalMapper;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

@Service
public class ExternalOrderTools {

    private static final List<String> CLOSED_STATUSES = List.of(
            "已结束", "结束", "已完成", "完成", "已取消", "取消",
            "closed", "done", "finished", "cancelled", "canceled"
    );

    @Autowired(required = false)
    @Qualifier("aiAgentExternalOrderJdbcTemplate")
    private NamedParameterJdbcTemplate jdbcTemplate;
    @Resource
    private CustomerTools customerTools;
    @Resource
    private AiAgentInternalMapper aiAgentInternalMapper;

    public ExternalOrderQueryResult findOrdersForSales(AiAgentContext context, String specialistName,
                                                       boolean activeOnly, int limit) {
        List<AiAgentCustomerRow> customers = customerTools.findCustomersBySpecialist(context, specialistName, 200);
        List<String> customerNames = customers.stream()
                .map(AiAgentCustomerRow::getName)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList();
        ExternalOrderQueryResult result = queryOrders(context, customerNames, specialistName, activeOnly, limit, null, null);
        result.setSearchedCustomers(customerNames);
        if (customerNames.isEmpty()) {
            result.getWarnings().add("当前权限范围内未找到该销售专员负责的客户。");
        }
        return result;
    }

    public ExternalOrderQueryResult findRecentOrdersForSales(AiAgentContext context, String specialistName, int limit) {
        List<AiAgentCustomerRow> customers = customerTools.findCustomersBySpecialist(context, specialistName, 200);
        List<String> customerNames = customers.stream()
                .map(AiAgentCustomerRow::getName)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList();
        ExternalOrderQueryResult result = queryOrders(context, customerNames, specialistName, false, limit,
                context.getTimeWindow().startTime(), context.getTimeWindow().endTime());
        result.setSearchedCustomers(customerNames);
        if (customerNames.isEmpty()) {
            result.getWarnings().add("当前权限范围内未找到该销售专员负责的客户。");
        }
        return result;
    }

    public ExternalOrderQueryResult findOrdersForCustomer(AiAgentContext context, String customerName,
                                                          boolean activeOnly, int limit) {
        List<AiAgentCustomerRow> customers = customerTools.searchCustomers(context, customerName, 10);
        List<String> customerNames = customers.stream()
                .map(AiAgentCustomerRow::getName)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList();
        ExternalOrderQueryResult result = queryOrders(context, customerNames, null, activeOnly, limit, null, null);
        result.setSearchedCustomers(customerNames);
        return result;
    }

    public ExternalOrderQueryResult findRecentOrdersForCustomer(AiAgentContext context, String customerName, int limit) {
        List<AiAgentCustomerRow> customers = customerTools.searchCustomers(context, customerName, 10);
        List<String> customerNames = customers.stream()
                .map(AiAgentCustomerRow::getName)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList();
        ExternalOrderQueryResult result = queryOrders(context, customerNames, null, false, limit,
                context.getTimeWindow().startTime(), context.getTimeWindow().endTime());
        result.setSearchedCustomers(customerNames);
        return result;
    }

    public ExternalOrderQueryResult findOrdersForVisibleCustomers(AiAgentContext context, boolean activeOnly, int limit) {
        List<String> customerNames = customerTools.searchCustomers(context, "", 500)
                .stream()
                .map(AiAgentCustomerRow::getName)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList();
        ExternalOrderQueryResult result = queryOrders(context, customerNames, null, activeOnly, limit, null, null);
        result.setSearchedCustomers(customerNames);
        if (customerNames.isEmpty()) {
            result.getWarnings().add("当前权限范围内没有可用于匹配外部订单的客户。");
        }
        return result;
    }

    public ExternalOrderQueryResult findRecentOrdersForVisibleCustomers(AiAgentContext context, int limit) {
        List<String> customerNames = customerTools.searchCustomers(context, "", 500)
                .stream()
                .map(AiAgentCustomerRow::getName)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList();
        ExternalOrderQueryResult result = queryOrders(context, customerNames, null, false, limit,
                context.getTimeWindow().startTime(), context.getTimeWindow().endTime());
        result.setSearchedCustomers(customerNames);
        if (customerNames.isEmpty()) {
            result.getWarnings().add("当前权限范围内没有可用于匹配外部订单的客户。");
        }
        return result;
    }

    public ExternalOrderQueryResult findOrdersByOrderNo(AiAgentContext context, String orderNo, int limit) {
        ExternalOrderQueryResult result = queryOrders(context, List.of(), null, false, limit,
                null, null, orderNo, null);
        result.setSearchedCustomers(List.of());
        return result;
    }

    public ExternalOrderQueryResult findOrdersByProduct(AiAgentContext context, String productName, int limit) {
        ExternalOrderQueryResult result = queryOrders(context, List.of(), null, false, limit,
                null, null, null, productName);
        result.setSearchedCustomers(List.of());
        return result;
    }

    private ExternalOrderQueryResult queryOrders(AiAgentContext context, List<String> customerNames, String managerName,
                                                 boolean activeOnly, int limit, Long startTime, Long endTime) {
        return queryOrders(context, customerNames, managerName, activeOnly, limit, startTime, endTime, null, null);
    }

    private ExternalOrderQueryResult queryOrders(AiAgentContext context, List<String> customerNames, String managerName,
                                                 boolean activeOnly, int limit, Long startTime, Long endTime,
                                                 String orderNoKeyword, String productKeyword) {
        ExternalOrderQueryResult result = new ExternalOrderQueryResult();
        result.setConfigured(jdbcTemplate != null);
        result.setDateFilterAvailable(startTime != null && endTime != null);
        if (jdbcTemplate == null) {
            result.getWarnings().add("请配置 crm.ai-agent.external-order.* 后再查询外部订单表。");
            return result;
        }
        if (context == null) {
            result.getWarnings().add("缺少智能体查询上下文，不能查询外部订单数据源。");
            return result;
        }
        if ((customerNames == null || customerNames.isEmpty()) && StringUtils.isBlank(managerName)
                && StringUtils.isBlank(orderNoKeyword) && StringUtils.isBlank(productKeyword)) {
            return result;
        }
        boolean restrictByManager = shouldRestrictByContractPermission(context);
        List<String> allowedManagerNames = restrictByManager ? resolveAllowedManagerNames(context) : List.of();
        if (restrictByManager && allowedManagerNames.isEmpty()) {
            result.getWarnings().add("No available contract managers in current data permission.");
            return result;
        }

        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("limit", Math.max(1, Math.min(limit, 1000)));
        StringBuilder sql = new StringBuilder("""
                SELECT *
                FROM contract_info
                WHERE 1 = 1
                """);

        appendCustomerFilter(sql, params, customerNames);
        if (StringUtils.isNotBlank(managerName)) {
            sql.append(" AND manager LIKE :managerName");
            params.addValue("managerName", "%" + managerName.trim() + "%");
        }
        if (StringUtils.isNotBlank(orderNoKeyword)) {
            sql.append(" AND order_no LIKE :orderNoKeyword");
            params.addValue("orderNoKeyword", "%" + orderNoKeyword.trim() + "%");
        }
        if (StringUtils.isNotBlank(productKeyword)) {
            sql.append(" AND product_name LIKE :productKeyword");
            params.addValue("productKeyword", "%" + productKeyword.trim() + "%");
        }
        appendAllowedManagerFilter(sql, params, allowedManagerNames);
        if (activeOnly) {
            sql.append(" AND (order_status IS NULL OR order_status NOT IN (:closedStatuses))");
            params.addValue("closedStatuses", CLOSED_STATUSES);
        }
        if (result.isDateFilterAvailable()) {
            sql.append(" AND create_time >= :startTime AND create_time < :endTime");
            params.addValue("startTime", new Timestamp(startTime));
            params.addValue("endTime", new Timestamp(endTime));
        }
        sql.append(" ORDER BY id DESC LIMIT :limit");

        result.setRows(jdbcTemplate.query(sql.toString(), params, this::mapOrderRow));
        return result;
    }

    private boolean shouldRestrictByContractPermission(AiAgentContext context) {
        DeptDataPermissionDTO dataPermission = context == null ? null : context.getContractDataPermission();
        return dataPermission == null || !Boolean.TRUE.equals(dataPermission.getAll());
    }

    private List<String> resolveAllowedManagerNames(AiAgentContext context) {
        DeptDataPermissionDTO dataPermission = context == null ? null : context.getContractDataPermission();
        if (dataPermission == null) {
            return List.of();
        }
        return aiAgentInternalMapper.findUserNamesByDataPermission(
                        context.getOrganizationId(), context.getUserId(), dataPermission)
                .stream()
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList();
    }

    private void appendAllowedManagerFilter(StringBuilder sql, MapSqlParameterSource params, List<String> managerNames) {
        if (managerNames == null || managerNames.isEmpty()) {
            return;
        }
        List<String> clauses = new ArrayList<>();
        int index = 0;
        for (String managerName : managerNames) {
            if (StringUtils.isBlank(managerName)) {
                continue;
            }
            String paramName = "allowedManagerName" + index++;
            clauses.add("TRIM(manager) = :" + paramName);
            params.addValue(paramName, managerName.trim());
        }
        if (!clauses.isEmpty()) {
            sql.append(" AND (").append(String.join(" OR ", clauses)).append(")");
        }
    }

    private void appendCustomerFilter(StringBuilder sql, MapSqlParameterSource params, List<String> customerNames) {
        if (customerNames == null || customerNames.isEmpty()) {
            return;
        }
        List<String> clauses = new ArrayList<>();
        int index = 0;
        for (String customerName : customerNames) {
            if (StringUtils.isBlank(customerName)) {
                continue;
            }
            String paramName = "customerName" + index++;
            clauses.add("customer LIKE :" + paramName);
            params.addValue(paramName, "%" + customerName.trim() + "%");
        }
        if (!clauses.isEmpty()) {
            sql.append(" AND (").append(String.join(" OR ", clauses)).append(")");
        }
    }

    private ExternalOrderRow mapOrderRow(ResultSet rs, int rowNum) throws SQLException {
        ExternalOrderRow row = new ExternalOrderRow();
        ResultSetMetaData metaData = rs.getMetaData();
        for (int index = 1; index <= metaData.getColumnCount(); index++) {
            String column = metaData.getColumnLabel(index);
            Object value = rs.getObject(index);
            row.getFields().put(column, value == null ? "" : String.valueOf(value));
        }
        Object id = rs.getObject("id");
        row.setId(id == null ? null : String.valueOf(id));
        row.setOrderNo(rs.getString("order_no"));
        row.setProductName(rs.getString("product_name"));
        row.setManager(rs.getString("manager"));
        row.setCustomer(rs.getString("customer"));
        row.setOrderStatus(rs.getString("order_status"));
        return row;
    }
}
