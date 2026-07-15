package cn.cordys.crm.order.service;

import cn.cordys.aspectj.annotation.OperationLog;
import cn.cordys.aspectj.constants.LogModule;
import cn.cordys.aspectj.constants.LogType;
import cn.cordys.aspectj.context.OperationLogContext;
import cn.cordys.aspectj.dto.LogDTO;
import cn.cordys.common.constants.BusinessModuleField;
import cn.cordys.common.constants.FormKey;
import cn.cordys.common.constants.OrderPromotedField;
import cn.cordys.common.constants.PermissionConstants;
import cn.cordys.common.domain.BaseModuleFieldValue;
import cn.cordys.common.dto.*;
import cn.cordys.common.dto.condition.BaseCondition;
import cn.cordys.common.exception.GenericException;
import cn.cordys.common.pager.PageUtils;
import cn.cordys.common.pager.PagerWithOption;
import cn.cordys.common.permission.PermissionCache;
import cn.cordys.common.permission.PermissionUtils;
import cn.cordys.common.response.result.CrmHttpResultCode;
import cn.cordys.common.service.BaseService;
import cn.cordys.common.service.DataScopeService;
import cn.cordys.common.uid.IDGenerator;
import cn.cordys.common.uid.SerialNumGenerator;
import cn.cordys.common.util.BeanUtils;
import cn.cordys.common.util.JSON;
import cn.cordys.common.util.Translator;
import cn.cordys.crm.contract.domain.Contract;
import cn.cordys.crm.customer.domain.Customer;
import cn.cordys.crm.order.domain.Order;
import cn.cordys.crm.order.domain.OrderSnapshot;
import cn.cordys.crm.order.dto.request.OrderAddRequest;
import cn.cordys.crm.order.dto.request.OrderPageRequest;
import cn.cordys.crm.order.dto.request.OrderStageRequest;
import cn.cordys.crm.order.dto.request.OrderUpdateRequest;
import cn.cordys.crm.order.dto.response.OrderGetResponse;
import cn.cordys.crm.order.dto.response.OrderListResponse;
import cn.cordys.crm.order.dto.response.OrderStageConfigResponse;
import cn.cordys.crm.order.dto.response.OrderStatisticResponse;
import cn.cordys.crm.order.dto.response.OrderSummaryResponse;
import cn.cordys.crm.order.mapper.ExtOrderMapper;
import cn.cordys.crm.order.mapper.ExtOrderStageConfigMapper;
import cn.cordys.crm.system.dto.field.SerialNumberField;
import cn.cordys.crm.system.dto.field.base.BaseField;
import cn.cordys.crm.system.dto.response.ModuleFormConfigDTO;
import cn.cordys.crm.system.service.LogService;
import cn.cordys.crm.system.service.ModuleFormCacheService;
import cn.cordys.crm.system.service.ModuleFormService;
import cn.cordys.mybatis.BaseMapper;
import cn.cordys.mybatis.lambda.LambdaQueryWrapper;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import jakarta.annotation.Resource;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional(rollbackFor = Exception.class)
public class OrderService {

    @Resource
    private OrderFieldService orderFieldService;
    @Resource
    private BaseMapper<Order> orderMapper;
    @Resource
    private BaseService baseService;
    @Resource
    private ModuleFormService moduleFormService;
    @Resource
    private BaseMapper<OrderSnapshot> snapshotBaseMapper;
    @Resource
    private ExtOrderMapper extOrderMapper;
    @Resource
    private ModuleFormCacheService moduleFormCacheService;
    @Resource
    private PermissionCache permissionCache;
    @Resource
    private BaseMapper<Customer> customerMapper;
    @Resource
    private BaseMapper<Contract> contractMapper;
    @Resource
    private LogService logService;
    @Resource
    private SerialNumGenerator serialNumGenerator;
    @Resource
    private DataScopeService dataScopeService;
    @Resource
    private ExtOrderStageConfigMapper extOrderStageConfigMapper;
    @Resource
    private BaseMapper<Customer> customerBaseMapper;

    private static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999");
    public static final int MAX_NUMBER_LENGTH = 50;

    /**
     * 新建订单
     *
     * @param request
     * @param operatorId
     * @param orgId
     * @return
     */
    @OperationLog(module = LogModule.ORDER_INDEX, type = LogType.ADD, resourceName = "{#request.orderNo}")
    public Order add(OrderAddRequest request, String operatorId, String orgId) {
        List<BaseModuleFieldValue> moduleFields = request.getModuleFields();
        ModuleFormConfigDTO moduleFormConfigDTO = request.getModuleFormConfigDTO();
        if (moduleFields == null) {
            moduleFields = new ArrayList<>();
        }
        if (moduleFormConfigDTO == null) {
            throw new GenericException(Translator.get("order.form.config.required"));
        }
        ModuleFormConfigDTO saveModuleFormConfigDTO = JSON.parseObject(JSON.toJSONString(moduleFormConfigDTO), ModuleFormConfigDTO.class);
        Order order = new Order();
        BeanUtils.copyBean(order, request);
        moduleFields = extractPromotedModuleFields(order, moduleFields, moduleFormConfigDTO);
        order.setId(IDGenerator.nextStr());
        order.setOrderNo(createOrderNumber(moduleFormConfigDTO, orgId, order.getOrderNo()));
        if (StringUtils.isBlank(order.getOrderNo())) {
            throw new GenericException(Translator.get("order.number.required"));
        }
        if (order.getOrderNo().length() > MAX_NUMBER_LENGTH) {
            throw new GenericException(Translator.get("order.number.length.exceed"));
        }
        order.setOrganizationId(orgId);
        order.setCreateTime(System.currentTimeMillis());
        order.setCreateUser(operatorId);
        order.setUpdateTime(System.currentTimeMillis());
        order.setUpdateUser(operatorId);
        setAmount(request.getAmount(), order);

        fillOwnerFromMerchandiser(order, orgId);
        fillCustomerFromContract(order);
        fillDefaultStatus(order, orgId);

        //自定义字段
        orderFieldService.saveModuleField(order, orgId, operatorId, moduleFields, false);
        orderMapper.insert(order);

        baseService.handleAddLogWithSubTable(order, moduleFields, Translator.get("products_info"), moduleFormConfigDTO);

        // 保存表单配置快照
        List<BaseModuleFieldValue> resolveFieldValues = moduleFormService.resolveSnapshotFields(moduleFields, moduleFormConfigDTO, orderFieldService, order.getId());
        OrderGetResponse response = get(order, resolveFieldValues, moduleFormConfigDTO);
        saveSnapshot(order, saveModuleFormConfigDTO, response);

        return order;
    }


    private String createOrderNumber(ModuleFormConfigDTO moduleFormConfigDTO, String orgId, String prefix) {
        BaseField numberField = moduleFormConfigDTO.getFields().stream()
                .filter(field -> field.isSerialNumber() && StringUtils.isNotEmpty(field.getBusinessKey())).findFirst().orElse(null);

        if (numberField != null) {
            return serialNumGenerator.generateByRules(((SerialNumberField) numberField).getSerialNumberRules(prefix), orgId, FormKey.ORDER.getKey());
        }
        return StringUtils.trimToNull(prefix);
    }

    private String resolveUpdateOrderNumber(ModuleFormConfigDTO moduleFormConfigDTO, String requestNumber, String oldOrderNo) {
        boolean serialNumber = moduleFormConfigDTO.getFields().stream()
                .anyMatch(field -> field.isSerialNumber() && StringUtils.isNotEmpty(field.getBusinessKey()));
        if (serialNumber) {
            return oldOrderNo;
        }
        return StringUtils.defaultIfBlank(StringUtils.trimToNull(requestNumber), oldOrderNo);
    }


    /**
     * 保存订单快照
     *
     * @param order
     * @param moduleFormConfigDTO
     * @param response
     */
    private void saveSnapshot(Order order, ModuleFormConfigDTO moduleFormConfigDTO, OrderGetResponse response) {
        //移除response中moduleFields 集合里 的 BaseModuleFieldValue 的 fieldId="products"的数据，避免快照数据过大
        if (CollectionUtils.isNotEmpty(response.getModuleFields())) {
            response.setModuleFields(response.getModuleFields().stream()
                    .filter(field -> (field.getFieldValue() != null && StringUtils.isNotBlank(field.getFieldValue().toString()) && !"[]".equals(field.getFieldValue().toString()))).toList());
        }
        OrderSnapshot snapshot = new OrderSnapshot();
        snapshot.setId(IDGenerator.nextStr());
        snapshot.setOrderId(order.getId());
        snapshot.setOrderProp(JSON.toJSONString(moduleFormConfigDTO));
        snapshot.setOrderValue(JSON.toJSONString(response));
        snapshotBaseMapper.insert(snapshot);
    }

    public OrderGetResponse getWithDataPermissionCheck(String id, String userId, String orgId) {
        OrderGetResponse getResponse = get(id);
        if (getResponse == null) {
            throw new GenericException(CrmHttpResultCode.NOT_FOUND);
        }
        dataScopeService.checkDataPermission(userId, orgId, resolveOwnerId(getResponse.getOwner(), orgId), PermissionConstants.ORDER_READ);
        return getResponse;
    }

    public OrderGetResponse getSnapshotWithDataPermissionCheck(String id, String userId, String orgId) {
        OrderGetResponse getResponse = getSnapshot(id);
        if (getResponse == null) {
            throw new GenericException(CrmHttpResultCode.NOT_FOUND);
        }
        dataScopeService.checkDataPermission(userId, orgId, resolveOwnerId(getResponse.getOwner(), orgId), PermissionConstants.ORDER_READ);
        return getResponse;
    }

    private OrderGetResponse get(Order order, List<BaseModuleFieldValue> orderFields, ModuleFormConfigDTO orderFormConfig) {
        OrderGetResponse orderGetResponse = BeanUtils.copyBean(new OrderGetResponse(), order);
        orderGetResponse = baseService.setCreateUpdateOwnerUserName(orderGetResponse);

        String id = order.getId();
        // 获取模块字段
        moduleFormService.processBusinessFieldValues(orderGetResponse, orderFields, orderFormConfig);
        orderGetResponse.setName(orderDisplayName(order));
        orderGetResponse.setStage(order.getStatus());
        orderFields = orderFieldService.setBusinessRefFieldValue(List.of(orderGetResponse),
                moduleFormService.getFlattenFormFields(FormKey.ORDER.getKey(), order.getOrganizationId()), new HashMap<>(Map.of(id, orderFields))).get(id);

        Map<String, List<OptionDTO>> optionMap = moduleFormService.getOptionMap(orderFormConfig, orderFields);

        orderGetResponse.setOwnerName(resolveOwnerName(orderGetResponse.getOwner(), order.getOrganizationId()));
        // 补充联系专员选项
        List<OptionDTO> ownerFieldOption = moduleFormService.getBusinessFieldOption(orderGetResponse,
                OrderGetResponse::getOwner, OrderGetResponse::getOwnerName);
        optionMap.put(BusinessModuleField.ORDER_OWNER.getBusinessKey(), ownerFieldOption);

        Customer customer = customerMapper.selectByPrimaryKey(order.getCustomerId());
        Contract contract = contractMapper.selectByPrimaryKey(order.getContractId());

        Map<String, String> stageNameMap = extOrderStageConfigMapper.getStageConfigList(order.getOrganizationId()).stream()
                .collect(Collectors.toMap(OrderStageConfigResponse::getId,
                        OrderStageConfigResponse::getName));
        orderGetResponse.setStageName(StringUtils.defaultIfBlank(stageNameMap.get(order.getStatus()), order.getStatus()));

        if (customer != null) {
            orderGetResponse.setCustomerName(customer.getName());
            optionMap.put("customerId", Collections.singletonList(new OptionDTO(customer.getId(), customer.getName())));
        }
        if (contract != null) {
            orderGetResponse.setContractName(contract.getName());
            optionMap.put("contractId", Collections.singletonList(new OptionDTO(contract.getId(), contract.getName())));
        }

        orderGetResponse.setOptionMap(optionMap);
        orderGetResponse.setModuleFields(orderFields);

        if (orderGetResponse.getOwner() != null) {
            UserDeptDTO userDeptDTO = baseService.getUserDeptMapByUserId(resolveOwnerId(orderGetResponse.getOwner(), order.getOrganizationId()), order.getOrganizationId());
            if (userDeptDTO != null) {
                orderGetResponse.setDepartmentId(userDeptDTO.getDeptId());
                orderGetResponse.setDepartmentName(userDeptDTO.getDeptName());
            }
        }

        // 附件信息
        orderGetResponse.setAttachmentMap(moduleFormService.getAttachmentMap(orderFormConfig, orderFields));
        return orderGetResponse;
    }

    /**
     * 获取订单详情
     *
     * @param id
     * @return
     */
    public OrderGetResponse get(String id) {
        Order order = orderMapper.selectByPrimaryKey(id);
        // 获取模块字段
        ModuleFormConfigDTO orderFormConfig = getFormConfig(order.getOrganizationId());
        List<BaseModuleFieldValue> orderFields = orderFieldService.getModuleFieldValuesByResourceId(id);
        return get(order, orderFields, orderFormConfig);
    }

    /**
     * 编辑订单
     *
     * @param request
     * @param userId
     * @param orgId
     * @return
     */
    @OperationLog(module = LogModule.ORDER_INDEX, type = LogType.UPDATE, resourceId = "{#request.id}")
    public Order update(OrderUpdateRequest request, String userId, String orgId) {
        Order oldOrder = orderMapper.selectByPrimaryKey(request.getId());
        List<BaseModuleFieldValue> moduleFields = request.getModuleFields();
        ModuleFormConfigDTO moduleFormConfigDTO = request.getModuleFormConfigDTO();
        if (moduleFields == null) {
            moduleFields = new ArrayList<>();
        }
        if (moduleFormConfigDTO == null) {
            throw new GenericException(Translator.get("order.form.config.required"));
        }
        List<BaseModuleFieldValue> normalizedModuleFields = moduleFields;
        ModuleFormConfigDTO saveModuleFormConfigDTO = JSON.parseObject(JSON.toJSONString(moduleFormConfigDTO), ModuleFormConfigDTO.class);
        Optional.ofNullable(oldOrder).ifPresentOrElse(item -> {

            List<BaseModuleFieldValue> originFields = orderFieldService.getModuleFieldValuesByResourceId(request.getId());
            Order order = BeanUtils.copyBean(new Order(), request);
            List<BaseModuleFieldValue> updateModuleFields = extractPromotedModuleFields(order, normalizedModuleFields, moduleFormConfigDTO);
            order.setUpdateTime(System.currentTimeMillis());
            order.setUpdateUser(userId);
            order.setOrderNo(resolveUpdateOrderNumber(moduleFormConfigDTO, order.getOrderNo(), oldOrder.getOrderNo()));
            if (StringUtils.isBlank(order.getOrderNo())) {
                throw new GenericException(Translator.get("order.number.required"));
            }
            if (order.getOrderNo().length() > MAX_NUMBER_LENGTH) {
                throw new GenericException(Translator.get("order.number.length.exceed"));
            }
            order.setCreateUser(oldOrder.getCreateUser());
            order.setCreateTime(oldOrder.getCreateTime());
            setAmount(request.getAmount(), order);
            fillOwnerFromMerchandiser(order, orgId);
            fillCustomerFromContract(order);
            fillDefaultStatus(order, orgId);
            updateFields(updateModuleFields, order, orgId, userId);
            orderMapper.update(order);
            //删除快照
            LambdaQueryWrapper<OrderSnapshot> delWrapper = new LambdaQueryWrapper<>();
            delWrapper.eq(OrderSnapshot::getOrderId, request.getId());
            List<OrderSnapshot> orderSnapshots = snapshotBaseMapper.selectListByLambda(delWrapper);
            if (CollectionUtils.isNotEmpty(orderSnapshots)) {
                OrderSnapshot first = orderSnapshots.getFirst();
                if (first != null) {
                    OrderGetResponse response = JSON.parseObject(first.getOrderValue(), OrderGetResponse.class);
                    List<BaseModuleFieldValue> originModuleFields = response.getModuleFields();
                    originFields.addAll(originModuleFields);
                }
            }
            snapshotBaseMapper.deleteByLambda(delWrapper);
            //保存快照
            List<BaseModuleFieldValue> resolveFieldValues = moduleFormService.resolveSnapshotFields(updateModuleFields, moduleFormConfigDTO, orderFieldService, order.getId());
            // get 方法需要使用orgId
            order.setOrganizationId(orgId);
            OrderGetResponse response = get(order, resolveFieldValues, moduleFormConfigDTO);
            saveSnapshot(order, saveModuleFormConfigDTO, response);
            baseService.handleUpdateLogWithSubTable(oldOrder, order, originFields, updateModuleFields, request.getId(), orderDisplayName(order), Translator.get("products_info"), moduleFormConfigDTO);
            // 处理日志上下文
        }, () -> {
            throw new GenericException(CrmHttpResultCode.NOT_FOUND);
        });
        return orderMapper.selectByPrimaryKey(request.getId());
    }

    private List<BaseModuleFieldValue> extractPromotedModuleFields(Order order, List<BaseModuleFieldValue> moduleFields,
                                                                   ModuleFormConfigDTO moduleFormConfigDTO) {
        if (CollectionUtils.isEmpty(moduleFields)) {
            return moduleFields;
        }
        Map<String, OrderPromotedField> promotedFieldMap = Optional.ofNullable(moduleFormConfigDTO)
                .map(ModuleFormConfigDTO::getFields)
                .orElse(List.of())
                .stream()
                .map(field -> Map.entry(field.getId(), OrderPromotedField.of(field.getId(), field.getInternalKey(), field.getBusinessKey())))
                .filter(entry -> entry.getValue() != null)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (first, second) -> first));

        return moduleFields.stream()
                .filter(fieldValue -> {
                    OrderPromotedField promotedField = promotedFieldMap.get(fieldValue.getFieldId());
                    if (promotedField == null) {
                        return true;
                    }
                    setPromotedFieldValue(order, promotedField, fieldValue.getFieldValue());
                    return false;
                })
                .toList();
    }

    private void setPromotedFieldValue(Order order, OrderPromotedField promotedField, Object fieldValue) {
        switch (promotedField) {
            case PROCESS_ORDER_NO -> order.setProcessOrderNo(toStringValue(fieldValue));
            case PROCESSOR -> order.setProcessor(toStringValue(fieldValue));
            case MERCHANDISER -> order.setMerchandiser(toStringValue(fieldValue));
            case STATUS -> order.setStatus(toStringValue(fieldValue));
            case COLOR -> order.setColor(toStringValue(fieldValue));
            case COLOR_CODE -> order.setColorCode(toStringValue(fieldValue));
            case COMPOSITION -> order.setComposition(toStringValue(fieldValue));
            case MATERIAL_NAME -> order.setMaterialName(toStringValue(fieldValue));
            case MATERIAL_TYPE -> order.setMaterialType(toStringValue(fieldValue));
            case PROCESS_TECHNOLOGY -> order.setProcessTechnology(toStringValue(fieldValue));
            case ORDER_TIME -> order.setOrderTime(toLongValue(fieldValue));
            case QUANTITY -> order.setQuantity(toBigDecimalValue(fieldValue));
            case UNIT -> order.setUnit(toStringValue(fieldValue));
            case UNIT_PRICE -> order.setUnitPrice(toBigDecimalValue(fieldValue));
            case AMOUNT -> order.setAmount(toBigDecimalValue(fieldValue));
            case CURRENCY -> order.setCurrency(toStringValue(fieldValue));
        }
    }

    private void fillOwnerFromMerchandiser(Order order, String orgId) {
        if (StringUtils.isBlank(order.getOwner()) && StringUtils.isNotBlank(order.getMerchandiser())) {
            order.setOwner(resolveOwnerName(order.getMerchandiser(), orgId));
        }
        if (StringUtils.isNotBlank(order.getOwner())) {
            order.setOwner(resolveOwnerName(order.getOwner(), orgId));
        }
        if (StringUtils.isBlank(order.getMerchandiser()) && StringUtils.isNotBlank(order.getOwner())) {
            order.setMerchandiser(order.getOwner());
        }
    }

    private String resolveOwnerId(String owner, String orgId) {
        return StringUtils.defaultIfBlank(baseService.resolveUserIdByIdOrName(owner, orgId), owner);
    }

    private String resolveOwnerName(String owner, String orgId) {
        if (StringUtils.isBlank(owner)) {
            return null;
        }
        String ownerId = baseService.resolveUserIdByIdOrName(owner, orgId);
        if (StringUtils.isNotBlank(ownerId)) {
            return StringUtils.defaultIfBlank(baseService.getUserName(ownerId), owner);
        }
        return owner;
    }

    private String toStringValue(Object value) {
        return value == null ? null : StringUtils.trimToNull(value.toString());
    }

    private Long toLongValue(Object value) {
        if (value == null || StringUtils.isBlank(value.toString())) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.valueOf(value.toString());
    }

    private BigDecimal toBigDecimalValue(Object value) {
        if (value == null || StringUtils.isBlank(value.toString())) {
            return null;
        }
        if (value instanceof BigDecimal bigDecimal) {
            return bigDecimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return new BigDecimal(value.toString());
    }

    private void fillCustomerFromContract(Order order) {
        if (StringUtils.isNotBlank(order.getCustomerId()) || StringUtils.isBlank(order.getContractId())) {
            return;
        }
        Contract contract = contractMapper.selectByPrimaryKey(order.getContractId());
        if (contract != null) {
            order.setCustomerId(contract.getCustomerId());
        }
    }

    private void fillDefaultStatus(Order order, String orgId) {
        if (StringUtils.isNotBlank(order.getStatus())) {
            return;
        }
        List<OrderStageConfigResponse> stageConfigList = extOrderStageConfigMapper.getStageConfigList(orgId);
        if (CollectionUtils.isNotEmpty(stageConfigList)) {
            order.setStatus(stageConfigList.getFirst().getId());
        }
    }

    private String orderDisplayName(Order order) {
        if (order == null) {
            return null;
        }
        return StringUtils.firstNonBlank(order.getOrderNo(), order.getProcessOrderNo(), order.getId());
    }

    private void setAmount(String amount, Order order) {
        if (StringUtils.isNotBlank(amount)) {
            order.setAmount(new BigDecimal(amount));
            if (order.getAmount().compareTo(MAX_AMOUNT) > 0) {
                throw new GenericException(Translator.get("order.amount.exceed.max"));
            }
        } else if (order.getAmount() == null) {
            order.setAmount(BigDecimal.ZERO);
        }
    }


    /**
     * 更新自定义字段
     *
     * @param moduleFields
     * @param order
     * @param orgId
     * @param userId
     */
    private void updateFields(List<BaseModuleFieldValue> moduleFields, Order order, String orgId, String userId) {
        if (moduleFields == null) {
            return;
        }
        orderFieldService.deleteByResourceId(order.getId());
        orderFieldService.saveModuleField(order, orgId, userId, moduleFields, true);
    }


    /**
     * 删除订单
     *
     * @param id 订单ID
     */
    @OperationLog(module = LogModule.ORDER_INDEX, type = LogType.DELETE, resourceId = "{#id}")
    public void delete(String id) {
        Order order = orderMapper.selectByPrimaryKey(id);
        if (order == null) {
            throw new GenericException(CrmHttpResultCode.NOT_FOUND);
        }

        orderFieldService.deleteByResourceId(id);
        orderMapper.deleteByPrimaryKey(id);

        //删除快照
        LambdaQueryWrapper<OrderSnapshot> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OrderSnapshot::getOrderId, id);
        snapshotBaseMapper.deleteByLambda(wrapper);
        // 添加日志上下文
        OperationLogContext.setResourceName(orderDisplayName(order));
    }


    /**
     * ⚠️反射调用; 勿修改入参, 返回, 方法名!
     *
     * @param id 订单ID
     * @return 订单详情
     */
    public OrderGetResponse getSnapshot(String id) {
        OrderGetResponse response = new OrderGetResponse();
        Order order = orderMapper.selectByPrimaryKey(id);
        if (order == null) {
            return null;
        }
        LambdaQueryWrapper<OrderSnapshot> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OrderSnapshot::getOrderId, id);
        OrderSnapshot snapshot = snapshotBaseMapper.selectListByLambda(wrapper).stream().findFirst().orElse(null);
        if (snapshot != null) {
            response = JSON.parseObject(snapshot.getOrderValue(), OrderGetResponse.class);
            if (StringUtils.isNotBlank(order.getCustomerId())) {
                Customer customer = customerBaseMapper.selectByPrimaryKey(order.getCustomerId());
                if (customer != null) {
                    response.setInCustomerPool(customer.getInSharedPool());
                    response.setPoolId(customer.getPoolId());
                }
            }
        }
        return response;
    }


    /**
     * 订单列表
     *
     * @param request
     * @param userId
     * @param orgId
     * @param deptDataPermission
     * @return
     */
    public PagerWithOption<List<OrderListResponse>> list(OrderPageRequest request, String userId, String orgId, DeptDataPermissionDTO deptDataPermission, Boolean source) {
        Page<Object> page = PageHelper.startPage(request.getCurrent(), request.getPageSize());
        List<OrderListResponse> list = extOrderMapper.list(request, orgId, userId, deptDataPermission, source);
        List<OrderListResponse> results = buildList(list, orgId);
        ModuleFormConfigDTO customerFormConfig = getFormConfig(orgId);
        Map<String, List<OptionDTO>> optionMap = buildOptionMap(list, results, customerFormConfig);

        return PageUtils.setPageInfoWithOption(page, results, optionMap);
    }

    public PagerWithOption<List<OrderSummaryResponse>> summaryList(OrderPageRequest request, String userId, String orgId, DeptDataPermissionDTO deptDataPermission) {
        Page<Object> page = PageHelper.startPage(request.getCurrent(), request.getPageSize());
        List<OrderSummaryResponse> list = extOrderMapper.summaryList(request, orgId, userId, deptDataPermission);
        List<OrderSummaryResponse> results = buildSummaryList(list, orgId);
        return PageUtils.setPageInfoWithOption(page, results, Collections.emptyMap());
    }

    private List<OrderSummaryResponse> buildSummaryList(List<OrderSummaryResponse> list, String orgId) {
        if (CollectionUtils.isEmpty(list)) {
            return list;
        }
        List<String> ownerValues = list.stream()
                .map(OrderSummaryResponse::getOwner)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList();
        Map<String, String> ownerIdMap = baseService.resolveUserIdsByIdsOrNames(ownerValues, orgId);
        List<String> ownerIds = ownerIdMap.values().stream().distinct().toList();
        Map<String, String> userNameMap = baseService.getUserNameMap(ownerIds);
        list.forEach(item -> {
            String ownerId = ownerIdMap.get(item.getOwner());
            item.setOwnerName(StringUtils.defaultIfBlank(userNameMap.get(ownerId), item.getOwner()));
        });
        return list;
    }

    private Map<String, List<OptionDTO>> buildOptionMap(List<OrderListResponse> list, List<OrderListResponse> buildList,
                                                        ModuleFormConfigDTO formConfig) {
        // 获取所有模块字段的值
        List<BaseModuleFieldValue> moduleFieldValues = moduleFormService.getBaseModuleFieldValues(list, OrderListResponse::getModuleFields);
        // 获取选项值对应的 option
        Map<String, List<OptionDTO>> optionMap = moduleFormService.getOptionMap(formConfig, moduleFieldValues);
        // 补充联系专员选项
        List<OptionDTO> ownerFieldOption = moduleFormService.getBusinessFieldOption(buildList,
                OrderListResponse::getOwner, OrderListResponse::getOwnerName);
        optionMap.put(BusinessModuleField.ORDER_OWNER.getBusinessKey(), ownerFieldOption);
        return optionMap;
    }

    private ModuleFormConfigDTO getFormConfig(String orgId) {
        return moduleFormCacheService.getBusinessFormConfig(FormKey.ORDER.getKey(), orgId);
    }

    public List<OrderListResponse> buildList(List<OrderListResponse> list, String orgId) {
        if (CollectionUtils.isEmpty(list)) {
            return list;
        }

        List<String> orderIds = list.stream().map(OrderListResponse::getId)
                .collect(Collectors.toList());
        Map<String, List<BaseModuleFieldValue>> orderFiledMap = orderFieldService.getResourceFieldMap(orderIds, true);
        Map<String, List<BaseModuleFieldValue>> resolvefieldValueMap = orderFieldService.setBusinessRefFieldValue(list, moduleFormService.getFlattenFormFields(FormKey.ORDER.getKey(), orgId), orderFiledMap);


        List<String> ownerValues = list.stream()
                .map(OrderListResponse::getOwner)
                .distinct()
                .toList();
        Map<String, String> ownerIdMap = baseService.resolveUserIdsByIdsOrNames(ownerValues, orgId);
        List<String> ownerIds = ownerIdMap.values().stream().distinct().toList();
        Map<String, String> userNameMap = baseService.getUserNameMap(ownerIds);
        Map<String, UserDeptDTO> userDeptMap = baseService.getUserDeptMapByUserIds(ownerIds, orgId);

        Map<String, String> stageNameMap = extOrderStageConfigMapper.getStageConfigList(orgId).stream()
                .collect(Collectors.toMap(OrderStageConfigResponse::getId,
                        OrderStageConfigResponse::getName));

        list.forEach(item -> {
            item.setName(orderDisplayName(item));
            item.setStage(item.getStatus());
            String ownerId = ownerIdMap.get(item.getOwner());
            item.setOwnerName(StringUtils.defaultIfBlank(userNameMap.get(ownerId), item.getOwner()));
            UserDeptDTO userDeptDTO = userDeptMap.get(ownerId);
            if (userDeptDTO != null) {
                item.setDepartmentId(userDeptDTO.getDeptId());
                item.setDepartmentName(userDeptDTO.getDeptName());
            }
            item.setStageName(StringUtils.defaultIfBlank(stageNameMap.get(item.getStatus()), item.getStatus()));
            // 获取自定义字段
            List<BaseModuleFieldValue> orderFields = resolvefieldValueMap.get(item.getId());
            item.setModuleFields(orderFields);
        });
        return baseService.setCreateAndUpdateUserName(list);
    }


    /**
     * 获取表单快照
     *
     * @param id
     * @param orgId
     * @return
     */
    public ModuleFormConfigDTO getFormSnapshot(String id, String orgId) {
        Order order = orderMapper.selectByPrimaryKey(id);
        if (order == null) {
            throw new GenericException(CrmHttpResultCode.NOT_FOUND);
        }
        LambdaQueryWrapper<OrderSnapshot> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OrderSnapshot::getOrderId, id);
        OrderSnapshot snapshot = snapshotBaseMapper.selectListByLambda(wrapper).stream().findFirst().orElse(null);
        if (snapshot != null) {
            return JSON.parseObject(snapshot.getOrderProp(), ModuleFormConfigDTO.class);
        } else {
            return moduleFormCacheService.getBusinessFormConfig(FormKey.ORDER.getKey(), orgId);
        }
    }


    public ResourceTabEnableDTO getTabEnableConfig(String userId, String orgId) {
        List<RolePermissionDTO> rolePermissions = permissionCache.getRolePermissions(userId, orgId);
        return PermissionUtils.getTabEnableConfig(userId, PermissionConstants.ORDER_READ, rolePermissions);
    }

    private void updateStageSnapshot(String id, String stage) {
        if (StringUtils.isBlank(stage)) {
            return;
        }
        LambdaQueryWrapper<OrderSnapshot> delWrapper = new LambdaQueryWrapper<>();
        delWrapper.eq(OrderSnapshot::getOrderId, id);
        List<OrderSnapshot> orderSnapshots = snapshotBaseMapper.selectListByLambda(delWrapper);
        OrderSnapshot first = orderSnapshots.getFirst();
        if (first != null) {
            OrderGetResponse response = JSON.parseObject(first.getOrderValue(), OrderGetResponse.class);
            response.setStage(stage);
            response.setStatus(stage);
            first.setOrderValue(JSON.toJSONString(response));
            snapshotBaseMapper.update(first);
        }
    }

    public Order selectByPrimaryKey(String id) {
        return orderMapper.selectByPrimaryKey(id);
    }

    public void updateStage(OrderStageRequest request, String userId, String orgId) {
        Order order = orderMapper.selectByPrimaryKey(request.getId());
        if (order == null) {
            throw new GenericException(CrmHttpResultCode.NOT_FOUND);
        }

        Map<String, String> oldMap = new HashMap<>();
        oldMap.put("orderStage", StringUtils.defaultString(order.getStatus()));

        order.setStatus(request.getStage());

        order.setUpdateTime(System.currentTimeMillis());
        order.setUpdateUser(userId);
        orderMapper.update(order);

        updateStageSnapshot(request.getId(), request.getStage());

        LogDTO logDTO = new LogDTO(orgId, request.getId(), userId, LogType.UPDATE, LogModule.ORDER_INDEX, orderDisplayName(order));
        Map<String, String> newMap = new HashMap<>();
        newMap.put("orderStage", StringUtils.defaultString(request.getStage()));
        logDTO.setOriginalValue(oldMap);
        logDTO.setModifiedValue(newMap);
        logService.add(logDTO);
    }

    public void download(String id, String userId, String organizationId) {
        OrderGetResponse getResponse = get(id);
        if (getResponse == null) {
            throw new GenericException(Translator.get("order_not_exist"));
        }

        LogDTO logDTO = new LogDTO(organizationId, id, userId, LogType.DOWNLOAD, LogModule.ORDER_INDEX, getResponse.getName());
        logDTO.setOriginalValue(getResponse.getName());
        logService.add(logDTO);
    }


    /**
     * 统计
     *
     * @param request
     * @param userId
     * @param orgId
     * @param deptDataPermission
     * @return
     */
    public OrderStatisticResponse searchStatistic(BaseCondition request, String userId, String orgId, DeptDataPermissionDTO deptDataPermission) {
        OrderStatisticResponse response = extOrderMapper.searchStatistic(request, orgId, userId, deptDataPermission);
        return Optional.ofNullable(response).orElse(new OrderStatisticResponse());
    }


    /**
     * 通过ID集合获取订单名称
     *
     * @param ids id集合
     * @return 工商表头名称
     */
    public Object getOrderNameByIds(List<String> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return StringUtils.EMPTY;
        }
        List<Order> records = orderMapper.selectByIds(ids);
        if (CollectionUtils.isNotEmpty(records)) {
            List<String> names = records.stream().map(this::orderDisplayName).toList();
            return String.join(",", names);
        }
        return StringUtils.EMPTY;
    }


    /**
     * 通过名称获取订单集合
     *
     * @param names 名称
     * @return 订单名称
     */
    public List<Order> getOrderListByNames(List<String> names) {
        LambdaQueryWrapper<Order> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        lambdaQueryWrapper.in(Order::getOrderNo, names);
        return orderMapper.selectListByLambda(lambdaQueryWrapper);
    }

    public Object getOrderName(String id) {
        Order order = orderMapper.selectByPrimaryKey(id);
        if (order != null) {
            return orderDisplayName(order);
        }
        return null;
    }
}
