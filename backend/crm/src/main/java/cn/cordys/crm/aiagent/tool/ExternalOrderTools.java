package cn.cordys.crm.aiagent.tool;

import cn.cordys.common.dto.DeptDataPermissionDTO;
import cn.cordys.crm.aiagent.dto.AiAgentContext;
import cn.cordys.crm.aiagent.dto.internal.AiAgentExternalOrderQuery;
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
import org.springframework.dao.DataAccessException;

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
    private AiAgentInternalMapper aiAgentInternalMapper;

    public ExternalOrderQueryResult findOrdersForSales(AiAgentContext context, String specialistName,
                                                       boolean activeOnly, int limit) {
        return continueQuery(context, spec(specialistName, null, true, activeOnly, false, null, null), limit, 0);
    }

    public ExternalOrderQueryResult findRecentOrdersForSales(AiAgentContext context, String specialistName, int limit) {
        return continueQuery(context, spec(specialistName, null, true, false, true, null, null), limit, 0);
    }

    public ExternalOrderQueryResult findOrdersForCustomer(AiAgentContext context, String customerName,
                                                          boolean activeOnly, int limit) {
        return continueQuery(context, spec(null, customerName, true, activeOnly, false, null, null), limit, 0);
    }

    public ExternalOrderQueryResult findRecentOrdersForCustomer(AiAgentContext context, String customerName, int limit) {
        return continueQuery(context, spec(null, customerName, true, false, true, null, null), limit, 0);
    }

    public ExternalOrderQueryResult findOrdersForVisibleCustomers(AiAgentContext context, boolean activeOnly, int limit) {
        return continueQuery(context, spec(null, null, true, activeOnly, false, null, null), limit, 0);
    }

    public ExternalOrderQueryResult findRecentOrdersForVisibleCustomers(AiAgentContext context, int limit) {
        return continueQuery(context, spec(null, null, true, false, true, null, null), limit, 0);
    }

    public ExternalOrderQueryResult findOrdersByOrderNo(AiAgentContext context, String orderNo, int limit) {
        return continueQuery(context, spec(null, null, false, false, false, orderNo, null), limit, 0);
    }

    public ExternalOrderQueryResult findOrdersByProduct(AiAgentContext context, String productName, int limit) {
        return continueQuery(context, spec(null, null, false, false, false, null, productName), limit, 0);
    }

    public ExternalOrderQueryResult continueQuery(AiAgentContext context, AiAgentExternalOrderQuery query,
                                                  int limit, long offset) {
        ExternalOrderQueryResult result = new ExternalOrderQueryResult();
        result.setConfigured(jdbcTemplate != null);
        result.setLimit(cn.cordys.crm.aiagent.service.AiAgentResultWindow.normalizeSize(limit, context == null || context.isLimitRows()));
        result.setOffset(Math.max(0, offset));
        result.setQuery(query);
        if (jdbcTemplate == null) {
            result.getWarnings().add("请配置 crm.ai-agent.external-order.* 后再查询外部订单表。");
            return result;
        }
        if (context == null) {
            result.getWarnings().add("缺少智能体查询上下文，不能查询外部订单数据源。");
            return result;
        }
        boolean restrictByManager = shouldRestrictByContractPermission(context);
        List<String> allowedManagerNames = restrictByManager ? resolveAllowedManagerNames(context) : List.of();
        if (restrictByManager && allowedManagerNames.isEmpty()) {
            result.getWarnings().add("No available contract managers in current data permission.");
            return result;
        }
        List<String> customerNames = query.isVisibleCustomers()
                ? aiAgentInternalMapper.findCustomerNamesForOrderQuery(context.getOrganizationId(), context.getUserId(),
                query.getSpecialistName(), query.getCustomerKeyword(), context.getDataPermission()) : List.of();
        result.setSearchedCustomers(customerNames);
        if (query.isVisibleCustomers() && customerNames.isEmpty()) {
            result.setTotalRows(0L);
            return result;
        }
        result.setDateFilterAvailable(query.isRecent() && context.getTimeWindow() != null
                && !"全部".equals(context.getTimeWindow().label()));
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("limit", result.getLimit());
        params.addValue("offset", result.getOffset());
        StringBuilder sql = new StringBuilder("SELECT * FROM contract_info WHERE 1 = 1");
        appendCustomerFilter(sql, params, customerNames);
        if (StringUtils.isNotBlank(query.getSpecialistName())) {
            sql.append(" AND TRIM(manager) = :managerName");
            params.addValue("managerName", query.getSpecialistName().trim());
        }
        if (StringUtils.isNotBlank(query.getOrderNo())) {
            sql.append(" AND order_no LIKE :orderNoKeyword");
            params.addValue("orderNoKeyword", "%" + query.getOrderNo().trim() + "%");
        }
        if (StringUtils.isNotBlank(query.getProductName())) {
            sql.append(" AND product_name LIKE :productKeyword");
            params.addValue("productKeyword", "%" + query.getProductName().trim() + "%");
        }
        appendAllowedManagerFilter(sql, params, allowedManagerNames);
        if (query.isActiveOnly()) {
            sql.append(" AND (order_status IS NULL OR order_status NOT IN (:closedStatuses))");
            params.addValue("closedStatuses", CLOSED_STATUSES);
        }
        if (result.isDateFilterAvailable()) {
            sql.append(" AND create_time >= :startTime AND create_time < :endTime");
            params.addValue("startTime", new Timestamp(context.getTimeWindow().startTime()));
            params.addValue("endTime", new Timestamp(context.getTimeWindow().endTime()));
        }
        String countSql = "SELECT COUNT(*) FROM (" + sql + ") agent_order_count";
        sql.append(" ORDER BY id DESC");
        if (result.getLimit() > 0) sql.append(" LIMIT :limit OFFSET :offset");
        result.setRows(jdbcTemplate.query(sql.toString(), params, this::mapOrderRow));
        try {
            Long total = jdbcTemplate.queryForObject(countSql, params, Long.class);
            if (total != null && total >= 0 && (result.getRows().isEmpty() || total >= offset + result.getRows().size())) {
                result.setTotalRows(total);
            } else {
                result.setTotalWarning("总数尚未核实，不能将本次返回条数视为总数。");
            }
        } catch (DataAccessException e) {
            result.setTotalWarning("总数统计失败，当前仅展示已取得的记录，无法确认剩余数量。");
        }
        return result;
    }

    private AiAgentExternalOrderQuery spec(String specialist, String customer, boolean visible, boolean active,
                                           boolean recent, String orderNo, String product) {
        var query = new AiAgentExternalOrderQuery();
        query.setSpecialistName(specialist);
        query.setCustomerKeyword(customer);
        query.setVisibleCustomers(visible);
        query.setActiveOnly(active);
        query.setRecent(recent);
        query.setOrderNo(orderNo);
        query.setProductName(product);
        return query;
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
