package cn.cordys.crm.integration.wecom.ingestion.service;

import cn.cordys.common.constants.BusinessModuleField;
import cn.cordys.common.constants.FormKey;
import cn.cordys.common.domain.BaseModuleFieldValue;
import cn.cordys.common.exception.GenericException;
import cn.cordys.common.pager.PagerWithOption;
import cn.cordys.context.OrganizationContext;
import cn.cordys.crm.customer.domain.Customer;
import cn.cordys.crm.customer.domain.CustomerContact;
import cn.cordys.crm.customer.domain.CustomerField;
import cn.cordys.crm.customer.domain.CustomerFieldBlob;
import cn.cordys.crm.customer.dto.request.CustomerContactAddRequest;
import cn.cordys.crm.customer.service.CustomerContactService;
import cn.cordys.crm.follow.domain.FollowUpRecord;
import cn.cordys.crm.follow.dto.request.FollowUpRecordAddRequest;
import cn.cordys.crm.follow.service.FollowUpRecordService;
import cn.cordys.crm.integration.wecom.ingestion.domain.WecomIngestionEvent;
import cn.cordys.crm.integration.wecom.ingestion.domain.WecomIngestionSessionDay;
import cn.cordys.crm.integration.wecom.ingestion.dto.request.WecomIngestionDeleteSessionRequest;
import cn.cordys.crm.integration.wecom.ingestion.dto.request.WecomIngestionMessagesPageRequest;
import cn.cordys.crm.integration.wecom.ingestion.dto.request.WecomIngestionSessionPageRequest;
import cn.cordys.crm.integration.wecom.ingestion.dto.request.WecomIngestionSyncFollowRequest;
import cn.cordys.crm.integration.wecom.ingestion.dto.response.WecomIngestionMessageRowResponse;
import cn.cordys.crm.integration.wecom.ingestion.dto.response.WecomIngestionSessionResponse;
import cn.cordys.crm.integration.wecom.ingestion.dto.response.WecomIngestionSessionRow;
import cn.cordys.crm.integration.wecom.ingestion.mapper.ExtWecomIngestionMapper;
import cn.cordys.crm.integration.wecom.ingestion.support.WecomIngestionMatchSupport;
import cn.cordys.crm.system.domain.ModuleField;
import cn.cordys.crm.system.domain.OrganizationUser;
import cn.cordys.crm.system.domain.User;
import cn.cordys.crm.system.dto.field.base.BaseField;
import cn.cordys.crm.system.dto.field.base.HasOption;
import cn.cordys.crm.system.dto.field.base.OptionProp;
import cn.cordys.crm.system.dto.response.ModuleFormConfigDTO;
import cn.cordys.crm.system.service.ModuleFieldService;
import cn.cordys.crm.system.service.ModuleFormCacheService;
import cn.cordys.mybatis.BaseMapper;
import cn.cordys.mybatis.lambda.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
public class WecomIngestionService {

    private static final String CONTACT_EMAIL_INTERNAL_KEY = "contactEmail";
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAIL = "FAIL";
    private static final String AUTO_OPERATOR_LABEL = "wecom-auto";
    private static final String MSG_TYPE_REVOKE = "revoke";
    private static final String PLACEHOLDER_EMAIL = "1111@163.com";
    private static final String LEGACY_WECHAT_FOLLOW_METHOD = "4";
    private static final ZoneId BEIJING_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter FOLLOW_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(BEIJING_ZONE);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Resource
    private BaseMapper<WecomIngestionSessionDay> wecomIngestionSessionDayMapper;
    @Resource
    private ExtWecomIngestionMapper extWecomIngestionMapper;
    @Resource
    private BaseMapper<User> userMapper;
    @Resource
    private BaseMapper<CustomerField> customerFieldMapper;
    @Resource
    private BaseMapper<CustomerFieldBlob> customerFieldBlobMapper;
    @Resource
    private BaseMapper<Customer> customerMapper;
    @Resource
    private BaseMapper<CustomerContact> customerContactMapper;
    @Resource
    private BaseMapper<OrganizationUser> organizationUserMapper;
    @Resource
    private FollowUpRecordService followUpRecordService;
    @Resource
    private CustomerContactService customerContactService;
    @Resource
    private ModuleFieldService moduleFieldService;
    @Resource
    private ModuleFormCacheService moduleFormCacheService;

    private WecomIngestionService self;

    @Autowired
    public void setWecomIngestionServiceSelf(@Lazy WecomIngestionService wecomIngestionServiceSelf) {
        this.self = wecomIngestionServiceSelf;
    }

    @Value("${crm.wecom.customer-external-field-internal-key:}")
    private String customerExternalFieldInternalKey;

    @Value("${crm.wecom.customer-external-field-id:}")
    private String customerExternalFieldId;

    @Value("${crm.wecom.follow-method:}")
    private String configuredWecomFollowMethod;

    @Value("${crm.wecom.auto-create-follow:true}")
    private boolean autoCreateFollow;

    @Value("${crm.wecom.auto-follow-batch-size:50}")
    private int autoFollowBatchSize;

    @Value("${crm.wecom.auto-follow-current-day:true}")
    private boolean autoFollowCurrentDay;

    public PagerWithOption<List<WecomIngestionSessionResponse>> pageSessions(WecomIngestionSessionPageRequest request, String orgId) {
        long total = extWecomIngestionMapper.countSessions(orgId);
        int offset = (request.getCurrent() - 1) * request.getPageSize();
        List<WecomIngestionSessionRow> rows = extWecomIngestionMapper.listSessions(orgId, offset, request.getPageSize());
        List<WecomIngestionSessionResponse> list = new ArrayList<>();
        for (WecomIngestionSessionRow row : rows) {
            WecomIngestionSessionResponse vo = new WecomIngestionSessionResponse();
            vo.setSessionKey(row.getSessionKey());
            vo.setChatType(row.getChatType());
            vo.setRoomid(row.getRoomid());
            vo.setLastSendTime(row.getLastSendTime());
            WecomIngestionSessionDay day = wecomIngestionSessionDayMapper.selectByPrimaryKey(row.getSessionKey());
            if (day != null) {
                vo.setChatDate(day.getChatDate());
                vo.setChatType(day.getChatType());
                vo.setRoomid(day.getRoomid());
                vo.setMessageCount(day.getMessageCount());
                vo.setStatus(day.getStatus());
                vo.setFollowRecordId(day.getFollowRecordId());
                vo.setLastPreview(StringUtils.abbreviate(StringUtils.defaultString(day.getMergedContent()), 120));
            }
            WecomIngestionEvent latest = findLatestEvent(orgId, row.getSessionKey());
            if (latest != null) {
                if (StringUtils.isBlank(vo.getLastPreview())) {
                    vo.setLastPreview(StringUtils.abbreviate(StringUtils.defaultString(latest.getContentText()), 120));
                }
                vo.setWecomCustomerExternalUserid(WecomIngestionMatchSupport.customerExternalUserid(latest));
                vo.setWecomStaffUserid(WecomIngestionMatchSupport.specialistWecomUserid(latest));
                vo.setMatchRuleSummary(WecomIngestionMatchSupport.matchRuleSummary(latest));
                List<FollowTarget> targets = resolvePreviewTargets(List.of(latest), orgId);
                vo.setMatchedCustomerName(summarizeCustomers(targets));
                vo.setMatchedStaffName(summarizeOwners(targets));
            }
            list.add(vo);
        }
        PagerWithOption<List<WecomIngestionSessionResponse>> pager = new PagerWithOption<>();
        pager.setList(list);
        pager.setTotal(total);
        pager.setPageSize(request.getPageSize());
        pager.setCurrent(request.getCurrent());
        return pager;
    }

    public PagerWithOption<List<WecomIngestionMessageRowResponse>> pageMessages(WecomIngestionMessagesPageRequest request, String orgId) {
        long total = extWecomIngestionMapper.countMessagesBySession(orgId, request.getSessionKey());
        int offset = (request.getCurrent() - 1) * request.getPageSize();
        List<WecomIngestionEvent> events = extWecomIngestionMapper.listMessagesBySession(orgId, request.getSessionKey(), offset, request.getPageSize());
        List<WecomIngestionMessageRowResponse> rows = new ArrayList<>();
        for (WecomIngestionEvent e : events) {
            rows.add(toMessageRow(e, orgId));
        }
        PagerWithOption<List<WecomIngestionMessageRowResponse>> pager = new PagerWithOption<>();
        pager.setList(rows);
        pager.setTotal(total);
        pager.setPageSize(request.getPageSize());
        pager.setCurrent(request.getCurrent());
        return pager;
    }

    public void consumePendingAutoFollow() {
        if (!autoCreateFollow) {
            return;
        }
        int limit = Math.min(Math.max(autoFollowBatchSize, 1), 200);
        List<String> pendingIds = extWecomIngestionMapper.listPendingAutoFollowIds(limit, autoFollowCurrentDay);
        if (CollectionUtils.isEmpty(pendingIds)) {
            return;
        }
        if (self == null) {
            log.error("wecom auto-follow: lazy self-proxy is null, cannot run transactional processing");
            return;
        }
        for (String pendingId : pendingIds) {
            String sessionDayId = StringUtils.trimToNull(pendingId);
            if (sessionDayId == null) {
                log.warn("wecom auto-follow skip blank pending sessionDayId");
                continue;
            }
            try {
                self.autoFollowSingleEvent(sessionDayId);
            } catch (Exception ex) {
                log.warn("wecom auto-follow skip sessionDayId={}, err={}", sessionDayId, ex.getMessage());
            }
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void autoFollowSingleEvent(String sessionDayId) {
        if (StringUtils.isBlank(sessionDayId)) {
            return;
        }
        WecomIngestionSessionDay fresh = wecomIngestionSessionDayMapper.selectByPrimaryKey(sessionDayId);
        if (fresh == null || !STATUS_PENDING.equals(StringUtils.trimToEmpty(fresh.getStatus()))
                || StringUtils.isNotBlank(fresh.getFollowRecordId())) {
            return;
        }
        String orgId = fresh.getOrganizationId();
        String previousOrgId = OrganizationContext.getOrganizationId();
        try {
            OrganizationContext.setOrganizationId(orgId);
            List<WecomIngestionEvent> messages = extWecomIngestionMapper.listUnsyncedMessagesBySession(orgId, fresh.getId(), 0, 1000);
            if (CollectionUtils.isEmpty(messages)) {
                throw new GenericException("NO_MESSAGES_IN_DAILY_SESSION");
            }
            List<AppliedFollowGroup> appliedGroups = applyFollowFromEvents(messages, orgId, null, null, true);
            FollowUpRecord first = appliedGroups.getFirst().record();
            String operatorUserId = StringUtils.defaultIfBlank(first.getOwner(), AUTO_OPERATOR_LABEL);
            for (AppliedFollowGroup group : appliedGroups) {
                markEventsSuccess(group.events(), group.record(), operatorUserId);
            }
            log.info("wecom auto-follow created {} record(s), firstFollowRecordId={}, sessionDayId={}, orgId={}",
                    appliedGroups.size(), first.getId(), sessionDayId, orgId);
        } catch (GenericException ge) {
            markEventFail(fresh, ge.getMessage());
        } catch (Exception ex) {
            log.error("wecom auto-follow failed sessionDayId={}, orgId={}", sessionDayId, orgId, ex);
            markEventFail(fresh, ex.getMessage());
        } finally {
            if (StringUtils.isNotBlank(previousOrgId)) {
                OrganizationContext.setOrganizationId(previousOrgId);
            } else {
                OrganizationContext.clear();
            }
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public FollowUpRecord syncFollow(WecomIngestionSyncFollowRequest request, String orgId, String operatorUserId) {
        if (CollectionUtils.isEmpty(request.getEventIds())) {
            throw new GenericException("eventIds required");
        }
        List<WecomIngestionEvent> events = extWecomIngestionMapper.listMessagesByIds(orgId, request.getEventIds());
        if (events.size() != request.getEventIds().size()) {
            throw new GenericException("部分消息不存在或无权限");
        }
        for (WecomIngestionEvent e : events) {
            if (StringUtils.isNotBlank(e.getFollowRecordId())) {
                throw new GenericException("所属日会话已同步过跟进: " + e.getSessionDayId());
            }
        }
        events.sort(Comparator.comparing(WecomIngestionEvent::getSendTime, Comparator.nullsLast(Long::compareTo)));
        List<AppliedFollowGroup> appliedGroups = applyFollowFromEvents(events, orgId, request.getCustomerId(), request.getOwnerUserId(), false);
        FollowUpRecord first = appliedGroups.getFirst().record();
        for (AppliedFollowGroup group : appliedGroups) {
            markEventsSuccess(group.events(), group.record(), operatorUserId);
        }
        return first;
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteSession(WecomIngestionDeleteSessionRequest request, String orgId) {
        if (request == null || StringUtils.isBlank(request.getSessionKey())) {
            throw new GenericException("sessionKey required");
        }
        String sessionDayId = request.getSessionKey().trim();
        WecomIngestionSessionDay day = wecomIngestionSessionDayMapper.selectByPrimaryKey(sessionDayId);
        if (day == null || !StringUtils.equals(day.getOrganizationId(), orgId)) {
            throw new GenericException("会话不存在或无权限删除");
        }
        extWecomIngestionMapper.deleteMediaBySession(orgId, sessionDayId);
        extWecomIngestionMapper.deleteMessageFollowRecordsBySession(orgId, sessionDayId);
        extWecomIngestionMapper.deleteMessagesBySession(orgId, sessionDayId);
        extWecomIngestionMapper.deleteSessionDay(orgId, sessionDayId);
    }

    private List<AppliedFollowGroup> applyFollowFromEvents(List<WecomIngestionEvent> events, String orgId, String customerIdOverride,
                                                           String ownerUserIdOverride, boolean autoMode) {
        if (CollectionUtils.isEmpty(events)) {
            throw new GenericException("无事件");
        }
        events = events.stream().filter(this::isFollowableMessage).collect(Collectors.toCollection(ArrayList::new));
        if (CollectionUtils.isEmpty(events)) {
            throw new GenericException("NO_FOLLOWABLE_WECOM_MESSAGES");
        }
        events.sort(Comparator.comparing(WecomIngestionEvent::getSendTime, Comparator.nullsLast(Long::compareTo)));
        List<TargetEventGroup> groups = resolveFollowEventGroups(events, orgId, customerIdOverride, ownerUserIdOverride, autoMode);
        if (CollectionUtils.isEmpty(groups)) {
            throw new GenericException(autoMode ? "WECOM_ROOM_TARGET_NOT_MATCHED" : "未匹配到可同步的客户和负责人");
        }

        List<AppliedFollowGroup> appliedGroups = new ArrayList<>();
        for (TargetEventGroup group : groups) {
            FollowTarget target = group.target();
            List<WecomIngestionEvent> groupEvents = group.events();
            String content = buildMergedContent(groupEvents, target);
            Long followTime = groupEvents.stream().map(WecomIngestionEvent::getSendTime).filter(Objects::nonNull)
                    .max(Long::compareTo).orElse(System.currentTimeMillis());
            CustomerContact contact = ensureCustomerContact(target.customer, target.owner.getId(), orgId, target.owner.getId());
            FollowUpRecordAddRequest add = new FollowUpRecordAddRequest();
            add.setType("CUSTOMER");
            add.setCustomerId(target.customer.getId());
            add.setContactId(contact == null ? null : contact.getId());
            add.setOwner(target.owner.getId());
            add.setFollowMethod(resolveWechatFollowMethod(orgId));
            add.setFollowTime(followTime);
            add.setContent(StringUtils.left(content, 3000));
            FollowUpRecord record = followUpRecordService.add(add, target.owner.getId(), orgId);
            appliedGroups.add(new AppliedFollowGroup(record, groupEvents));
        }
        return appliedGroups;
    }

    private List<TargetEventGroup> resolveFollowEventGroups(List<WecomIngestionEvent> events, String orgId,
                                                            String customerIdOverride, String ownerUserIdOverride,
                                                            boolean autoMode) {
        if (StringUtils.isNotBlank(customerIdOverride) || StringUtils.isNotBlank(ownerUserIdOverride)) {
            List<FollowTarget> targets = resolveOverrideTarget(events, orgId, customerIdOverride, ownerUserIdOverride, autoMode);
            if (CollectionUtils.isEmpty(targets)) {
                return List.of();
            }
            return List.of(new TargetEventGroup(targets.getFirst(), events));
        }
        Map<String, TargetEventGroup> groups = new LinkedHashMap<>();
        for (WecomIngestionEvent event : events) {
            List<FollowTarget> eventTargets = isRoom(event)
                    ? resolveRoomTargets(event, orgId)
                    : resolveSingleTarget(event, orgId);
            for (FollowTarget target : eventTargets) {
                TargetEventGroup group = groups.computeIfAbsent(target.key(),
                        key -> new TargetEventGroup(target, new ArrayList<>()));
                group.events().add(event);
            }
        }
        return new ArrayList<>(groups.values());
    }

    private List<FollowTarget> resolveFollowTargets(List<WecomIngestionEvent> events, String orgId, String customerIdOverride,
                                                    String ownerUserIdOverride, boolean autoMode) {
        if (StringUtils.isNotBlank(customerIdOverride) || StringUtils.isNotBlank(ownerUserIdOverride)) {
            return resolveOverrideTarget(events, orgId, customerIdOverride, ownerUserIdOverride, autoMode);
        }
        Map<String, FollowTarget> targets = new LinkedHashMap<>();
        for (WecomIngestionEvent event : events) {
            List<FollowTarget> eventTargets = isRoom(event)
                    ? resolveRoomTargets(event, orgId)
                    : resolveSingleTarget(event, orgId);
            for (FollowTarget target : eventTargets) {
                targets.putIfAbsent(target.key(), target);
            }
        }
        return new ArrayList<>(targets.values());
    }

    private List<FollowTarget> resolveOverrideTarget(List<WecomIngestionEvent> events, String orgId, String customerIdOverride,
                                                     String ownerUserIdOverride, boolean autoMode) {
        List<FollowTarget> autoTargets = resolveFollowTargets(events, orgId, null, null, autoMode);
        Customer customer = null;
        User owner = null;
        if (StringUtils.isNotBlank(customerIdOverride)) {
            customer = customerMapper.selectByPrimaryKey(customerIdOverride);
        } else if (CollectionUtils.isNotEmpty(autoTargets)) {
            customer = autoTargets.getFirst().customer;
        }
        if (StringUtils.isNotBlank(ownerUserIdOverride)) {
            owner = userMapper.selectByPrimaryKey(ownerUserIdOverride);
        } else if (CollectionUtils.isNotEmpty(autoTargets)) {
            owner = autoTargets.getFirst().owner;
        } else if (customer != null && StringUtils.isNotBlank(customer.getOwner())) {
            owner = userMapper.selectByPrimaryKey(customer.getOwner());
        }
        if (customer == null || !StringUtils.equals(customer.getOrganizationId(), orgId)) {
            throw new GenericException(autoMode ? "CUSTOMER_NOT_MATCHED" : "未匹配到 CRM 客户，请手动选择客户后再同步");
        }
        if (owner == null || !hasOneEnabledOrgMembership(owner, orgId)) {
            throw new GenericException(autoMode ? "STAFF_NOT_MATCHED" : "未匹配到 CRM 负责人，请手动指定负责人");
        }
        return List.of(new FollowTarget(customer, owner));
    }

    private List<FollowTarget> resolveSingleTarget(WecomIngestionEvent event, String orgId) {
        String ext = WecomIngestionMatchSupport.customerExternalUserid(event);
        String staffWecom = WecomIngestionMatchSupport.specialistWecomUserid(event);
        Customer customer = resolveCustomer(ext, orgId);
        User owner = resolveStaff(staffWecom, orgId);
        if (!isCustomerOwner(customer, owner)) {
            return List.of();
        }
        return List.of(new FollowTarget(customer, owner));
    }

    private List<FollowTarget> resolveRoomTargets(WecomIngestionEvent event, String orgId) {
        String roomid = resolveRoomid(event);
        if (StringUtils.isBlank(roomid)) {
            return List.of();
        }
        RoomParticipants participants = loadRoomParticipants(roomid, orgId);
        if (isInbound(event)) {
            return resolveInboundRoomTargets(event, orgId, participants, roomid);
        }
        return resolveOutboundRoomTargets(event, orgId, participants, roomid);
    }

    private List<FollowTarget> resolveOutboundRoomTargets(WecomIngestionEvent event, String orgId, RoomParticipants participants,
                                                          String roomid) {
        User staff = resolveStaff(WecomIngestionMatchSupport.specialistWecomUserid(event), orgId);
        if (staff == null || !participants.staffById.containsKey(staff.getId())) {
            return List.of();
        }
        Set<String> externalUserids = resolveRoomExternalUserids(event);
        if (CollectionUtils.isEmpty(externalUserids)) {
            return List.of();
        }
        Map<String, FollowTarget> targets = new LinkedHashMap<>();
        for (String externalUserid : externalUserids) {
            Customer customer = resolveCustomer(externalUserid, orgId);
            if (customer == null || !roomContains(customer.getRoomid(), roomid)
                    || !StringUtils.equals(customer.getOwner(), staff.getId())) {
                continue;
            }
            FollowTarget target = new FollowTarget(customer, staff);
            targets.putIfAbsent(target.key(), target);
        }
        return new ArrayList<>(targets.values());
    }

    private List<FollowTarget> resolveInboundRoomTargets(WecomIngestionEvent event, String orgId, RoomParticipants participants, String roomid) {
        Customer customer = resolveCustomer(WecomIngestionMatchSupport.customerExternalUserid(event), orgId);
        if (customer == null || !roomContains(customer.getRoomid(), roomid)) {
            return List.of();
        }
        User owner = StringUtils.isBlank(customer.getOwner()) ? null : participants.staffById.get(customer.getOwner());
        if (owner == null) {
            return List.of();
        }
        return List.of(new FollowTarget(customer, owner));
    }

    private boolean isCustomerOwner(Customer customer, User owner) {
        return customer != null
                && owner != null
                && StringUtils.equals(StringUtils.trimToEmpty(customer.getOwner()), StringUtils.trimToEmpty(owner.getId()));
    }

    private List<FollowTarget> resolvePreviewTargets(List<WecomIngestionEvent> events, String orgId) {
        try {
            return resolveFollowTargets(events, orgId, null, null, false);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private RoomParticipants loadRoomParticipants(String roomid, String orgId) {
        RoomParticipants participants = new RoomParticipants();
        LambdaQueryWrapper<Customer> cq = new LambdaQueryWrapper<>();
        cq.eq(Customer::getOrganizationId, orgId);
        List<Customer> customers = customerMapper.selectListByLambda(cq);
        participants.customers = customers.stream()
                .filter(customer -> roomContains(customer.getRoomid(), roomid))
                .toList();

        LambdaQueryWrapper<OrganizationUser> oq = new LambdaQueryWrapper<>();
        oq.eq(OrganizationUser::getOrganizationId, orgId);
        oq.eq(OrganizationUser::getEnable, true);
        List<OrganizationUser> orgUsers = organizationUserMapper.selectListByLambda(oq);
        List<String> userIds = orgUsers.stream().map(OrganizationUser::getUserId).filter(StringUtils::isNotBlank).distinct().toList();
        List<User> users = CollectionUtils.isEmpty(userIds) ? List.of() : userMapper.selectByIds(userIds);
        participants.staff = users.stream()
                .filter(user -> roomContains(user.getRoomid(), roomid))
                .toList();
        participants.staffById = participants.staff.stream()
                .collect(Collectors.toMap(User::getId, user -> user, (a, b) -> a, LinkedHashMap::new));
        return participants;
    }

    private boolean roomContains(String roomidValue, String roomid) {
        if (StringUtils.isBlank(roomidValue) || StringUtils.isBlank(roomid)) {
            return false;
        }
        return Arrays.stream(StringUtils.split(roomidValue, ",;；， \n\r\t"))
                .map(StringUtils::trimToEmpty)
                .anyMatch(item -> StringUtils.equals(item, roomid));
    }

    private Set<String> resolveRoomExternalUserids(WecomIngestionEvent event) {
        LinkedHashSet<String> externalUserids = new LinkedHashSet<>();
        if (event == null) {
            return externalUserids;
        }
        addExternalUserid(externalUserids, event.getMatchedExternalUserid());
        addExternalUserid(externalUserids, event.getExternalUserid());
        addExternalUserid(externalUserids, event.getSenderExternalUserid());
        collectRoomSnapshotExternalUserids(event.getExtraJson(), externalUserids);
        return externalUserids;
    }

    private void collectRoomSnapshotExternalUserids(String extraJson, Set<String> externalUserids) {
        if (StringUtils.isBlank(extraJson)) {
            return;
        }
        try {
            JsonNode root = JSON.readTree(extraJson);
            JsonNode snapshot = root.path("room_snapshot");
            collectArrayExternalUserids(snapshot.path("external_userids"), externalUserids, true);
            collectArrayExternalUserids(snapshot.path("participants"), externalUserids, false);
            collectArrayExternalUserids(snapshot.path("tolist"), externalUserids, false);

            JsonNode payload = root.path("payload");
            addExternalUserid(externalUserids, textValue(payload.path("external_userid")));
            addExternalUserid(externalUserids, textValue(payload.path("matched_external_userid")));
            addExternalUserid(externalUserids, textValue(payload.path("sender_external_userid")));
            collectArrayExternalUserids(payload.path("tolist"), externalUserids, false);
        } catch (Exception ex) {
            log.debug("parse wecom room snapshot failed: {}", ex.getMessage());
        }
    }

    private void collectArrayExternalUserids(JsonNode node, Set<String> externalUserids, boolean trustArray) {
        if (node == null || !node.isArray()) {
            return;
        }
        for (JsonNode item : node) {
            String value = textValue(item);
            if (trustArray || isLikelyExternalUserid(value)) {
                addExternalUserid(externalUserids, value);
            }
        }
    }

    private String textValue(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : StringUtils.trimToNull(node.asText());
    }

    private void addExternalUserid(Set<String> externalUserids, String value) {
        String externalUserid = StringUtils.trimToNull(value);
        if (externalUserid != null && isLikelyExternalUserid(externalUserid)) {
            externalUserids.add(externalUserid);
        }
    }

    private boolean isLikelyExternalUserid(String value) {
        String userid = StringUtils.trimToEmpty(value);
        return StringUtils.startsWith(userid, "wm")
                || StringUtils.startsWith(userid, "wo")
                || StringUtils.startsWith(userid, "external_");
    }

    private String resolveRoomid(WecomIngestionEvent event) {
        if (event == null) {
            return null;
        }
        String roomid = StringUtils.trimToNull(event.getRoomid());
        if (StringUtils.isNotBlank(roomid)) {
            return roomid;
        }
        if (StringUtils.isNotBlank(event.getSessionDayId())) {
            WecomIngestionSessionDay day = wecomIngestionSessionDayMapper.selectByPrimaryKey(event.getSessionDayId());
            if (day != null) {
                return StringUtils.defaultIfBlank(day.getRoomid(), roomidFromSessionKey(day.getSessionKey()));
            }
        }
        return null;
    }

    private boolean isRoom(WecomIngestionEvent event) {
        return event != null && (StringUtils.isNotBlank(event.getRoomid())
                || "room".equalsIgnoreCase(StringUtils.trimToEmpty(event.getChatType())));
    }

    private boolean isInbound(WecomIngestionEvent event) {
        return "INBOUND".equalsIgnoreCase(StringUtils.trimToEmpty(event == null ? null : event.getMessageDirection()));
    }

    private boolean isFollowableMessage(WecomIngestionEvent event) {
        return event != null && !MSG_TYPE_REVOKE.equalsIgnoreCase(StringUtils.trimToEmpty(event.getMsgType()));
    }

    private String roomidFromSessionKey(String sessionKey) {
        String value = StringUtils.trimToEmpty(sessionKey);
        return StringUtils.startsWith(value, "room:") ? StringUtils.trimToNull(value.substring("room:".length())) : null;
    }

    private String summarizeCustomers(List<FollowTarget> targets) {
        if (CollectionUtils.isEmpty(targets)) {
            return null;
        }
        return targets.stream().map(t -> t.customer.getName()).filter(StringUtils::isNotBlank).distinct().collect(Collectors.joining("、"));
    }

    private String summarizeOwners(List<FollowTarget> targets) {
        if (CollectionUtils.isEmpty(targets)) {
            return null;
        }
        return targets.stream().map(t -> t.owner.getName()).filter(StringUtils::isNotBlank).distinct().collect(Collectors.joining("、"));
    }

    private void markEventsSuccess(List<WecomIngestionEvent> events, FollowUpRecord record, String operatorUserId) {
        Set<String> sessionDayIds = new LinkedHashSet<>();
        Set<String> messageIdSet = new LinkedHashSet<>();
        for (WecomIngestionEvent e : events) {
            if (e != null && StringUtils.isNotBlank(e.getId())) {
                messageIdSet.add(e.getId());
            }
            if (e != null && StringUtils.isNotBlank(e.getSessionDayId())) {
                sessionDayIds.add(e.getSessionDayId());
            }
        }
        List<String> messageIds = new ArrayList<>(messageIdSet);
        if (CollectionUtils.isEmpty(messageIds)) {
            return;
        }
        long now = System.currentTimeMillis();
        String organizationId = events.stream()
                .map(WecomIngestionEvent::getOrganizationId)
                .filter(StringUtils::isNotBlank)
                .findFirst()
                .orElse(null);
        if (StringUtils.isBlank(organizationId)) {
            return;
        }
        extWecomIngestionMapper.insertMessageFollowRecords(organizationId, messageIds, record.getId(), operatorUserId, now);
        extWecomIngestionMapper.markMessagesSuccess(organizationId, messageIds, record.getId(), operatorUserId, now);
        for (String sessionDayId : sessionDayIds) {
            extWecomIngestionMapper.refreshSessionDaySyncState(organizationId, sessionDayId, record.getId(), operatorUserId, now);
        }
    }

    private void markEventFail(WecomIngestionSessionDay e, String message) {
        if (e == null || StringUtils.isBlank(e.getId())) {
            return;
        }
        WecomIngestionSessionDay upd = wecomIngestionSessionDayMapper.selectByPrimaryKey(e.getId());
        if (upd == null || !STATUS_PENDING.equals(StringUtils.trimToEmpty(upd.getStatus()))) {
            return;
        }
        upd.setStatus(STATUS_FAIL);
        upd.setErrorMessage(StringUtils.left(StringUtils.defaultString(message), 1000));
        upd.setUpdateTime(System.currentTimeMillis());
        upd.setUpdateUser(AUTO_OPERATOR_LABEL);
        wecomIngestionSessionDayMapper.update(upd);
    }

    private String buildMergedContent(List<WecomIngestionEvent> events, FollowTarget target) {
        StringBuilder sb = new StringBuilder();
        for (WecomIngestionEvent e : events) {
            if (sb.length() > 0) {
                sb.append("\n---\n");
            }
            String time = e.getSendTime() == null ? "" : FOLLOW_TIME_FORMATTER.format(Instant.ofEpochMilli(e.getSendTime()));
            sb.append("[").append(time).append("] ");
            sb.append(senderLabel(e, target)).append(" ");
            sb.append(messageTypeLabel(e.getMsgType())).append("\n");
            sb.append(StringUtils.defaultString(e.getContentText()));
            if (StringUtils.isBlank(e.getContentText()) && StringUtils.isNotBlank(e.getMsgType())) {
                sb.append("（非文本消息，类型：").append(messageTypeLabel(e.getMsgType())).append("）");
            }
        }
        return sb.toString();
    }

    private String senderLabel(WecomIngestionEvent e, FollowTarget target) {
        if (isInbound(e)) {
            return StringUtils.defaultIfBlank(target == null || target.customer == null ? null : target.customer.getName(), "客户") + "发来";
        }
        return StringUtils.defaultIfBlank(target == null || target.owner == null ? null : target.owner.getName(), "联系专员") + "发出";
    }

    private String messageTypeLabel(String msgType) {
        return switch (StringUtils.trimToEmpty(msgType).toLowerCase()) {
            case "text" -> "文本";
            case "image" -> "图片";
            case "voice" -> "语音";
            case "video" -> "视频";
            case "file" -> "文件";
            case "emotion" -> "表情";
            case "redpacket" -> "红包";
            case "voiptext", "meeting_voice_call", "voice_call" -> "语音通话";
            case "voip_doc_share", "meeting_video_call", "video_call" -> "视频通话";
            default -> StringUtils.defaultIfBlank(msgType, "消息");
        };
    }

    private WecomIngestionMessageRowResponse toMessageRow(WecomIngestionEvent e, String orgId) {
        WecomIngestionMessageRowResponse r = new WecomIngestionMessageRowResponse();
        r.setId(e.getId());
        r.setMessageDirection(e.getMessageDirection());
        r.setMsgType(e.getMsgType());
        r.setChatType(e.getChatType());
        r.setRoomid(e.getRoomid());
        r.setContentText(e.getContentText());
        r.setSendTime(e.getSendTime());
        r.setWecomCustomerExternalUserid(WecomIngestionMatchSupport.customerExternalUserid(e));
        r.setWecomStaffUserid(WecomIngestionMatchSupport.specialistWecomUserid(e));
        r.setMatchRuleSummary(WecomIngestionMatchSupport.matchRuleSummary(e));
        List<FollowTarget> targets = resolvePreviewTargets(List.of(e), orgId);
        r.setMatchedCustomerName(summarizeCustomers(targets));
        r.setMatchedStaffName(summarizeOwners(targets));
        r.setSynced(StringUtils.isNotBlank(e.getFollowRecordId()));
        r.setFollowRecordId(e.getFollowRecordId());
        return r;
    }

    private WecomIngestionEvent findLatestEvent(String orgId, String sessionKey) {
        List<WecomIngestionEvent> list = extWecomIngestionMapper.listMessagesBySession(orgId, sessionKey, 0, 1);
        return CollectionUtils.isEmpty(list) ? null : list.getFirst();
    }

    private User resolveStaff(String wecomUserid, String orgId) {
        if (StringUtils.isBlank(wecomUserid) || "_".equals(wecomUserid)) {
            return null;
        }
        LambdaQueryWrapper<User> qw = new LambdaQueryWrapper<>();
        qw.eq(User::getWecomId, wecomUserid);
        List<User> users = userMapper.selectListByLambda(qw);
        if (CollectionUtils.isEmpty(users)) {
            return null;
        }
        List<User> sameOrgUsers = users.stream()
                .filter(u -> hasOneEnabledOrgMembership(u, orgId))
                .toList();
        return sameOrgUsers.size() == 1 ? sameOrgUsers.getFirst() : null;
    }

    private boolean hasOneEnabledOrgMembership(User user, String orgId) {
        if (user == null || StringUtils.isBlank(user.getId()) || StringUtils.isBlank(orgId)) {
            return false;
        }
        LambdaQueryWrapper<OrganizationUser> oq = new LambdaQueryWrapper<>();
        oq.eq(OrganizationUser::getUserId, user.getId());
        oq.eq(OrganizationUser::getOrganizationId, orgId);
        oq.eq(OrganizationUser::getEnable, true);
        List<OrganizationUser> memberships = organizationUserMapper.selectListByLambda(oq);
        return memberships != null && memberships.size() == 1;
    }

    private Customer resolveCustomer(String externalUserid, String orgId) {
        if (StringUtils.isBlank(externalUserid) || "_".equals(externalUserid)) {
            return null;
        }
        LambdaQueryWrapper<Customer> cq = new LambdaQueryWrapper<>();
        cq.eq(Customer::getWecomExternalId, externalUserid);
        cq.eq(Customer::getOrganizationId, orgId);
        List<Customer> directMatches = customerMapper.selectListByLambda(cq);
        if (CollectionUtils.isNotEmpty(directMatches)) {
            return directMatches.size() == 1 ? directMatches.getFirst() : null;
        }
        String fieldId = resolveCustomerExternalFieldId();
        LambdaQueryWrapper<CustomerField> fq = new LambdaQueryWrapper<>();
        fq.eq(CustomerField::getFieldValue, externalUserid);
        if (StringUtils.isNotBlank(fieldId)) {
            fq.eq(CustomerField::getFieldId, fieldId);
        }
        List<CustomerField> fields = customerFieldMapper.selectListByLambda(fq);
        if (CollectionUtils.isEmpty(fields)) {
            return null;
        }
        List<String> ids = fields.stream().map(CustomerField::getResourceId).filter(StringUtils::isNotBlank).distinct().collect(Collectors.toList());
        Customer matched = null;
        for (String customerId : ids) {
            Customer c = customerMapper.selectByPrimaryKey(customerId);
            if (c != null && StringUtils.equals(c.getOrganizationId(), orgId)) {
                if (matched != null && !StringUtils.equals(matched.getId(), c.getId())) {
                    return null;
                }
                matched = c;
            }
        }
        return matched;
    }

    private String resolveCustomerExternalFieldId() {
        if (StringUtils.isNotBlank(customerExternalFieldId)) {
            return customerExternalFieldId.trim();
        }
        if (StringUtils.isBlank(customerExternalFieldInternalKey)) {
            return null;
        }
        ModuleField field = moduleFieldService.selectFieldByInternalKey(customerExternalFieldInternalKey.trim());
        return field == null ? null : field.getId();
    }

    private CustomerContact ensureCustomerContact(Customer customer, String ownerUserId, String orgId, String operatorUserId) {
        if (customer == null) {
            return null;
        }
        LambdaQueryWrapper<CustomerContact> qw = new LambdaQueryWrapper<>();
        qw.eq(CustomerContact::getCustomerId, customer.getId());
        qw.eq(CustomerContact::getOrganizationId, orgId);
        qw.eq(CustomerContact::getName, customer.getName());
        List<CustomerContact> contacts = customerContactMapper.selectListByLambda(qw);
        if (CollectionUtils.isNotEmpty(contacts)) {
            for (CustomerContact c : contacts) {
                if (c != null && Boolean.TRUE.equals(c.getEnable())) {
                    return c;
                }
            }
            return contacts.getFirst();
        }
        CustomerContactAddRequest addRequest = new CustomerContactAddRequest();
        addRequest.setCustomerId(customer.getId());
        addRequest.setName(StringUtils.defaultIfBlank(customer.getName(), "未知联系人"));
        addRequest.setPhone(generateUniquePlaceholderPhone());
        addRequest.setOwner(ownerUserId);
        ModuleField emailField = moduleFieldService.selectFieldByInternalKey(CONTACT_EMAIL_INTERNAL_KEY);
        String customerEmail = resolveCustomerEmail(customer, orgId);
        if (emailField != null && StringUtils.isNotBlank(emailField.getId())) {
            addRequest.setModuleFields(List.of(new BaseModuleFieldValue(emailField.getId(),
                    StringUtils.defaultIfBlank(customerEmail, PLACEHOLDER_EMAIL))));
        }
        return customerContactService.add(addRequest, operatorUserId, orgId);
    }

    private String resolveCustomerEmail(Customer customer, String orgId) {
        if (customer == null || StringUtils.isBlank(customer.getId())) {
            return null;
        }
        String emailFieldId = resolveCustomerEmailFieldId(orgId);
        if (StringUtils.isBlank(emailFieldId)) {
            return null;
        }
        LambdaQueryWrapper<CustomerField> qw = new LambdaQueryWrapper<>();
        qw.eq(CustomerField::getResourceId, customer.getId());
        qw.eq(CustomerField::getFieldId, emailFieldId);
        List<CustomerField> fields = customerFieldMapper.selectListByLambda(qw);
        for (CustomerField field : fields) {
            String value = field == null ? null : fieldValueToString(field.getFieldValue());
            if (StringUtils.isNotBlank(value) && StringUtils.contains(value, "@")) {
                return value.trim();
            }
        }
        LambdaQueryWrapper<CustomerFieldBlob> blobQw = new LambdaQueryWrapper<>();
        blobQw.eq(CustomerFieldBlob::getResourceId, customer.getId());
        blobQw.eq(CustomerFieldBlob::getFieldId, emailFieldId);
        List<CustomerFieldBlob> blobs = customerFieldBlobMapper.selectListByLambda(blobQw);
        for (CustomerFieldBlob blob : blobs) {
            String value = blob == null ? null : fieldValueToString(blob.getFieldValue());
            if (StringUtils.isNotBlank(value) && StringUtils.contains(value, "@")) {
                return value.trim();
            }
        }
        return null;
    }

    private String fieldValueToString(Object fieldValue) {
        if (fieldValue == null) {
            return null;
        }
        if (fieldValue instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return String.valueOf(fieldValue);
    }

    private String resolveCustomerEmailFieldId(String orgId) {
        try {
            ModuleFormConfigDTO config = moduleFormCacheService.getBusinessFormConfig(FormKey.CUSTOMER.getKey(), orgId);
            if (config == null || CollectionUtils.isEmpty(config.getFields())) {
                return null;
            }
            for (BaseField field : config.getFields()) {
                if (field == null) {
                    continue;
                }
                String name = StringUtils.defaultString(field.getName()).toLowerCase();
                String internalKey = StringUtils.defaultString(field.getInternalKey()).toLowerCase();
                String businessKey = StringUtils.defaultString(field.getBusinessKey()).toLowerCase();
                if (StringUtils.contains(name, "邮箱") || StringUtils.contains(name, "email")
                        || StringUtils.contains(internalKey, "email") || StringUtils.contains(businessKey, "email")) {
                    return field.getId();
                }
            }
        } catch (Exception e) {
            log.warn("resolve customer email field failed, orgId={}, err={}", orgId, e.getMessage());
        }
        return null;
    }

    private String generateUniquePlaceholderPhone() {
        String millis = String.valueOf(System.currentTimeMillis());
        String suffix = org.apache.commons.lang3.StringUtils.right(millis, 9);
        return "19" + org.apache.commons.lang3.StringUtils.leftPad(suffix, 9, '0');
    }

    private String resolveWechatFollowMethod(String organizationId) {
        String configured = StringUtils.trimToEmpty(configuredWecomFollowMethod);
        if (StringUtils.isNotBlank(configured) && !configured.matches("\\d{1,2}")) {
            return configured;
        }
        List<OptionProp> options = loadFollowMethodOptions(organizationId);
        if (CollectionUtils.isEmpty(options)) {
            return LEGACY_WECHAT_FOLLOW_METHOD;
        }
        for (OptionProp opt : options) {
            if (opt == null) {
                continue;
            }
            String label = StringUtils.trimToEmpty(opt.getLabel());
            if ("微信".equals(label) || StringUtils.contains(label, "微信")) {
                return opt.getValue();
            }
        }
        return LEGACY_WECHAT_FOLLOW_METHOD;
    }

    private List<OptionProp> loadFollowMethodOptions(String organizationId) {
        if (StringUtils.isBlank(organizationId)) {
            return List.of();
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
                    List<OptionProp> list = new ArrayList<>();
                    if (hasOption.getOptions() != null) {
                        list.addAll(hasOption.getOptions());
                    }
                    if (hasOption.getCustomOptions() != null) {
                        list.addAll(hasOption.getCustomOptions());
                    }
                    return list;
                }
            }
        } catch (Exception e) {
            log.warn("load wecom follow method options failed, orgId={}, err={}", organizationId, e.getMessage());
        }
        return List.of();
    }

    private static class RoomParticipants {
        private List<User> staff = List.of();
        private List<Customer> customers = List.of();
        private Map<String, User> staffById = Map.of();
    }

    private record TargetEventGroup(FollowTarget target, List<WecomIngestionEvent> events) {
    }

    private record AppliedFollowGroup(FollowUpRecord record, List<WecomIngestionEvent> events) {
    }

    private record FollowTarget(Customer customer, User owner) {
        private String key() {
            return customer.getId() + ":" + owner.getId();
        }
    }
}
