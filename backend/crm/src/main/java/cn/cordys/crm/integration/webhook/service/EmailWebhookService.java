package cn.cordys.crm.integration.webhook.service;

import cn.cordys.common.constants.BusinessModuleField;
import cn.cordys.common.constants.FormKey;
import cn.cordys.common.domain.BaseModuleFieldValue;
import cn.cordys.common.uid.IDGenerator;
import cn.cordys.common.util.JSON;
import cn.cordys.crm.customer.domain.Customer;
import cn.cordys.crm.customer.domain.CustomerContact;
import cn.cordys.crm.customer.domain.CustomerContactField;
import cn.cordys.crm.customer.dto.request.CustomerContactAddRequest;
import cn.cordys.crm.customer.service.CustomerContactService;
import cn.cordys.crm.follow.domain.FollowUpRecord;
import cn.cordys.crm.follow.dto.request.FollowUpRecordAddRequest;
import cn.cordys.crm.follow.service.FollowUpRecordService;
import cn.cordys.crm.integration.webhook.domain.EmailWebhookEvent;
import cn.cordys.crm.integration.webhook.domain.EmailWebhookAttachment;
import cn.cordys.crm.integration.webhook.dto.request.EmailWebhookAttachmentRequest;
import cn.cordys.crm.integration.webhook.dto.request.EmailWebhookRequest;
import cn.cordys.crm.integration.webhook.dto.response.EmailWebhookResponse;
import cn.cordys.crm.system.domain.User;
import cn.cordys.crm.system.domain.ModuleField;
import cn.cordys.crm.system.domain.ModuleFieldBlob;
import cn.cordys.crm.system.domain.ModuleForm;
import cn.cordys.crm.system.constants.FieldType;
import cn.cordys.crm.system.dto.field.CheckBoxField;
import cn.cordys.crm.system.dto.field.RadioField;
import cn.cordys.crm.system.dto.field.SelectField;
import cn.cordys.crm.system.dto.field.SelectMultipleField;
import cn.cordys.crm.system.dto.field.base.BaseField;
import cn.cordys.crm.system.dto.field.base.HasOption;
import cn.cordys.crm.system.dto.field.base.OptionProp;
import cn.cordys.crm.system.dto.response.ModuleFormConfigDTO;
import cn.cordys.crm.system.service.ModuleFieldService;
import cn.cordys.crm.system.service.ModuleFormCacheService;
import cn.cordys.mybatis.BaseMapper;
import cn.cordys.mybatis.lambda.LambdaQueryWrapper;
import cn.cordys.security.SessionUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@Slf4j
public class EmailWebhookService {
    private static final String CONTACT_EMAIL_INTERNAL_KEY = "contactEmail";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAIL = "FAIL";
    /** 邮件跟进方式在表单中的选项 value；解析失败时的兜底（与手动创建「邮件」时写入的 ID 一致）。 */
    private static final String EMAIL_FOLLOW_METHOD_DEFAULT = "177667046269900000";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Resource
    private BaseMapper<EmailWebhookEvent> emailWebhookEventMapper;
    @Resource
    private BaseMapper<EmailWebhookAttachment> emailWebhookAttachmentMapper;
    @Resource
    private BaseMapper<CustomerContactField> customerContactFieldMapper;
    @Resource
    private BaseMapper<CustomerContact> customerContactMapper;
    @Resource
    private BaseMapper<Customer> customerMapper;
    @Resource
    private ModuleFieldService moduleFieldService;
    @Resource
    private ModuleFormCacheService moduleFormCacheService;
    @Resource
    private FollowUpRecordService followUpRecordService;
    @Resource
    private CustomerContactService customerContactService;
    @Resource
    private BaseMapper<User> userMapper;
    @Resource
    private BaseMapper<FollowUpRecord> followUpRecordMapper;
    @Resource
    private BaseMapper<ModuleForm> moduleFormMapper;
    @Resource
    private BaseMapper<ModuleField> moduleFieldRowMapper;
    @Resource
    private BaseMapper<ModuleFieldBlob> moduleFieldBlobMapper;
    @Value("${crm.webhook.follow-method:177667046269900000}")
    private String followMethod;
    /**
     * Webhook联调阶段建议先只落库，不自动创建跟进记录。
     * 设置为 true 才会真正调用 FollowUpRecordService.add(...)
     */
    @Value("${crm.webhook.create-follow:true}")
    private boolean createFollow;

    public ResponseEntity<EmailWebhookResponse> handle(EmailWebhookRequest request, String organizationIdHeader, String apiKeyUserId) {
        String organizationId = StringUtils.defaultIfBlank(organizationIdHeader, request.getOrganizationId());
        if (StringUtils.isAnyBlank(organizationId, request.getSourceMailbox(), request.getMessageId(), request.getMatchedTargetMailbox())) {
            return ResponseEntity.badRequest()
                    .body(new EmailWebhookResponse(false, null, "INVALID_PARAM", "organizationId/sourceMailbox/messageId/matchedTargetMailbox are required"));
        }
        // 自动跟进的负责人以 “SOURCE_MAILBOX(被监控邮箱)” 在 sys_user.email 的匹配结果为准
        String responsibleUserId = resolveResponsibleUserId(request.getSourceMailbox(), organizationId);
        // webhook 入库的操作者：优先 apiKey 用户；否则兜底为负责人；再兜底 session 用户
        String userId = StringUtils.defaultIfBlank(apiKeyUserId, StringUtils.defaultIfBlank(responsibleUserId, SessionUtils.getUserId()));

        EmailWebhookEvent duplicate = findDuplicate(organizationId, request.getSourceMailbox(), request.getMessageId());
        if (duplicate != null) {
            return ResponseEntity.ok(new EmailWebhookResponse(true, duplicate.getId(), null, null));
        }

        EmailWebhookEvent event = initEvent(request, organizationId, userId);
        emailWebhookEventMapper.insert(event);
        List<String> persistedAttachmentUrls = saveAttachmentsIfAny(request, event, organizationId, userId);
        try {
            Customer customer = findCustomerByEmail(request.getMatchedTargetMailbox(), organizationId);
            if (customer == null || StringUtils.isBlank(customer.getId())) {
                markFail(event, "customer mailbox not found");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(new EmailWebhookResponse(false, event.getId(), "CUSTOMER_NOT_FOUND", "customer mailbox not found"));
            }
            if (StringUtils.isBlank(responsibleUserId)) {
                markFail(event, "responsible user not found by sourceMailbox");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(new EmailWebhookResponse(false, event.getId(), "UNAUTHORIZED", "responsible user not found"));
            }

            if (!createFollow) {
                // 联调阶段：先确认 webhook 能稳定入库与幂等，再按需开启自动建跟进。
                event.setStatus(STATUS_SUCCESS);
                event.setUpdateTime(System.currentTimeMillis());
                event.setUpdateUser(userId);
                emailWebhookEventMapper.update(event);
                return ResponseEntity.ok(new EmailWebhookResponse(true, event.getId(), null, null));
            }

            CustomerContact contact = ensureCustomerContact(customer, request.getMatchedTargetMailbox(), responsibleUserId, organizationId, userId);
            FollowUpRecord record = followUpRecordService.add(
                    buildFollowRequest(request, event, persistedAttachmentUrls, customer.getId(), contact == null ? null : contact.getId(), responsibleUserId, organizationId),
                    userId,
                    organizationId
            );
            forceSyncAttachmentContentToFollowRecord(record, request, persistedAttachmentUrls, event, userId);
            event.setFollowRecordId(record.getId());
            event.setStatus(STATUS_SUCCESS);
            event.setUpdateTime(System.currentTimeMillis());
            event.setUpdateUser(userId);
            emailWebhookEventMapper.update(event);
            return ResponseEntity.ok(new EmailWebhookResponse(true, event.getId(), null, null));
        } catch (Exception e) {
            log.error("email webhook handle failed, messageId={}, orgId={}", request.getMessageId(), organizationId, e);
            markFail(event, e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(new EmailWebhookResponse(false, event.getId(), "INTERNAL_ERROR", "internal server error"));
        }
    }

    private List<String> saveAttachmentsIfAny(EmailWebhookRequest request, EmailWebhookEvent event, String organizationId, String userId) {
        List<EmailWebhookAttachmentRequest> attachments = request.getAttachments();
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }
        long now = System.currentTimeMillis();

        List<EmailWebhookAttachment> entities = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        for (EmailWebhookAttachmentRequest a : attachments) {
            if (a == null) {
                continue;
            }
            if (StringUtils.isAllBlank(a.getFileName(), a.getDownloadUrl())) {
                continue;
            }
            EmailWebhookAttachment entity = new EmailWebhookAttachment();
            entity.setId(IDGenerator.nextStr());
            entity.setEventId(event.getId());
            entity.setOrganizationId(organizationId);
            entity.setFileName(StringUtils.defaultString(a.getFileName()));
            entity.setContentType(StringUtils.defaultIfBlank(a.getContentType(), "application/octet-stream"));
            entity.setSizeBytes(a.getSizeBytes() == null ? 0L : Math.max(a.getSizeBytes(), 0L));
            entity.setDownloadUrl(StringUtils.defaultString(a.getDownloadUrl()));
            entity.setCreateTime(now);
            entity.setUpdateTime(now);
            entity.setCreateUser(userId);
            entity.setUpdateUser(userId);
            entities.add(entity);
            if (StringUtils.isNotBlank(entity.getDownloadUrl())) {
                urls.add(entity.getDownloadUrl().trim());
            }
        }
        if (!entities.isEmpty()) {
            emailWebhookAttachmentMapper.batchInsert(entities);
        }
        return urls.stream().distinct().collect(Collectors.toList());
    }

    private EmailWebhookEvent findDuplicate(String organizationId, String sourceMailbox, String messageId) {
        LambdaQueryWrapper<EmailWebhookEvent> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(EmailWebhookEvent::getOrganizationId, organizationId);
        queryWrapper.eq(EmailWebhookEvent::getSourceMailbox, sourceMailbox);
        queryWrapper.eq(EmailWebhookEvent::getMessageId, messageId);
        List<EmailWebhookEvent> list = emailWebhookEventMapper.selectListByLambda(queryWrapper);
        return list == null || list.isEmpty() ? null : list.get(0);
    }

    private EmailWebhookEvent initEvent(EmailWebhookRequest request, String organizationId, String userId) {
        long now = System.currentTimeMillis();
        EmailWebhookEvent event = new EmailWebhookEvent();
        event.setId(IDGenerator.nextStr());
        event.setOrganizationId(organizationId);
        event.setSourceMailbox(request.getSourceMailbox());
        event.setMessageId(request.getMessageId());
        event.setThreadId(request.getThreadId());
        event.setFromAddress(request.getFromAddress());
        event.setMatchedTargetMailbox(request.getMatchedTargetMailbox());
        event.setSubject(StringUtils.left(StringUtils.defaultString(request.getSubject()), 512));
        event.setContentText(StringUtils.defaultString(request.getContentText()));
        event.setToAddresses(toJsonSafely(request.getToAddresses()));
        event.setCcAddresses(toJsonSafely(request.getCcAddresses()));
        event.setStatus("PENDING");
        event.setCreateTime(now);
        event.setUpdateTime(now);
        event.setCreateUser(userId);
        event.setUpdateUser(userId);
        return event;
    }

    private String toJsonSafely(Object value) {
        if (value == null) {
            return "[]";
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            // Fallback to string to keep something rather than failing the webhook.
            return String.valueOf(value);
        }
    }

    private void markFail(EmailWebhookEvent event, String message) {
        event.setStatus(STATUS_FAIL);
        event.setErrorMessage(StringUtils.left(StringUtils.defaultString(message), 1000));
        event.setUpdateTime(System.currentTimeMillis());
        if (StringUtils.isBlank(event.getUpdateUser())) {
            event.setUpdateUser(SessionUtils.getUserId());
        }
        emailWebhookEventMapper.update(event);
    }

    private CustomerContact findContactByMailbox(String mailbox, String organizationId) {
        ModuleField emailField = moduleFieldService.selectFieldByInternalKey(CONTACT_EMAIL_INTERNAL_KEY);
        if (emailField == null || StringUtils.isBlank(emailField.getId())) {
            return null;
        }
        LambdaQueryWrapper<CustomerContactField> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(CustomerContactField::getFieldId, emailField.getId());
        queryWrapper.eq(CustomerContactField::getFieldValue, mailbox);
        List<CustomerContactField> fields = customerContactFieldMapper.selectListByLambda(queryWrapper);
        if (CollectionUtils.isEmpty(fields)) {
            return null;
        }
        List<String> contactIds = fields.stream().map(CustomerContactField::getResourceId).distinct().collect(Collectors.toList());
        for (String contactId : contactIds) {
            CustomerContact contact = customerContactMapper.selectByPrimaryKey(contactId);
            if (contact != null && StringUtils.equals(contact.getOrganizationId(), organizationId) && Boolean.TRUE.equals(contact.getEnable())) {
                return contact;
            }
        }
        return null;
    }

    private Customer findCustomerByEmail(String mailbox, String organizationId) {
        String customerEmail = StringUtils.trimToEmpty(mailbox);
        if (StringUtils.isAnyBlank(customerEmail, organizationId)) {
            return null;
        }
        LambdaQueryWrapper<Customer> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Customer::getEmail, customerEmail);
        queryWrapper.eq(Customer::getOrganizationId, organizationId);
        List<Customer> customers = customerMapper.selectListByLambda(queryWrapper);
        return CollectionUtils.isEmpty(customers) ? null : customers.get(0);
    }

    private FollowUpRecordAddRequest buildFollowRequest(EmailWebhookRequest request, EmailWebhookEvent event,
                                                        List<String> persistedAttachmentUrls,
                                                        String customerId, String contactId, String responsibleUserId,
                                                        String organizationId) {
        FollowUpRecordAddRequest followRequest = new FollowUpRecordAddRequest();
        followRequest.setType("CUSTOMER");
        followRequest.setCustomerId(customerId);
        followRequest.setContactId(contactId);
        followRequest.setOwner(responsibleUserId);
        followRequest.setFollowMethod(resolveFollowMethod(organizationId));
        followRequest.setFollowTime(request.getSendTime() == null ? System.currentTimeMillis() : request.getSendTime());

        // 按需求：跟进内容直接取邮件正文(contentText)；如为空则兜底空串
        followRequest.setContent(StringUtils.left(buildFollowContentWithAttachments(request, event, persistedAttachmentUrls), 3000));
        return followRequest;
    }

    private void forceSyncAttachmentContentToFollowRecord(FollowUpRecord record, EmailWebhookRequest request,
                                                          List<String> persistedAttachmentUrls, EmailWebhookEvent event,
                                                          String userId) {
        if (record == null || StringUtils.isBlank(record.getId())) {
            return;
        }
        String expectedContent = StringUtils.left(buildFollowContentWithAttachments(request, event, persistedAttachmentUrls), 3000);
        if (StringUtils.equals(expectedContent, record.getContent())) {
            return;
        }
        FollowUpRecord update = new FollowUpRecord();
        update.setId(record.getId());
        update.setContent(expectedContent);
        update.setUpdateTime(System.currentTimeMillis());
        update.setUpdateUser(userId);
        followUpRecordMapper.update(update);
    }

    private String buildFollowContentWithAttachments(EmailWebhookRequest request, EmailWebhookEvent event, List<String> persistedAttachmentUrls) {
        String baseContent = StringUtils.defaultString(request.getContentText());
        List<String> urls = new ArrayList<>();
        if (!CollectionUtils.isEmpty(persistedAttachmentUrls)) {
            urls.addAll(persistedAttachmentUrls.stream().filter(StringUtils::isNotBlank).map(String::trim).toList());
        }
        if (urls.isEmpty() && event != null && StringUtils.isNotBlank(event.getId())) {
            urls.addAll(queryAttachmentUrlsFromDb(event.getId()));
        }

        StringBuilder attachmentLines = new StringBuilder();
        for (String url : urls) {
            if (StringUtils.isBlank(url)) {
                continue;
            }
            if (attachmentLines.length() > 0) {
                attachmentLines.append("\n");
            }
            attachmentLines.append("附件：").append(url.trim());
        }
        if (attachmentLines.length() == 0) {
            return baseContent;
        }
        if (StringUtils.isBlank(baseContent)) {
            return attachmentLines.toString();
        }
        return baseContent + "\n" + attachmentLines;
    }

    private List<String> queryAttachmentUrlsFromDb(String eventId) {
        LambdaQueryWrapper<EmailWebhookAttachment> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(EmailWebhookAttachment::getEventId, eventId);
        List<EmailWebhookAttachment> list = emailWebhookAttachmentMapper.selectListByLambda(queryWrapper);
        if (CollectionUtils.isEmpty(list)) {
            return List.of();
        }
        return list.stream()
                .map(EmailWebhookAttachment::getDownloadUrl)
                .filter(StringUtils::isNotBlank)
                .map(String::trim)
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * 跟进方式存的是表单选项的 value（与前端 optionMap 的 id 一致）。优先从当前组织「跟进记录」表单解析「邮件」；
     * 解析失败则使用 EMAIL_FOLLOW_METHOD_DEFAULT；亦可通过 crm.webhook.follow-method 覆盖（匹配选项 value 或 label）。
     * <p>
     * 注意：表单 JSON 里可能仍保留旧版「邮件」选项 value=3，与前端实际使用的长 ID 不一致；若直接按 label 命中会写回 3，
     * 导致前端「选项不存在」。因此对 1~2 位数字的配置与选项 value 一律不作为最终写入值，优先长数字 ID。
     */
    private String resolveFollowMethod(String organizationId) {
        List<OptionProp> options = loadFollowMethodOptions(organizationId);
        if (CollectionUtils.isEmpty(options)) {
            log.warn("follow method field has no options, orgId={}, fallback={}", organizationId, EMAIL_FOLLOW_METHOD_DEFAULT);
            return EMAIL_FOLLOW_METHOD_DEFAULT;
        }
        String configured = StringUtils.trimToEmpty(followMethod);
        // 仅当配置为「非旧版短数字」时，才按配置精确匹配（避免 crm.webhook.follow-method=3 或默认历史值写回 3）
        if (StringUtils.isNotBlank(configured) && !isLegacyShortOptionValue(configured)) {
            for (OptionProp opt : options) {
                if (opt == null) {
                    continue;
                }
                if (configured.equals(StringUtils.trimToEmpty(opt.getValue()))) {
                    return opt.getValue();
                }
            }
            for (OptionProp opt : options) {
                if (opt == null) {
                    continue;
                }
                if (configured.equalsIgnoreCase(StringUtils.trimToEmpty(opt.getLabel()))) {
                    return opt.getValue();
                }
            }
            log.warn("crm.webhook.follow-method={} not found in form options, orgId={}, will resolve by 邮件 label", configured, organizationId);
        }
        String picked = pickEmailFollowMethodFromOptions(options);
        if (picked != null) {
            return picked;
        }
        log.warn("email follow method option 邮件 not resolved to a non-legacy id, orgId={}, fallback={}", organizationId, EMAIL_FOLLOW_METHOD_DEFAULT);
        return EMAIL_FOLLOW_METHOD_DEFAULT;
    }

    private static boolean isLegacyShortOptionValue(String value) {
        String v = StringUtils.trimToEmpty(value);
        return v.matches("\\d{1,2}");
    }

    /**
     * 在「邮件」相关 label 的选项中：优先与兜底常量一致的长 ID，否则优先长数字 value，避免选用旧版 1~2 位 value。
     */
    private String pickEmailFollowMethodFromOptions(List<OptionProp> options) {
        List<OptionProp> mailRelated = new ArrayList<>();
        for (OptionProp opt : options) {
            if (opt == null) {
                continue;
            }
            String lb = StringUtils.trimToEmpty(opt.getLabel());
            if (lb.isEmpty()) {
                continue;
            }
            if ("邮件".equalsIgnoreCase(lb) || "email".equalsIgnoreCase(lb) || StringUtils.contains(lb, "邮件")) {
                mailRelated.add(opt);
            }
        }
        if (mailRelated.isEmpty()) {
            return null;
        }
        for (OptionProp o : mailRelated) {
            if (EMAIL_FOLLOW_METHOD_DEFAULT.equals(StringUtils.trimToEmpty(o.getValue()))) {
                return o.getValue();
            }
        }
        for (OptionProp o : mailRelated) {
            String v = StringUtils.trimToEmpty(o.getValue());
            if (v.matches("\\d{12,}")) {
                return v;
            }
        }
        for (OptionProp o : mailRelated) {
            String v = StringUtils.trimToEmpty(o.getValue());
            if (!isLegacyShortOptionValue(v)) {
                return v;
            }
        }
        return null;
    }

    /**
     * 从库中读取「跟进记录」表单里 recordMethod 字段的原始 JSON 并解析选项。
     * 原因：ModuleFormService#getAllFields 使用 JSON.parseObject(..., BaseField.class) 时，子类多态可能未带上 options，
     * instanceof HasOption 不成立时会走兜底 ID。按字段 type 反序列化为具体类型可稳定拿到选项 value。
     */
    private List<OptionProp> loadFollowMethodOptions(String organizationId) {
        if (StringUtils.isBlank(organizationId)) {
            return List.of();
        }
        List<OptionProp> fromBlob = loadFollowMethodOptionsFromFieldBlob(organizationId);
        if (CollectionUtils.isNotEmpty(fromBlob)) {
            return fromBlob;
        }
        try {
            ModuleFormConfigDTO config = moduleFormCacheService.getBusinessFormConfig(FormKey.FOLLOW_RECORD.getKey(), organizationId);
            if (config == null || CollectionUtils.isEmpty(config.getFields())) {
                return List.of();
            }
            for (BaseField field : config.getFields()) {
                if (field == null || !Strings.CS.equals(BusinessModuleField.FOLLOW_METHOD.getKey(), field.getInternalKey())) {
                    continue;
                }
                if (field instanceof HasOption hasOption) {
                    return mergeOptionProps(hasOption);
                }
            }
        } catch (Exception e) {
            log.warn("load follow method options failed, orgId={}, err={}", organizationId, e.getMessage());
        }
        return List.of();
    }

    private static final String OPTION_SOURCE_CUSTOM = "custom";
    private static final int OPTION_REF_MAX_DEPTH = 8;

    private List<OptionProp> loadFollowMethodOptionsFromFieldBlob(String organizationId) {
        try {
            LambdaQueryWrapper<ModuleForm> formQuery = new LambdaQueryWrapper<>();
            formQuery.eq(ModuleForm::getFormKey, FormKey.FOLLOW_RECORD.getKey());
            formQuery.eq(ModuleForm::getOrganizationId, organizationId);
            List<ModuleForm> forms = moduleFormMapper.selectListByLambda(formQuery);
            if (CollectionUtils.isEmpty(forms)) {
                return List.of();
            }
            ModuleForm form = forms.getFirst();
            LambdaQueryWrapper<ModuleField> fieldQuery = new LambdaQueryWrapper<>();
            fieldQuery.eq(ModuleField::getFormId, form.getId());
            fieldQuery.eq(ModuleField::getInternalKey, BusinessModuleField.FOLLOW_METHOD.getKey());
            List<ModuleField> fieldRows = moduleFieldRowMapper.selectListByLambda(fieldQuery);
            if (CollectionUtils.isEmpty(fieldRows)) {
                return List.of();
            }
            ModuleField row = fieldRows.getFirst();
            ModuleFieldBlob blob = moduleFieldBlobMapper.selectByPrimaryKey(row.getId());
            if (blob == null || StringUtils.isBlank(blob.getProp())) {
                return List.of();
            }
            List<OptionProp> direct = loadOptionsFromFieldBlobContent(blob.getProp(), row.getType(), 0);
            if (CollectionUtils.isNotEmpty(direct)) {
                return direct;
            }
            log.warn("follow method field blob produced no options, orgId={}, fieldId={}, type={}",
                    organizationId, row.getId(), row.getType());
        } catch (Exception e) {
            log.warn("load follow method options from field blob failed, orgId={}, err={}", organizationId, e.getMessage());
        }
        return List.of();
    }

    /**
     * 按 sys_module_field.type 将 blob 反序列化为具体字段类型并合并 options/customOptions；
     * 若选项来自「引用其他字段」(optionSource!=custom 且 refId 有值)，则继续读取被引用字段的 blob。
     */
    private List<OptionProp> loadOptionsFromFieldBlobContent(String prop, String type, int depth) {
        if (depth > OPTION_REF_MAX_DEPTH || StringUtils.isBlank(prop)) {
            return List.of();
        }
        String t = StringUtils.trimToEmpty(type);
        if (Strings.CI.equals(t, FieldType.SELECT.name())) {
            SelectField parsed = JSON.parseObject(prop, SelectField.class);
            List<OptionProp> merged = mergeOptionProps(parsed);
            if (CollectionUtils.isNotEmpty(merged)) {
                return merged;
            }
            return followOptionRefIfNeeded(parsed.getOptionSource(), parsed.getRefId(), depth);
        }
        if (Strings.CI.equals(t, FieldType.SELECT_MULTIPLE.name())) {
            SelectMultipleField parsed = JSON.parseObject(prop, SelectMultipleField.class);
            List<OptionProp> merged = mergeOptionProps(parsed);
            if (CollectionUtils.isNotEmpty(merged)) {
                return merged;
            }
            return followOptionRefIfNeeded(parsed.getOptionSource(), parsed.getRefId(), depth);
        }
        if (Strings.CI.equals(t, FieldType.RADIO.name())) {
            RadioField parsed = JSON.parseObject(prop, RadioField.class);
            List<OptionProp> merged = mergeOptionProps(parsed);
            if (CollectionUtils.isNotEmpty(merged)) {
                return merged;
            }
            return followOptionRefIfNeeded(parsed.getOptionSource(), parsed.getRefId(), depth);
        }
        if (Strings.CI.equals(t, FieldType.CHECKBOX.name())) {
            CheckBoxField parsed = JSON.parseObject(prop, CheckBoxField.class);
            List<OptionProp> merged = mergeOptionProps(parsed);
            if (CollectionUtils.isNotEmpty(merged)) {
                return merged;
            }
            return followOptionRefIfNeeded(parsed.getOptionSource(), parsed.getRefId(), depth);
        }
        return List.of();
    }

    private List<OptionProp> followOptionRefIfNeeded(String optionSource, String refId, int depth) {
        if (StringUtils.isBlank(refId)) {
            return List.of();
        }
        if (StringUtils.isBlank(optionSource) || Strings.CI.equals(optionSource, OPTION_SOURCE_CUSTOM)) {
            return List.of();
        }
        ModuleField refRow = moduleFieldRowMapper.selectByPrimaryKey(refId);
        ModuleFieldBlob refBlob = moduleFieldBlobMapper.selectByPrimaryKey(refId);
        if (refRow == null || refBlob == null || StringUtils.isBlank(refBlob.getProp())) {
            return List.of();
        }
        return loadOptionsFromFieldBlobContent(refBlob.getProp(), refRow.getType(), depth + 1);
    }

    private List<OptionProp> mergeOptionProps(HasOption field) {
        List<OptionProp> list = new ArrayList<>();
        if (field.getOptions() != null) {
            list.addAll(field.getOptions());
        }
        if (field.getCustomOptions() != null) {
            list.addAll(field.getCustomOptions());
        }
        return list;
    }

    private String resolveResponsibleUserId(String sourceMailbox, String organizationId) {
        if (StringUtils.isBlank(sourceMailbox)) {
            return null;
        }
        LambdaQueryWrapper<User> qw = new LambdaQueryWrapper<>();
        qw.eq(User::getEmail, sourceMailbox);
        List<User> users = userMapper.selectListByLambda(qw);
        if (CollectionUtils.isEmpty(users)) {
            return null;
        }
        // 优先 lastOrganizationId 命中当前组织
        for (User u : users) {
            if (u != null && StringUtils.equals(organizationId, u.getLastOrganizationId())) {
                return u.getId();
            }
        }
        return users.stream().filter(Objects::nonNull).map(User::getId).filter(StringUtils::isNotBlank).findFirst().orElse(null);
    }

    private CustomerContact ensureCustomerContact(Customer customer, String customerMailbox, String responsibleUserId, String organizationId, String operatorUserId) {
        if (customer == null || StringUtils.isBlank(customer.getId())) {
            return null;
        }
        // 优先按邮箱复用已存在联系人，避免重复创建触发唯一性校验。
        CustomerContact existedByMailbox = findContactByMailbox(customerMailbox, organizationId);
        if (existedByMailbox != null && StringUtils.equals(existedByMailbox.getCustomerId(), customer.getId())) {
            return existedByMailbox;
        }
        // 先按 “联系人姓名 == 客户名称” 的规则匹配
        LambdaQueryWrapper<CustomerContact> qw = new LambdaQueryWrapper<>();
        qw.eq(CustomerContact::getCustomerId, customer.getId());
        qw.eq(CustomerContact::getOrganizationId, organizationId);
        qw.eq(CustomerContact::getName, customer.getName());
        List<CustomerContact> contacts = customerContactMapper.selectListByLambda(qw);
        if (!CollectionUtils.isEmpty(contacts)) {
            for (CustomerContact c : contacts) {
                if (c != null && Boolean.TRUE.equals(c.getEnable())) {
                    return c;
                }
            }
            return contacts.get(0);
        }

        // 不存在则自动创建联系人：姓名=客户名，邮箱=客户邮箱，负责人=匹配到的联系人(员工)
        CustomerContactAddRequest addRequest = new CustomerContactAddRequest();
        addRequest.setCustomerId(customer.getId());
        addRequest.setName(StringUtils.defaultIfBlank(customer.getName(), "未知联系人"));
        addRequest.setPhone(generateUniquePlaceholderPhone());
        addRequest.setOwner(responsibleUserId);

        ModuleField emailField = moduleFieldService.selectFieldByInternalKey(CONTACT_EMAIL_INTERNAL_KEY);
        if (emailField != null && StringUtils.isNotBlank(emailField.getId()) && StringUtils.isNotBlank(customerMailbox)) {
            addRequest.setModuleFields(List.of(new BaseModuleFieldValue(emailField.getId(), customerMailbox)));
        }
        return customerContactService.add(addRequest, operatorUserId, organizationId);
    }

    private String generateUniquePlaceholderPhone() {
        String millis = String.valueOf(System.currentTimeMillis());
        String suffix = StringUtils.right(millis, 9);
        return "19" + StringUtils.leftPad(suffix, 9, '0');
    }
}

