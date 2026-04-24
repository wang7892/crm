package cn.cordys.crm.integration.webhook.service;

import cn.cordys.common.domain.BaseModuleFieldValue;
import cn.cordys.common.uid.IDGenerator;
import cn.cordys.crm.customer.domain.Customer;
import cn.cordys.crm.customer.domain.CustomerContact;
import cn.cordys.crm.customer.domain.CustomerContactField;
import cn.cordys.crm.customer.domain.CustomerField;
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
import cn.cordys.crm.system.service.ModuleFieldService;
import cn.cordys.mybatis.BaseMapper;
import cn.cordys.mybatis.lambda.LambdaQueryWrapper;
import cn.cordys.security.SessionUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.ArrayList;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@Slf4j
public class EmailWebhookService {
    private static final String CONTACT_EMAIL_INTERNAL_KEY = "contactEmail";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAIL = "FAIL";
    private static final String EMAIL_FOLLOW_METHOD_DEFAULT = "3";
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
    private BaseMapper<CustomerField> customerFieldMapper;
    @Resource
    private BaseMapper<Customer> customerMapper;
    @Resource
    private ModuleFieldService moduleFieldService;
    @Resource
    private FollowUpRecordService followUpRecordService;
    @Resource
    private CustomerContactService customerContactService;
    @Resource
    private BaseMapper<User> userMapper;
    @Resource
    private BaseMapper<FollowUpRecord> followUpRecordMapper;
    @Value("${crm.webhook.follow-method:3}")
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
            Customer customer = findCustomerByMailboxFromCustomerField(request.getMatchedTargetMailbox(), organizationId);
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
                    buildFollowRequest(request, event, persistedAttachmentUrls, customer.getId(), contact == null ? null : contact.getId(), responsibleUserId),
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

    private Customer findCustomerByMailboxFromCustomerField(String mailbox, String organizationId) {
        if (StringUtils.isBlank(mailbox)) {
            return null;
        }
        LambdaQueryWrapper<CustomerField> fieldQuery = new LambdaQueryWrapper<>();
        fieldQuery.eq(CustomerField::getFieldValue, mailbox);
        List<CustomerField> fields = customerFieldMapper.selectListByLambda(fieldQuery);
        if (CollectionUtils.isEmpty(fields)) {
            return null;
        }
        List<String> customerIds = fields.stream()
                .map(CustomerField::getResourceId)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .collect(Collectors.toList());
        for (String customerId : customerIds) {
            Customer customer = customerMapper.selectByPrimaryKey(customerId);
            if (customer != null && StringUtils.equals(customer.getOrganizationId(), organizationId)) {
                return customer;
            }
        }
        return null;
    }

    private FollowUpRecordAddRequest buildFollowRequest(EmailWebhookRequest request, EmailWebhookEvent event,
                                                        List<String> persistedAttachmentUrls,
                                                        String customerId, String contactId, String responsibleUserId) {
        FollowUpRecordAddRequest followRequest = new FollowUpRecordAddRequest();
        followRequest.setType("CUSTOMER");
        followRequest.setCustomerId(customerId);
        followRequest.setContactId(contactId);
        followRequest.setOwner(responsibleUserId);
        followRequest.setFollowMethod(resolveFollowMethod());
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

    private String resolveFollowMethod() {
        if (StringUtils.isBlank(followMethod)) {
            return EMAIL_FOLLOW_METHOD_DEFAULT;
        }
        String method = StringUtils.trim(followMethod);
        if (!method.matches("\\d{1,2}")) {
            log.warn("invalid crm.webhook.follow-method={}, fallback to {}", followMethod, EMAIL_FOLLOW_METHOD_DEFAULT);
            return EMAIL_FOLLOW_METHOD_DEFAULT;
        }
        return method;
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

