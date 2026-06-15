package cn.cordys.crm.order.service;

import cn.cordys.crm.contract.domain.Contract;
import cn.cordys.crm.order.domain.Order;
import cn.cordys.crm.order.dto.request.ExternalOrderSyncRequest;
import cn.cordys.crm.order.dto.response.ExternalOrderSyncResult;
import cn.cordys.crm.order.mapper.ExtOrderMapper;
import cn.cordys.mybatis.BaseMapper;
import cn.cordys.mybatis.lambda.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional(rollbackFor = Exception.class)
public class ExternalOrderInfoSyncService {

    private static final int DEFAULT_LIMIT = 5000;
    private static final int MAX_LIMIT = 20000;
    private static final int MAX_WARNING_COUNT = 30;

    @Autowired(required = false)
    @Qualifier("aiAgentExternalOrderJdbcTemplate")
    private NamedParameterJdbcTemplate externalOrderJdbcTemplate;
    @Resource
    private BaseMapper<Order> orderMapper;
    @Resource
    private ExtOrderMapper extOrderMapper;
    @Resource
    private BaseMapper<Contract> contractMapper;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ExternalOrderSyncResult sync(ExternalOrderSyncRequest request, String operatorId, String orgId) {
        ExternalOrderSyncResult result = new ExternalOrderSyncResult();
        result.setConfigured(externalOrderJdbcTemplate != null);
        if (externalOrderJdbcTemplate == null) {
            result.getWarnings().add("External order data source is not configured: crm.ai-agent.external-order.*");
            return result;
        }

        int pageSize = normalizeLimit(request == null ? null : request.getLimit());
        Long minId = request == null ? null : request.getMinId();
        List<Map<String, Object>> rows = queryExternalRows(pageSize + 1, minId);
        result.setHasMore(rows.size() > pageSize);
        List<Map<String, Object>> syncRows = result.isHasMore() ? rows.subList(0, pageSize) : rows;
        result.setTotal(syncRows.size());

        Long maxId = minId;
        Map<String, Contract> contractCache = new HashMap<>();
        for (Map<String, Object> row : syncRows) {
            try {
                syncOne(row, operatorId, orgId, result, contractCache);
            } catch (Exception e) {
                result.increaseSkipped();
                addWarning(result, "External order_info.id=" + asString(row, "id") + " sync failed: " + e.getMessage());
            }
            Long rowId = toLongId(row.get("id"));
            if (rowId != null && (maxId == null || rowId > maxId)) {
                maxId = rowId;
            }
        }

        result.setNextMinId(maxId);
        if (result.isHasMore() && (maxId == null || (minId != null && maxId <= minId))) {
            result.setHasMore(false);
            addWarning(result, "External order_info.id is not numeric or did not advance, stopped after current batch.");
        }
        return result;
    }

    private void syncOne(Map<String, Object> row, String operatorId, String orgId, ExternalOrderSyncResult result,
                         Map<String, Contract> contractCache) {
        String externalId = asString(row, "id");
        String orderNo = asString(row, "order_no");
        if (StringUtils.isAnyBlank(externalId, orderNo)) {
            result.increaseSkipped();
            addWarning(result, "External order_info has empty id or order_no, skipped.");
            return;
        }

        Order incoming = new Order();
        fillOrder(row, incoming, orgId, contractCache);

        Order order = findExistingOrder(externalId, orgId);
        boolean exists = order != null;
        if (!exists) {
            order = incoming;
            order.setId(buildExternalOrderId(externalId, orgId));
            order.setOrganizationId(orgId);
            order.setCreateTime(System.currentTimeMillis());
            order.setCreateUser(operatorId);
        }

        fillOrder(row, order, orgId, contractCache);
        if (exists) {
            order.setUpdateTime(System.currentTimeMillis());
            order.setUpdateUser(operatorId);
            orderMapper.update(order);
            extOrderMapper.updateContractRelation(order);
            result.increaseUpdated();
        } else {
            order.setUpdateTime(order.getCreateTime());
            order.setUpdateUser(operatorId);
            orderMapper.insert(order);
            result.increaseCreated();
        }
    }

    private void fillOrder(Map<String, Object> row, Order order, String orgId, Map<String, Contract> contractCache) {
        String orderNo = asString(row, "order_no");
        String merchandiser = asString(row, "merchandiser");

        order.setOrderNo(orderNo);
        order.setProcessOrderNo(asString(row, "process_order_no"));
        order.setProcessor(asString(row, "processor"));
        order.setMerchandiser(merchandiser);
        order.setStatus(asString(row, "status"));
        order.setColor(asString(row, "color"));
        order.setColorCode(asString(row, "color_code"));
        order.setComposition(asString(row, "composition"));
        order.setMaterialName(asString(row, "material_name"));
        order.setMaterialType(asString(row, "material_type"));
        order.setProcessTechnology(asString(row, "process_technology"));
        order.setOrderTime(toEpochMillis(row.get("order_time")));
        order.setQuantity(toBigDecimal(row.get("quantity")));
        order.setUnit(asString(row, "unit"));
        order.setUnitPrice(toBigDecimal(row.get("unit_price")));
        order.setAmount(toBigDecimal(row.get("amount")));
        order.setCurrency(asString(row, "currency"));
        fillContractInfo(order, orderNo, orgId, contractCache);
    }

    private void fillContractInfo(Order order, String orderNo, String orgId, Map<String, Contract> contractCache) {
        if (StringUtils.isAnyBlank(orderNo, orgId)) {
            clearContractInfo(order);
            return;
        }
        Contract contract = findContract(orderNo, orgId, contractCache);
        if (contract == null) {
            clearContractInfo(order);
            return;
        }
        order.setContractId(contract.getId());
        order.setCustomerId(contract.getCustomerId());
        order.setOwner(StringUtils.trimToNull(contract.getOwner()));
    }

    private Contract findContract(String orderNo, String orgId, Map<String, Contract> contractCache) {
        String cacheKey = orgId + ":" + orderNo;
        if (contractCache.containsKey(cacheKey)) {
            return contractCache.get(cacheKey);
        }
        LambdaQueryWrapper<Contract> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Contract::getNumber, orderNo);
        wrapper.eq(Contract::getOrganizationId, orgId);
        Contract contract = contractMapper.selectListByLambda(wrapper).stream().findFirst().orElse(null);
        contractCache.put(cacheKey, contract);
        return contract;
    }

    private void clearContractInfo(Order order) {
        order.setContractId(null);
        order.setCustomerId(null);
        order.setOwner(null);
    }

    private List<Map<String, Object>> queryExternalRows(int limit, Long minId) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("limit", limit);
        StringBuilder sql = new StringBuilder("""
                SELECT id, order_no, process_order_no, processor, merchandiser, status,
                       color, color_code, composition, material_name, material_type,
                       process_technology, order_time, quantity, unit, unit_price,
                       amount, currency
                FROM order_info
                WHERE 1 = 1
                """);
        if (minId != null) {
            sql.append(" AND id > :minId");
            params.addValue("minId", minId);
        }
        sql.append(" ORDER BY id ASC LIMIT :limit");
        return externalOrderJdbcTemplate.queryForList(sql.toString(), params);
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private String buildExternalOrderId(String externalId, String orgId) {
        if (externalId.length() <= 32) {
            return externalId;
        }
        return UUID.nameUUIDFromBytes(("order_info:" + orgId + ":" + externalId).getBytes(StandardCharsets.UTF_8))
                .toString()
                .replace("-", "");
    }

    private Order findExistingOrder(String externalId, String orgId) {
        return orderMapper.selectByPrimaryKey(buildExternalOrderId(externalId, orgId));
    }

    private String asString(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : StringUtils.trimToNull(value.toString());
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null || StringUtils.isBlank(value.toString())) {
            return null;
        }
        if (value instanceof BigDecimal bigDecimal) {
            return bigDecimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return new BigDecimal(value.toString().trim());
    }

    private Long toEpochMillis(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.getTime();
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        if (value instanceof LocalDate localDate) {
            return localDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        if (value instanceof Date date) {
            return date.getTime();
        }
        if (value instanceof Number number) {
            return normalizeEpochNumber(number.longValue());
        }
        String text = value.toString().trim();
        if (StringUtils.isBlank(text)) {
            return null;
        }
        if (StringUtils.isNumeric(text)) {
            return normalizeEpochNumber(Long.parseLong(text));
        }
        try {
            return Timestamp.valueOf(text).getTime();
        } catch (IllegalArgumentException ignored) {
            // try formatted date strings below
        }
        for (DateTimeFormatter formatter : List.of(
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy-M-d H:m:s"),
                DateTimeFormatter.ofPattern("yyyy/M/d HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy/M/d H:m:s"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
                DateTimeFormatter.ofPattern("yyyy-M-d H:m"),
                DateTimeFormatter.ofPattern("yyyy/M/d HH:mm")
        )) {
            try {
                return LocalDateTime.parse(text, formatter).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            } catch (DateTimeParseException ignored) {
                // try next format
            }
        }
        for (DateTimeFormatter formatter : List.of(
                DateTimeFormatter.ofPattern("yyyy-MM-dd"),
                DateTimeFormatter.ofPattern("yyyy/M/d"),
                DateTimeFormatter.ofPattern("yyyy.M.d")
        )) {
            try {
                return LocalDate.parse(text, formatter).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
            } catch (DateTimeParseException ignored) {
                // try next format
            }
        }
        return null;
    }

    private Long toLongId(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = value.toString().trim();
        if (!StringUtils.isNumeric(text)) {
            return null;
        }
        return Long.valueOf(text);
    }

    private long normalizeEpochNumber(long value) {
        return Math.abs(value) < 100_000_000_000L ? value * 1000 : value;
    }

    private void addWarning(ExternalOrderSyncResult result, String warning) {
        if (result.getWarnings().size() < MAX_WARNING_COUNT) {
            result.getWarnings().add(warning);
        }
    }
}
