package cn.cordys.crm.contract.service;

import cn.cordys.aspectj.annotation.OperationLog;
import cn.cordys.aspectj.constants.LogModule;
import cn.cordys.aspectj.constants.LogType;
import cn.cordys.aspectj.context.OperationLogContext;
import cn.cordys.aspectj.dto.LogDTO;
import cn.cordys.common.constants.BusinessModuleField;
import cn.cordys.common.constants.ContractPromotedField;
import cn.cordys.common.constants.FormKey;
import cn.cordys.common.constants.PermissionConstants;
import cn.cordys.common.domain.BaseModuleFieldValue;
import cn.cordys.common.domain.BaseResourceSubField;
import cn.cordys.common.dto.*;
import cn.cordys.common.dto.condition.BaseCondition;
import cn.cordys.common.dto.condition.FilterCondition;
import cn.cordys.common.exception.GenericException;
import cn.cordys.common.pager.PageUtils;
import cn.cordys.common.pager.PagerWithOption;
import cn.cordys.common.permission.PermissionCache;
import cn.cordys.common.permission.PermissionUtils;
import cn.cordys.common.service.BaseService;
import cn.cordys.common.service.DataScopeService;
import cn.cordys.common.uid.IDGenerator;
import cn.cordys.common.uid.SerialNumGenerator;
import cn.cordys.common.util.BeanUtils;
import cn.cordys.common.util.JSON;
import cn.cordys.common.util.Translator;
import cn.cordys.crm.contract.constants.ContractApprovalStatus;
import cn.cordys.crm.contract.domain.Contract;
import cn.cordys.crm.contract.domain.ContractField;
import cn.cordys.crm.contract.domain.ContractFieldBlob;
import cn.cordys.crm.contract.domain.ContractPaymentRecord;
import cn.cordys.crm.contract.domain.ContractSnapshot;
import cn.cordys.crm.contract.dto.request.*;
import cn.cordys.crm.contract.dto.response.ContractGetResponse;
import cn.cordys.crm.contract.dto.response.ContractListResponse;
import cn.cordys.crm.contract.dto.response.ContractStatisticResponse;
import cn.cordys.crm.contract.dto.response.CustomerContractStatisticResponse;
import cn.cordys.crm.contract.mapper.ExtContractInvoiceMapper;
import cn.cordys.crm.contract.mapper.ExtContractMapper;
import cn.cordys.crm.customer.domain.Customer;
import cn.cordys.crm.system.constants.SheetKey;
import cn.cordys.crm.system.dto.field.SerialNumberField;
import cn.cordys.crm.system.dto.field.base.BaseField;
import cn.cordys.crm.system.dto.response.ImportResponse;
import cn.cordys.crm.system.dto.response.ModuleFormConfigDTO;
import cn.cordys.crm.system.excel.CustomImportAfterDoConsumer;
import cn.cordys.crm.system.excel.handler.CustomHeadColWidthStyleStrategy;
import cn.cordys.crm.system.excel.handler.CustomTemplateWriteHandler;
import cn.cordys.crm.system.excel.listener.CustomFieldCheckEventListener;
import cn.cordys.crm.system.excel.listener.CustomFieldImportEventListener;
import cn.cordys.crm.system.service.LogService;
import cn.cordys.crm.system.service.ModuleFormCacheService;
import cn.cordys.crm.system.service.ModuleFormService;
import cn.cordys.excel.utils.EasyExcelExporter;
import cn.cordys.mybatis.BaseMapper;
import cn.cordys.mybatis.lambda.LambdaQueryWrapper;
import cn.idev.excel.FastExcelFactory;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@Transactional(rollbackFor = Exception.class)
public class ContractService {

    @Resource
    private ContractFieldService contractFieldService;
    @Resource
    private BaseMapper<ContractField> contractFieldMapper;
    @Resource
    private BaseMapper<ContractFieldBlob> contractFieldBlobMapper;
    @Resource
    private BaseMapper<Contract> contractMapper;
    @Resource
    private BaseService baseService;
    @Resource
    private ModuleFormService moduleFormService;
    @Resource
    private BaseMapper<ContractSnapshot> snapshotBaseMapper;
    @Resource
    private ExtContractMapper extContractMapper;
    @Resource
    private ModuleFormCacheService moduleFormCacheService;
    @Resource
    private PermissionCache permissionCache;
    @Resource
    private BaseMapper<Customer> customerBaseMapper;
    @Resource
    private LogService logService;
    @Resource
    private SerialNumGenerator serialNumGenerator;
    @Resource
    private DataScopeService dataScopeService;
    @Resource
    private BaseMapper<ContractPaymentRecord> contractPaymentRecordMapper;
    @Resource
    private ExtContractInvoiceMapper extContractInvoiceMapper;

    private static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999");
    private static final String MLS_CONTRACT_DEPARTMENT_NAME = "木林森";

    /**
     * 新建合同
     *
     * @param request
     * @param operatorId
     * @param orgId
     * @return
     */
    @OperationLog(module = LogModule.CONTRACT_INDEX, type = LogType.ADD, resourceName = "{#request.name}")
    public Contract add(ContractAddRequest request, String operatorId, String orgId) {
        List<BaseModuleFieldValue> moduleFields = request.getModuleFields();
        ModuleFormConfigDTO moduleFormConfigDTO = request.getModuleFormConfigDTO();
        if (CollectionUtils.isEmpty(moduleFields)) {
            throw new GenericException(Translator.get("contract.field.required"));
        }
        if (moduleFormConfigDTO == null) {
            throw new GenericException(Translator.get("contract.form.config.required"));
        }
        ModuleFormConfigDTO saveModuleFormConfigDTO = JSON.parseObject(JSON.toJSONString(moduleFormConfigDTO), ModuleFormConfigDTO.class);
        Contract contract = new Contract();
        String id = IDGenerator.nextStr();
        contract.setId(id);
        contract.setName(request.getName());
        contract.setCustomerId(request.getCustomerId());
        contract.setOwner(request.getOwner());
        contract.setOrderStatus(request.getOrderStatus());
        contract.setCurrency(request.getCurrency());
        contract.setNumber(createContractNumber(moduleFormConfigDTO, orgId, request.getNumber()));
        contract.setOrganizationId(orgId);
        contract.setApprovalStatus(ContractApprovalStatus.NONE.name());
        contract.setStartTime(request.getStartTime());
        contract.setEndTime(request.getEndTime());
        contract.setCreateTime(System.currentTimeMillis());
        contract.setCreateUser(operatorId);
        contract.setUpdateTime(System.currentTimeMillis());
        contract.setUpdateUser(operatorId);

        //判断总金额
        setAmount(request.getAmount(), contract);

        // 设置子表格字段值
        moduleFields = extractPromotedModuleFields(contract, moduleFields, moduleFormConfigDTO);
        moduleFields.add(new BaseModuleFieldValue("products", request.getProducts()));
        //自定义字段
        contractFieldService.saveModuleField(contract, orgId, operatorId, moduleFields, false);
        contractMapper.insert(contract);

        baseService.handleAddLogWithSubTable(contract, moduleFields, Translator.get("products_info"), moduleFormConfigDTO);

        // 保存表单配置快照
        List<BaseModuleFieldValue> resolveFieldValues = moduleFormService.resolveSnapshotFields(moduleFields, moduleFormConfigDTO, contractFieldService, contract.getId());
        ContractGetResponse response = get(contract, resolveFieldValues, moduleFormConfigDTO);
        saveSnapshot(contract, saveModuleFormConfigDTO, response);

        return contract;
    }

    private String createContractNumber(ModuleFormConfigDTO moduleFormConfigDTO, String orgId, String prefix) {
        BaseField numberField = moduleFormConfigDTO.getFields().stream()
                .filter(field -> field.isSerialNumber() && StringUtils.isNotEmpty(field.getBusinessKey())).findFirst().orElse(null);

        if (numberField instanceof SerialNumberField serialField) {
            return serialNumGenerator.generateByRules(serialField.getSerialNumberRules(prefix), orgId, FormKey.CONTRACT.getKey());
        }
        return null;
    }


    /**
     * 保存合同快照
     *
     * @param contract
     * @param moduleFormConfigDTO
     * @param response
     */
    private void saveSnapshot(Contract contract, ModuleFormConfigDTO moduleFormConfigDTO, ContractGetResponse response) {
        //移除response中moduleFields 集合里 的 BaseModuleFieldValue 的 fieldId="products"的数据，避免快照数据过大
        if (CollectionUtils.isNotEmpty(response.getModuleFields())) {
            response.setModuleFields(response.getModuleFields().stream()
                    .filter(field -> (field.getFieldValue() != null && StringUtils.isNotBlank(field.getFieldValue().toString()) && !"[]".equals(field.getFieldValue().toString()))).toList());
        }
        ContractSnapshot snapshot = new ContractSnapshot();
        snapshot.setId(IDGenerator.nextStr());
        snapshot.setContractId(contract.getId());
        snapshot.setContractProp(JSON.toJSONString(moduleFormConfigDTO));
        snapshot.setContractValue(JSON.toJSONString(response));
        snapshotBaseMapper.insert(snapshot);

    }

    public ContractGetResponse getWithDataPermissionCheck(String id, String userId, String orgId) {
        ContractGetResponse getResponse = get(id);
        if (getResponse == null) {
            throw new GenericException(Translator.get("resource.not.exist"));
        }
        dataScopeService.checkDataPermission(userId, orgId, getPermissionOwner(getResponse, orgId), PermissionConstants.CONTRACT_READ);
        return getResponse;
    }

    public ContractGetResponse getSnapshotWithDataPermissionCheck(String id, String userId, String orgId) {
        ContractGetResponse getResponse = getSnapshot(id);
        if (getResponse == null) {
            throw new GenericException(Translator.get("resource.not.exist"));
        }
        dataScopeService.checkDataPermission(userId, orgId, getPermissionOwner(getResponse, orgId), PermissionConstants.CONTRACT_READ);
        return getResponse;
    }

    private String getPermissionOwner(ContractGetResponse response, String orgId) {
        return getPermissionOwner(response.getOwner(), response.getCreateUser(), orgId);
    }

    private String getPermissionOwner(String owner, String createUser, String orgId) {
        String resolvedOwner = baseService.resolveUserIdByIdOrName(owner, orgId);
        if (StringUtils.isNotBlank(resolvedOwner)) {
            return resolvedOwner;
        }
        return createUser;
    }

    private void fallbackExternalAuditUserNames(ContractListResponse response) {
        if (response == null) {
            return;
        }
        if (isMissingResolvedUserName(response.getCreateUserName())) {
            response.setCreateUserName(response.getCreateUser());
        }
        if (isMissingResolvedUserName(response.getUpdateUserName())) {
            response.setUpdateUserName(response.getUpdateUser());
        }
    }

    private boolean isMissingResolvedUserName(String value) {
        return StringUtils.isBlank(value)
                || Strings.CS.equals(value, Translator.get("common.option.not_exist"));
    }

    private void applyMlsContractDepartment(List<? extends ContractListResponse> responses, String orgId) {
        if (CollectionUtils.isEmpty(responses) || StringUtils.isBlank(orgId)) {
            return;
        }
        List<String> contractIds = responses.stream()
                .map(ContractListResponse::getId)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList();
        if (contractIds.isEmpty()) {
            return;
        }
        Set<String> mlsContractIds = new HashSet<>(
                extContractMapper.selectMlsSyncedContractIds(contractIds, orgId));
        responses.stream()
                .filter(response -> mlsContractIds.contains(response.getId()))
                .forEach(response -> {
                    response.setDepartmentId(null);
                    response.setDepartmentName(MLS_CONTRACT_DEPARTMENT_NAME);
                });
    }

    private ContractGetResponse get(Contract contract, List<BaseModuleFieldValue> contractFields, ModuleFormConfigDTO contractFormConfig) {
        ContractGetResponse contractGetResponse = BeanUtils.copyBean(new ContractGetResponse(), contract);
        contractGetResponse = baseService.setCreateUpdateOwnerUserName(contractGetResponse);
        fallbackExternalAuditUserNames(contractGetResponse);
        if (StringUtils.isNotBlank(contract.getOwner()) &&
                (StringUtils.isBlank(contractGetResponse.getOwnerName()) ||
                        Strings.CS.equals(contractGetResponse.getOwnerName(), Translator.get("common.option.not_exist")))) {
            contractGetResponse.setOwnerName(contract.getOwner());
        }

        String id = contract.getId();
        // 获取模块字段
        moduleFormService.processBusinessFieldValues(contractGetResponse, contractFields, contractFormConfig);
        contractFields = contractFieldService.setBusinessRefFieldValue(List.of(contractGetResponse),
                moduleFormService.getFlattenFormFields(FormKey.CONTRACT.getKey(), contract.getOrganizationId()), new HashMap<>(Map.of(id, contractFields))).get(id);

        Map<String, List<OptionDTO>> optionMap = moduleFormService.getOptionMap(contractFormConfig, contractFields);

        // 补充负责人选项
        List<OptionDTO> ownerFieldOption = moduleFormService.getBusinessFieldOption(contractGetResponse,
                ContractGetResponse::getOwner, ContractGetResponse::getOwnerName);
        optionMap.put(BusinessModuleField.CONTRACT_OWNER.getBusinessKey(), ownerFieldOption);

        Customer customer = customerBaseMapper.selectByPrimaryKey(contract.getCustomerId());
        String customerName = customer == null ? contract.getCustomerId() : customer.getName();
        if (StringUtils.isNotBlank(customerName)) {
            contractGetResponse.setCustomerName(customerName);
            optionMap.put(BusinessModuleField.CONTRACT_CUSTOMER_NAME.getBusinessKey(), Collections.singletonList(new OptionDTO(contract.getCustomerId(), customerName)));
        }

        contractGetResponse.setOptionMap(optionMap);
        contractGetResponse.setModuleFields(contractFields);

        if (contractGetResponse.getOwner() != null) {
            UserDeptDTO userDeptDTO = baseService.getUserDeptMapByUserId(contractGetResponse.getOwner(), contract.getOrganizationId());
            if (userDeptDTO != null) {
                contractGetResponse.setDepartmentId(userDeptDTO.getDeptId());
                contractGetResponse.setDepartmentName(userDeptDTO.getDeptName());
            }
        }
        applyMlsContractDepartment(List.of(contractGetResponse), contract.getOrganizationId());

        // 附件信息
        contractGetResponse.setAttachmentMap(moduleFormService.getAttachmentMap(contractFormConfig, contractFields));

        return contractGetResponse;
    }

    /**
     * 获取合同详情
     *
     * @param id
     * @return
     */
    public ContractGetResponse get(String id) {
        Contract contract = contractMapper.selectByPrimaryKey(id);
        // 获取模块字段
        ModuleFormConfigDTO contractFormConfig = getFormConfig(contract.getOrganizationId());
        List<BaseModuleFieldValue> contractFields = contractFieldService.getModuleFieldValuesByResourceId(id);
        return get(contract, contractFields, contractFormConfig);
    }

    /**
     * 编辑合同
     *
     * @param request
     * @param userId
     * @param orgId
     * @return
     */
    @OperationLog(module = LogModule.CONTRACT_INDEX, type = LogType.UPDATE, resourceId = "{#request.id}")
    public Contract update(ContractUpdateRequest request, String userId, String orgId) {
        Contract oldContract = contractMapper.selectByPrimaryKey(request.getId());
        List<BaseModuleFieldValue> moduleFields = request.getModuleFields();
        ModuleFormConfigDTO moduleFormConfigDTO = request.getModuleFormConfigDTO();
        if (CollectionUtils.isEmpty(moduleFields)) {
            throw new GenericException(Translator.get("contract.field.required"));
        }
        if (moduleFormConfigDTO == null) {
            throw new GenericException(Translator.get("contract.form.config.required"));
        }
        ModuleFormConfigDTO saveModuleFormConfigDTO = JSON.parseObject(JSON.toJSONString(moduleFormConfigDTO), ModuleFormConfigDTO.class);
        Optional.ofNullable(oldContract).ifPresentOrElse(item -> {

            List<BaseModuleFieldValue> originFields = contractFieldService.getModuleFieldValuesByResourceId(request.getId());
            Contract contract = BeanUtils.copyBean(new Contract(), request);
            List<BaseModuleFieldValue> updateModuleFields = extractPromotedModuleFields(contract, moduleFields, moduleFormConfigDTO);
            contract.setStartTime(request.getStartTime());
            contract.setEndTime(request.getEndTime());
            contract.setUpdateTime(System.currentTimeMillis());
            contract.setUpdateUser(userId);
            // 保留不可更改的字段
            contract.setNumber(oldContract.getNumber());
            contract.setCreateUser(oldContract.getCreateUser());
            contract.setCreateTime(oldContract.getCreateTime());
            contract.setApprovalStatus(ContractApprovalStatus.NONE.name());

            //判断总金额
            setAmount(request.getAmount(), contract);
            updateModuleFields.add(new BaseModuleFieldValue("products", request.getProducts()));
            updateFields(updateModuleFields, contract, orgId, userId);
            contractMapper.update(contract);
            //删除快照
            LambdaQueryWrapper<ContractSnapshot> delWrapper = new LambdaQueryWrapper<>();
            delWrapper.eq(ContractSnapshot::getContractId, request.getId());
            List<ContractSnapshot> contractSnapshots = snapshotBaseMapper.selectListByLambda(delWrapper);
            if (CollectionUtils.isNotEmpty(contractSnapshots)) {
                ContractSnapshot first = contractSnapshots.getFirst();
                if (first != null) {
                    ContractGetResponse response = JSON.parseObject(first.getContractValue(), ContractGetResponse.class);
                    List<BaseModuleFieldValue> originModuleFields = response.getModuleFields();
                    originModuleFields.add(new BaseModuleFieldValue("products", response.getProducts()));
                    originFields.addAll(originModuleFields);
                }
            }
            snapshotBaseMapper.deleteByLambda(delWrapper);
            //保存快照
            List<BaseModuleFieldValue> resolveFieldValues = moduleFormService.resolveSnapshotFields(updateModuleFields, moduleFormConfigDTO, contractFieldService, contract.getId());
            // get 方法需要使用orgId
            contract.setOrganizationId(orgId);
            ContractGetResponse response = get(contract, resolveFieldValues, moduleFormConfigDTO);
            saveSnapshot(contract, saveModuleFormConfigDTO, response);
            // 处理日志上下文
            baseService.handleUpdateLogWithSubTable(oldContract, contract, originFields, updateModuleFields, request.getId(), contract.getName(), Translator.get("products_info"), moduleFormConfigDTO);
        }, () -> {
            throw new GenericException(Translator.get("contract.not.exist"));
        });
        return contractMapper.selectByPrimaryKey(request.getId());
    }

    private void setAmount(String amount, Contract contract) {
        if (StringUtils.isNotBlank(amount)) {
            contract.setAmount(new BigDecimal(amount));
            if (contract.getAmount().compareTo(MAX_AMOUNT) > 0) {
                throw new GenericException(Translator.get("contract.amount.exceed.max"));
            }
        } else {
            contract.setAmount(BigDecimal.ZERO);
        }
    }

    /**
     * Move promoted form values onto the contract entity so they are persisted
     * in contract.order_status/currency rather than contract_field.
     */
    private List<BaseModuleFieldValue> extractPromotedModuleFields(Contract contract,
                                                                    List<BaseModuleFieldValue> moduleFields,
                                                                    ModuleFormConfigDTO formConfig) {
        if (CollectionUtils.isEmpty(moduleFields)) {
            return moduleFields;
        }
        Map<String, ContractPromotedField> promotedFieldMap = Optional.ofNullable(formConfig)
                .map(ModuleFormConfigDTO::getFields)
                .orElse(List.of())
                .stream()
                .map(field -> new AbstractMap.SimpleImmutableEntry<>(field.getId(),
                        ContractPromotedField.of(field.getId(), field.getInternalKey(), field.getBusinessKey())))
                .filter(entry -> entry.getValue() != null)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (first, second) -> first));

        return moduleFields.stream()
                .filter(fieldValue -> {
                    ContractPromotedField promotedField = promotedFieldMap.get(fieldValue.getFieldId());
                    if (promotedField == null) {
                        return true;
                    }
                    setPromotedFieldValue(contract, promotedField, fieldValue.getFieldValue());
                    return false;
                })
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private void setPromotedFieldValue(Contract contract, ContractPromotedField promotedField, Object fieldValue) {
        switch (promotedField) {
            case ORDER_STATUS -> contract.setOrderStatus(toStringValue(fieldValue));
            case CURRENCY -> contract.setCurrency(toStringValue(fieldValue));
        }
    }

    private String toStringValue(Object value) {
        return value == null ? null : StringUtils.trimToNull(value.toString());
    }

    private Set<String> getSnapshotPromotedFieldIds(String contractProp) {
        if (StringUtils.isBlank(contractProp)) {
            return Collections.emptySet();
        }
        ModuleFormConfigDTO snapshotFormConfig = JSON.parseObject(contractProp, ModuleFormConfigDTO.class);
        return Optional.ofNullable(snapshotFormConfig)
                .map(ModuleFormConfigDTO::getFields)
                .orElse(List.of())
                .stream()
                .filter(field -> ContractPromotedField.of(
                        field.getId(), field.getInternalKey(), field.getBusinessKey()) != null)
                .map(field -> field.getId())
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toSet());
    }


    /**
     * 更新自定义字段
     *
     * @param moduleFields
     * @param contract
     * @param orgId
     * @param userId
     */
    private void updateFields(List<BaseModuleFieldValue> moduleFields, Contract contract, String orgId, String userId) {
        if (moduleFields == null) {
            return;
        }
        contractFieldService.deleteByResourceId(contract.getId());
        contractFieldService.saveModuleField(contract, orgId, userId, moduleFields, true);
    }


    /**
     * 删除合同
     *
     * @param id 合同ID
     */
    @OperationLog(module = LogModule.CONTRACT_INDEX, type = LogType.DELETE, resourceId = "{#id}")
    public void delete(String id) {
        Contract contract = contractMapper.selectByPrimaryKey(id);
        if (contract == null) {
            throw new GenericException(Translator.get("contract.not.exist"));
        }
        checkContractRelated(id);

        contractFieldService.deleteByResourceId(id);
        contractMapper.deleteByPrimaryKey(id);

        //删除快照
        LambdaQueryWrapper<ContractSnapshot> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ContractSnapshot::getContractId, id);
        snapshotBaseMapper.deleteByLambda(wrapper);
        // 添加日志上下文
        OperationLogContext.setResourceName(contract.getName());
    }


    /**
     * ⚠️反射调用; 勿修改入参, 返回, 方法名!
     *
     * @param id 合同ID
     * @return 合同详情
     */
    public ContractGetResponse getSnapshot(String id) {
        Contract contract = contractMapper.selectByPrimaryKey(id);
        if (contract == null) {
            return null;
        }
        LambdaQueryWrapper<ContractSnapshot> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ContractSnapshot::getContractId, id);
        ContractSnapshot snapshot = snapshotBaseMapper.selectListByLambda(wrapper).stream().findFirst().orElse(null);
        if (snapshot == null) {
            return get(id);
        }

        ContractGetResponse response = JSON.parseObject(snapshot.getContractValue(), ContractGetResponse.class);
        BeanUtils.copyBean(response, contract);
        response.setOwnerName(null);
        response.setCustomerName(null);
        response.setInCustomerPool(null);
        response.setPoolId(null);
        response.setDepartmentId(null);
        response.setDepartmentName(null);
        response = baseService.setCreateUpdateOwnerUserName(response);
        fallbackExternalAuditUserNames(response);
        if (StringUtils.isBlank(contract.getOwner())) {
            response.setOwnerName(null);
        }
        Set<String> promotedFieldIds = getSnapshotPromotedFieldIds(snapshot.getContractProp());
        if (CollectionUtils.isNotEmpty(response.getModuleFields())) {
            response.setModuleFields(response.getModuleFields().stream()
                    .filter(field -> field != null && !promotedFieldIds.contains(field.getFieldId()))
                    .toList());
        }

        Map<String, List<OptionDTO>> optionMap = response.getOptionMap() == null
                ? new HashMap<>() : new HashMap<>(response.getOptionMap());
        optionMap.remove(BusinessModuleField.CONTRACT_OWNER.getBusinessKey());
        optionMap.remove(BusinessModuleField.CONTRACT_CUSTOMER_NAME.getBusinessKey());
        if (StringUtils.isNotBlank(contract.getOwner())) {
            if (isMissingResolvedUserName(response.getOwnerName())) {
                response.setOwnerName(contract.getOwner());
            }
            optionMap.put(BusinessModuleField.CONTRACT_OWNER.getBusinessKey(),
                    moduleFormService.getBusinessFieldOption(response,
                            ContractGetResponse::getOwner, ContractGetResponse::getOwnerName));
        }
        if (StringUtils.isNotBlank(contract.getOwner())) {
            UserDeptDTO userDept = baseService.getUserDeptMapByUserId(contract.getOwner(), contract.getOrganizationId());
            if (userDept != null) {
                response.setDepartmentId(userDept.getDeptId());
                response.setDepartmentName(userDept.getDeptName());
            }
        }
        Customer customer = customerBaseMapper.selectByPrimaryKey(contract.getCustomerId());
        if (customer != null) {
            response.setInCustomerPool(customer.getInSharedPool());
            response.setPoolId(customer.getPoolId());
            response.setCustomerName(customer.getName());
            optionMap.put(BusinessModuleField.CONTRACT_CUSTOMER_NAME.getBusinessKey(),
                    Collections.singletonList(new OptionDTO(customer.getId(), customer.getName())));
        } else if (StringUtils.isNotBlank(contract.getCustomerId())) {
            response.setCustomerName(contract.getCustomerId());
            optionMap.put(BusinessModuleField.CONTRACT_CUSTOMER_NAME.getBusinessKey(),
                    Collections.singletonList(new OptionDTO(contract.getCustomerId(), contract.getCustomerId())));
        }
        applyMlsContractDepartment(List.of(response), contract.getOrganizationId());
        response.setOptionMap(optionMap);
        return response;
    }


    /**
     * 合同列表
     *
     * @param request
     * @param userId
     * @param orgId
     * @param deptDataPermission
     * @return
     */
    public PagerWithOption<List<ContractListResponse>> list(ContractPageRequest request, String userId, String orgId, DeptDataPermissionDTO deptDataPermission, Boolean source) {
        Page<Object> page = PageHelper.startPage(request.getCurrent(), request.getPageSize());
        List<ContractListResponse> list = extContractMapper.list(request, orgId, userId, deptDataPermission, source);
        List<ContractListResponse> results = buildList(list, orgId);
        ModuleFormConfigDTO customerFormConfig = getFormConfig(orgId);
        Map<String, List<OptionDTO>> optionMap = buildOptionMap(list, results, customerFormConfig);

        return PageUtils.setPageInfoWithOption(page, results, optionMap);
    }

    private Map<String, List<OptionDTO>> buildOptionMap(List<ContractListResponse> list, List<ContractListResponse> buildList,
                                                        ModuleFormConfigDTO formConfig) {
        // 获取所有模块字段的值
        List<BaseModuleFieldValue> moduleFieldValues = moduleFormService.getBaseModuleFieldValues(list, ContractListResponse::getModuleFields);
        // 获取选项值对应的 option
        Map<String, List<OptionDTO>> optionMap = moduleFormService.getOptionMap(formConfig, moduleFieldValues);
        // 补充负责人选项
        List<OptionDTO> ownerFieldOption = moduleFormService.getBusinessFieldOption(buildList,
                ContractListResponse::getOwner, ContractListResponse::getOwnerName);
        optionMap.put(BusinessModuleField.CONTRACT_OWNER.getBusinessKey(), ownerFieldOption);
        // 补充客户选项。手动录入客户名称时 customer_id 本身就是显示值。
        List<OptionDTO> customerFieldOption = moduleFormService.getBusinessFieldOption(buildList,
                        ContractListResponse::getCustomerId, ContractListResponse::getCustomerName)
                .stream()
                .filter(option -> StringUtils.isNotBlank(option.getId()) && StringUtils.isNotBlank(option.getName()))
                .toList();
        optionMap.put(BusinessModuleField.CONTRACT_CUSTOMER_NAME.getBusinessKey(), customerFieldOption);
        return optionMap;
    }

    private ModuleFormConfigDTO getFormConfig(String orgId) {
        return moduleFormCacheService.getBusinessFormConfig(FormKey.CONTRACT.getKey(), orgId);
    }

    public List<ContractListResponse> buildList(List<ContractListResponse> list, String orgId) {
        if (CollectionUtils.isEmpty(list)) {
            return list;
        }

        List<String> contractIds = list.stream().map(ContractListResponse::getId)
                .collect(Collectors.toList());
        Map<String, List<BaseModuleFieldValue>> contractFiledMap = contractFieldService.getResourceFieldMap(contractIds, true);
        Map<String, List<BaseModuleFieldValue>> resolvefieldValueMap = contractFieldService.setBusinessRefFieldValue(list, moduleFormService.getFlattenFormFields(FormKey.CONTRACT.getKey(), orgId), contractFiledMap);


        List<String> ownerValues = list.stream()
                .map(ContractListResponse::getOwner)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList();
        Map<String, String> resolvedOwnerIdMap = baseService.resolveUserIdsByIdsOrNames(ownerValues, orgId);
        List<String> ownerIds = resolvedOwnerIdMap.values().stream()
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList();
        List<String> permissionUserIds = list.stream()
                .flatMap(item -> java.util.stream.Stream.of(
                        resolvedOwnerIdMap.getOrDefault(item.getOwner(), item.getOwner()),
                        item.getCreateUser()))
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList();
        Map<String, String> userNameMap = baseService.getUserNameMap(ownerIds);
        Map<String, UserDeptDTO> userDeptMap = baseService.getUserDeptMapByUserIds(permissionUserIds, orgId);

        list.forEach(item -> {
            String resolvedOwnerId = resolvedOwnerIdMap.get(item.getOwner());
            item.setOwnerName(StringUtils.defaultIfBlank(userNameMap.get(resolvedOwnerId), item.getOwner()));
            if (StringUtils.isBlank(item.getCustomerName())) {
                item.setCustomerName(item.getCustomerId());
            }
            UserDeptDTO userDeptDTO = userDeptMap.get(resolvedOwnerId);
            if (userDeptDTO == null) {
                userDeptDTO = userDeptMap.get(item.getCreateUser());
            }
            if (userDeptDTO != null) {
                item.setDepartmentId(userDeptDTO.getDeptId());
                item.setDepartmentName(userDeptDTO.getDeptName());
            }
            // 获取自定义字段
            List<BaseModuleFieldValue> contractFields = resolvefieldValueMap.get(item.getId());
            item.setModuleFields(contractFields);
        });
        List<ContractListResponse> responses = baseService.setCreateAndUpdateUserName(list);
        responses.forEach(this::fallbackExternalAuditUserNames);
        applyMlsContractDepartment(responses, orgId);
        return responses;
    }


    /**
     * 获取表单快照
     *
     * @param id
     * @param orgId
     * @return
     */
    public ModuleFormConfigDTO getFormSnapshot(String id, String orgId) {
        ModuleFormConfigDTO moduleFormConfigDTO = new ModuleFormConfigDTO();
        Contract contract = contractMapper.selectByPrimaryKey(id);
        if (contract == null) {
            throw new GenericException(Translator.get("contract.not.exist"));
        }
        LambdaQueryWrapper<ContractSnapshot> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ContractSnapshot::getContractId, id);
        ContractSnapshot snapshot = snapshotBaseMapper.selectListByLambda(wrapper).stream().findFirst().orElse(null);
        if (snapshot != null) {
            moduleFormConfigDTO = JSON.parseObject(snapshot.getContractProp(), ModuleFormConfigDTO.class);
        } else {
            moduleFormConfigDTO = moduleFormCacheService.getBusinessFormConfig(FormKey.CONTRACT.getKey(), orgId);
        }
        if (CollectionUtils.isNotEmpty(moduleFormConfigDTO.getFields())) {
            moduleFormConfigDTO.getFields().forEach(moduleFormService::setFieldBusinessParam);
        }
        return moduleFormConfigDTO;

    }


    public ResourceTabEnableDTO getTabEnableConfig(String userId, String orgId) {
        List<RolePermissionDTO> rolePermissions = permissionCache.getRolePermissions(userId, orgId);
        return PermissionUtils.getTabEnableConfig(userId, PermissionConstants.CONTRACT_READ, rolePermissions);
    }

    /**
     * 下载导入模板
     *
     * @param response   响应
     * @param currentOrg 当前组织
     */
    public void downloadImportTpl(HttpServletResponse response, String currentOrg) {
        new EasyExcelExporter()
                .exportMultiSheetTplWithSharedHandler(response, moduleFormService.getCustomImportHeadsNoRef(FormKey.CONTRACT.getKey(), currentOrg),
                        Translator.get("contract.import_tpl.name"), Translator.get(SheetKey.DATA), Translator.get(SheetKey.COMMENT),
                        new CustomTemplateWriteHandler(moduleFormService.getAllCustomImportFields(FormKey.CONTRACT.getKey(), currentOrg)),
                        new CustomHeadColWidthStyleStrategy());
    }

    /**
     * 导入检查
     *
     * @param file       导入文件
     * @param currentOrg 当前组织
     * @return 导入检查信息
     */
    public ImportResponse importPreCheck(MultipartFile file, String currentOrg) {
        if (file == null) {
            throw new GenericException(Translator.get("file_cannot_be_null"));
        }
        return checkImportExcel(file, currentOrg);
    }

    /**
     * 合同导入
     *
     * @param file        导入文件
     * @param currentOrg  当前组织
     * @param currentUser 当前用户
     * @return 导入返回信息
     */
    public ImportResponse realImport(MultipartFile file, String currentOrg, String currentUser) {
        if (file == null) {
            throw new GenericException(Translator.get("file_cannot_be_null"));
        }
        try {
            List<BaseField> fields = moduleFormService.getAllFields(FormKey.CONTRACT.getKey(), currentOrg);
            Optional<SerialNumberField> serialOptional = fields.stream()
                    .filter(BaseField::isSerialNumber)
                    .filter(field -> Strings.CI.equals(field.getInternalKey(), BusinessModuleField.CONTRACT_NO.getKey()))
                    .filter(SerialNumberField.class::isInstance)
                    .map(SerialNumberField.class::cast)
                    .findAny();
            ModuleFormConfigDTO moduleFormConfigDTO = getFormConfig(currentOrg);

            CustomImportAfterDoConsumer<Contract, BaseResourceSubField> afterDo = (contracts, contractFields, contractFieldBlobs) -> {
                List<LogDTO> logs = new ArrayList<>();
                contracts.forEach(contract -> {
                    serialOptional.ifPresent(serialField ->
                            contract.setNumber(serialNumGenerator.generateByRules(serialField.getSerialNumberRules(), currentOrg, FormKey.CONTRACT.getKey())));
                    contract.setApprovalStatus(ContractApprovalStatus.NONE.name());
                    if (contract.getAmount() == null) {
                        contract.setAmount(BigDecimal.ZERO);
                    }
                    if (contract.getAmount().compareTo(MAX_AMOUNT) > 0) {
                        throw new GenericException(Translator.get("contract.amount.exceed.max"));
                    }
                    logs.add(new LogDTO(currentOrg, contract.getId(), currentUser, LogType.ADD, LogModule.CONTRACT_INDEX, contract.getName()));
                });

                contractMapper.batchInsert(contracts);
                if (CollectionUtils.isNotEmpty(contractFields)) {
                    contractFieldMapper.batchInsert(contractFields.stream().map(field -> BeanUtils.copyBean(new ContractField(), field)).toList());
                }
                if (CollectionUtils.isNotEmpty(contractFieldBlobs)) {
                    contractFieldBlobMapper.batchInsert(contractFieldBlobs.stream().map(field -> BeanUtils.copyBean(new ContractFieldBlob(), field)).toList());
                }
                logService.batchAdd(logs);

                contracts.forEach(contract -> saveSnapshot(contract, moduleFormConfigDTO, get(contract.getId())));
            };

            CustomFieldImportEventListener<Contract> eventListener = new CustomFieldImportEventListener<>(fields, Contract.class, currentOrg, currentUser,
                    "contract_field", afterDo, 2000, null, null);
            FastExcelFactory.read(file.getInputStream(), eventListener).headRowNumber(1).ignoreEmptyRow(true).sheet().doRead();
            return ImportResponse.builder().errorMessages(eventListener.getErrList())
                    .successCount(eventListener.getSuccessCount()).failCount(eventListener.getErrList().size()).build();
        } catch (Exception e) {
            log.error("contract import error", e);
            throw new GenericException(e.getMessage());
        }
    }

    /**
     * 检查导入文件
     *
     * @param file       导入文件
     * @param currentOrg 当前组织
     * @return 检查信息
     */
    private ImportResponse checkImportExcel(MultipartFile file, String currentOrg) {
        try {
            List<BaseField> fields = moduleFormService.getAllCustomImportFields(FormKey.CONTRACT.getKey(), currentOrg);
            CustomFieldCheckEventListener eventListener = new CustomFieldCheckEventListener(fields, "contract", "contract_field", currentOrg);
            FastExcelFactory.read(file.getInputStream(), eventListener).headRowNumber(1).ignoreEmptyRow(true).sheet().doRead();
            return ImportResponse.builder().errorMessages(eventListener.getErrList())
                    .successCount(eventListener.getSuccess()).failCount(eventListener.getErrList().size()).build();
        } catch (Exception e) {
            log.error("contract import pre-check error", e);
            throw new GenericException(e.getMessage());
        }
    }


    public CustomerContractStatisticResponse calculateContractStatisticByCustomerId(String customerId, String userId, String orgId, DeptDataPermissionDTO deptDataPermission) {
        return extContractMapper.calculateContractStatisticByCustomerId(customerId, userId, orgId, deptDataPermission);
    }


    public String getContractName(String id) {
        Contract contract = contractMapper.selectByPrimaryKey(id);
        return Optional.ofNullable(contract).map(Contract::getName).orElse(null);
    }

    public Contract selectByPrimaryKey(String id) {
        return contractMapper.selectByPrimaryKey(id);
    }

    /**
     * 通过名称获取合同集合
     *
     * @param names 名称集合
     * @return 合同集合
     */
    public List<Contract> getContractListByNames(List<String> names) {
        LambdaQueryWrapper<Contract> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        lambdaQueryWrapper.in(Contract::getName, names);
        return contractMapper.selectListByLambda(lambdaQueryWrapper);
    }

    /**
     * 设置默认的数据源搜索条件
     *
     * @return 搜索条件
     */
    public List<FilterCondition> getDefaultSourceFilters() {
        return Collections.emptyList();
    }

    /**
     * 校验合同是否存在关联数据
     *
     * @param contractId 合同ID
     */
    private void checkContractRelated(String contractId) {
        LambdaQueryWrapper<ContractPaymentRecord> recordWrapper = new LambdaQueryWrapper<>();
        recordWrapper.eq(ContractPaymentRecord::getContractId, contractId);
        List<ContractPaymentRecord> contractPaymentRecords = contractPaymentRecordMapper.selectListByLambda(recordWrapper);
        if (CollectionUtils.isNotEmpty(contractPaymentRecords)) {
            throw new GenericException(Translator.get("contract.has.payment.record"));
        }
        if (extContractInvoiceMapper.hasContractInvoice(contractId)) {
            throw new GenericException(Translator.get("contract.has.invoice.cannot.delete"));
        }
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
    public ContractStatisticResponse searchStatistic(BaseCondition request, String userId, String orgId, DeptDataPermissionDTO deptDataPermission) {
        ContractStatisticResponse response = extContractMapper.searchStatistic(request, orgId, userId, deptDataPermission);
        return Optional.ofNullable(response).orElse(new ContractStatisticResponse());
    }
}
