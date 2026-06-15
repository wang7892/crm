package cn.cordys.crm.aiagent.service;

import cn.cordys.common.constants.InternalUserView;
import cn.cordys.common.constants.PermissionConstants;
import cn.cordys.common.dto.DeptDataPermissionDTO;
import cn.cordys.common.service.DataScopeService;
import cn.cordys.context.OrganizationContext;
import cn.cordys.crm.aiagent.config.AiAgentLlmProperties;
import cn.cordys.crm.aiagent.domain.AiAgentMessage;
import cn.cordys.crm.aiagent.domain.AiAgentSession;
import cn.cordys.crm.aiagent.dto.AiAgentContext;
import cn.cordys.crm.aiagent.dto.AiAgentTimeWindow;
import cn.cordys.crm.aiagent.dto.ParsedAiAgentQuestion;
import cn.cordys.crm.aiagent.dto.internal.AiAgentCommunicationRow;
import cn.cordys.crm.aiagent.dto.internal.AiAgentCustomerRow;
import cn.cordys.crm.aiagent.dto.internal.AiAgentFollowRecordRow;
import cn.cordys.crm.aiagent.dto.internal.AiAgentUnansweredQuestionRow;
import cn.cordys.crm.aiagent.dto.internal.ExternalOrderQueryResult;
import cn.cordys.crm.aiagent.dto.internal.ExternalOrderRow;
import cn.cordys.crm.aiagent.dto.query.AiAgentQueryFilter;
import cn.cordys.crm.aiagent.dto.query.AiAgentQueryMetric;
import cn.cordys.crm.aiagent.dto.query.AiAgentQueryOrder;
import cn.cordys.crm.aiagent.dto.query.AiAgentQueryPlan;
import cn.cordys.crm.aiagent.dto.request.AiAgentChatRequest;
import cn.cordys.crm.aiagent.dto.response.AiAgentChatResponse;
import cn.cordys.crm.aiagent.dto.response.AiAgentCitationDTO;
import cn.cordys.crm.aiagent.dto.response.AiAgentToolCallDTO;
import cn.cordys.crm.aiagent.mapper.AiAgentInternalMapper;
import cn.cordys.crm.aiagent.tool.CommunicationTools;
import cn.cordys.crm.aiagent.tool.CustomerTools;
import cn.cordys.crm.aiagent.tool.ExternalOrderTools;
import cn.cordys.security.SessionUtils;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Transactional(rollbackFor = Exception.class)
public class AiAgentChatService {

    private static final Logger log = LoggerFactory.getLogger(AiAgentChatService.class);
    private static final ZoneId AGENT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int STALE_FOLLOW_DAYS = 30;
    private static final int LOW_COMMUNICATION_COUNT = 2;
    private static final Map<String, String> CONTRACT_FIELD_LABELS = Map.ofEntries(
            Map.entry("id", "合同ID"),
            Map.entry("order_no", "订单号"),
            Map.entry("product_name", "产品名称"),
            Map.entry("manager", "负责人"),
            Map.entry("customer", "客户名称"),
            Map.entry("order_status", "订单状态"),
            Map.entry("composition", "成分"),
            Map.entry("width", "门幅"),
            Map.entry("weight", "克重"),
            Map.entry("total_quantity", "总数量"),
            Map.entry("unit", "单位"),
            Map.entry("quality_requirement", "品质要求"),
            Map.entry("process_technology", "工艺要求"),
            Map.entry("packaging_requirement", "包装要求"),
            Map.entry("remark", "备注"),
            Map.entry("hot_stamping_requirement", "烫金要求"),
            Map.entry("shipping_mark", "唛头"),
            Map.entry("shipping_sample_remark", "船样备注"),
            Map.entry("creator", "创建人"),
            Map.entry("create_time", "创建时间"),
            Map.entry("settlement_method", "结算方式"),
            Map.entry("delivery_method", "交货方式"),
            Map.entry("overload_percent", "溢装比例"),
            Map.entry("shortage_percent", "短装比例"),
            Map.entry("amount", "金额"),
            Map.entry("currency", "币种"),
            Map.entry("release_date", "下达日期"),
            Map.entry("updater", "更新人"),
            Map.entry("update_time", "更新时间"),
            Map.entry("process_order_no", "生产单号"),
            Map.entry("exchange_rate", "汇率"),
            Map.entry("work_instruction", "工作指示"),
            Map.entry("approval_status", "审批状态"),
            Map.entry("length_coefficient", "长度系数"),
            Map.entry("inventory_enabled", "是否启用库存"),
            Map.entry("delivery_date", "交期")
    );

    @Resource
    private AiAgentAuditService aiAgentAuditService;
    @Resource
    private DataScopeService dataScopeService;
    @Resource
    private AiAgentInternalMapper aiAgentInternalMapper;
    @Resource
    private CustomerTools customerTools;
    @Resource
    private CommunicationTools communicationTools;
    @Resource
    private ExternalOrderTools externalOrderTools;
    @Resource
    private LlmAiAgentQuestionParser llmAiAgentQuestionParser;
    @Resource
    private AiAgentLlmProperties aiAgentLlmProperties;
    @Resource
    private AiAgentSqlQueryService aiAgentSqlQueryService;
    @Resource
    private AiAgentMessageBodyAccessService aiAgentMessageBodyAccessService;
    @Resource
    private AiAgentDatabaseQueryService aiAgentDatabaseQueryService;

    public AiAgentChatResponse chat(AiAgentChatRequest request) {
        String userId = SessionUtils.getUserId();
        String orgId = OrganizationContext.getOrganizationId();
        String viewId = resolveViewId(request.getDataScope());
        DeptDataPermissionDTO customerDataPermission = dataScopeService.getDeptDataPermission(
                userId, orgId, viewId, PermissionConstants.CUSTOMER_MANAGEMENT_READ);
        DeptDataPermissionDTO contractDataPermission = dataScopeService.getDeptDataPermission(
                userId, orgId, viewId, PermissionConstants.CONTRACT_READ);
        DeptDataPermissionDTO orderDataPermission = getOrderDataPermission(userId, orgId, viewId);

        AiAgentSession session = aiAgentAuditService.ensureSession(request.getSessionId(), request.getQuestion(), userId, orgId);
        AiAgentMessage userMessage = aiAgentAuditService.saveMessage(
                session.getId(), "user", request.getQuestion(), null, null, userId);

        AiAgentContext context = new AiAgentContext();
        context.setUserId(userId);
        context.setOrganizationId(orgId);
        context.setDataScope(request.getDataScope());
        context.setDataPermission(customerDataPermission);
        context.setCustomerDataPermission(customerDataPermission);
        context.setContractDataPermission(contractDataPermission);
        context.setOrderDataPermission(orderDataPermission);
        context.setLlmProvider(request.getLlmProvider());
        context.setTimeWindow(resolveTimeWindow(request.getQuestion(), request.getTimeRange()));

        AiAgentChatResponse response = route(request.getQuestion(), context);
        recordQuestionBankUsage(request.getQuestion(), response, context, session, userMessage);
        addLlmAttemptTrace(response, context);
        AiAgentMessage assistantMessage = aiAgentAuditService.saveMessage(
                session.getId(),
                "assistant",
                response.getAnswer(),
                response.getIntent(),
                aiAgentAuditService.responseEvidenceSnapshot(response),
                userId
        );
        response.setSessionId(session.getId());
        response.setMessageId(assistantMessage.getId());
        aiAgentAuditService.saveToolLogs(assistantMessage.getId(), response.getTools(), userId);
        aiAgentAuditService.touch(session.getId(), userId);
        return response;
    }

    private void recordQuestionBankUsage(String question, AiAgentChatResponse response, AiAgentContext context,
                                         AiAgentSession session, AiAgentMessage userMessage) {
        if (response == null || StringUtils.isBlank(response.getIntent())) {
            aiAgentAuditService.recordUnansweredQuestion(
                    context.getOrganizationId(), context.getUserId(), session.getId(), userMessage.getId(), question, "ERROR");
            return;
        }
        if (isUnansweredFallback(question, response)) {
            aiAgentAuditService.recordUnansweredQuestion(
                    context.getOrganizationId(), context.getUserId(), session.getId(), userMessage.getId(), question, "NO_MATCH");
            return;
        }
        aiAgentAuditService.recordAnswerableQuestionHit(
                context.getOrganizationId(),
                response.getIntent(),
                question,
                response.getAnswer(),
                response.getTools().isEmpty() ? null : response.getTools().get(0).getName(),
                response.getEvidence(),
                context.getUserId());
    }

    private boolean isUnansweredFallback(String question, AiAgentChatResponse response) {
        return StringUtils.equals(response.getIntent(), "HELP") && !isHelpQuestion(question);
    }

    private AiAgentChatResponse route(String rawQuestion, AiAgentContext context) {
        String question = StringUtils.defaultString(rawQuestion).trim();
        if (aiAgentMessageBodyAccessService.isMessageBodyQuestion(question)) {
            return aiAgentMessageBodyAccessService.explainAccessPolicy();
        }
        if (isSensitiveOrUnauthorizedProbe(question)) {
            return refusal(question);
        }
        if (containsAny(question, "最近用户问了哪些没答上", "未回答问题", "没答上的问题", "不能回答的问题")) {
            return pendingUnansweredQuestions(context);
        }
        if (containsAny(question, "加入可回答问题库", "把这个问题加入", "转入可回答")) {
            return questionBankPromotionGuide();
        }
        if (containsAny(question, "数据来源", "来自哪个表", "企微数据来自哪里", "邮件数据来自哪里",
                "支持哪些数据源", "这些订单来自哪个表")) {
            return dataSourceGuide(question);
        }
        if (containsAny(question, "为什么订单数据为空", "订单数据为空", "外部订单数据源没配置")) {
            return externalOrderConfigGuide();
        }
        if (containsAny(question, "数据范围", "全公司、我的团队、仅本人", "全公司 我的团队 仅本人")) {
            return dataScopeGuide();
        }
        ParsedAiAgentQuestion parsedQuestion = llmAiAgentQuestionParser.parse(question, context.getLlmProvider());
        context.setLlmParseAttempted(true);
        context.setLlmParsedQuestion(parsedQuestion);
        logLlmParsedQuestion(question, parsedQuestion);
        AiAgentChatResponse llmResponse = routeParsedQuestion(parsedQuestion, context);
        if (llmResponse != null) {
            return llmResponse;
        }
        AiAgentChatResponse stableRuleResponse = routeStableRuleQuestion(question, context);
        if (stableRuleResponse != null) {
            markDeterministicFallback(stableRuleResponse, parsedQuestion);
            return stableRuleResponse;
        }
        if (isCustomerNameKeywordQuestion(question)) {
            return customerSearchByKeyword(question, context);
        }
        if (containsAny(question, "最近新增了哪些客户", "新增了哪些客户", "最近新增客户", "新客户")) {
            return recentCustomers(question, context);
        }
        if (containsAny(question, "我名下有哪些客户", "我的客户有哪些", "我有哪些客户", "我的团队有哪些客户", "全公司有哪些客户")
                && !containsAny(question, "需要跟进", "优先拜访", "值得关注")) {
            return visibleCustomerList(question, context);
        }
        if (containsAny(question, "是谁负责", "属于哪个销售", "哪个销售负责", "负责人是谁")) {
            return customerFieldLookup(question, context, "CUSTOMER_OWNER_LOOKUP", "customer_owner_lookup", "负责人");
        }
        if (containsAny(question, "联系方式", "联系电话", "电话邮箱", "电话", "邮箱")) {
            return customerFieldLookup(question, context, "CUSTOMER_CONTACT_LOOKUP", "customer_contact_lookup", "联系方式");
        }
        if (containsAny(question, "最近一次跟进", "上次跟进是什么时候")) {
            return customerFieldLookup(question, context, "CUSTOMER_LAST_FOLLOW_LOOKUP", "customer_last_follow_lookup", "最近跟进时间");
        }
        if (containsAny(question, "备注里写了什么", "客户备注", "备注是什么")) {
            return customerFieldLookup(question, context, "CUSTOMER_REMARK_LOOKUP", "customer_remark_lookup", "备注");
        }
        if (containsAny(question, "在哪个地区", "客户地区", "地区是什么")) {
            return customerFieldLookup(question, context, "CUSTOMER_REGION_LOOKUP", "customer_region_lookup", "地区");
        }
        if (containsAny(question, "external_userid", "企微 external", "企微ID", "企微id")) {
            return customerFieldLookup(question, context, "CUSTOMER_WECOM_ID_LOOKUP", "customer_wecom_id_lookup", "企微ID");
        }
        if (containsAny(question, "roomid", "微信群", "关联微信群")) {
            return customerFieldLookup(question, context, "CUSTOMER_ROOMID_LOOKUP", "customer_roomid_lookup", "微信群 roomid");
        }
        if (containsAny(question, "有哪些跟进记录", "最近跟进记录")) {
            return customerFollowRecords(question, context);
        }
        if (containsAny(question, "上次是谁跟进", "最近是谁跟进")) {
            return customerLastFollower(question, context);
        }
        if (containsAny(question, "超过 30 天没跟进", "超过30天没跟进", "长期没跟进", "很久没跟进", "多久没跟进", "没跟进")) {
            return question.contains("张三") || containsAny(question, "销售", "联系专员")
                    ? specialistStaleFollowCustomers(question, context)
                    : staleFollowCustomers(context);
        }
        if (containsAny(question, "本周哪些客户还没有跟进", "本周还没有跟进")) {
            return notFollowedThisWeek(context);
        }
        if (containsAny(question, "跟进最少", "没有任何跟进记录")) {
            return leastFollowCustomers(question, context);
        }
        if (containsAny(question, "某客户最近沟通", "客户最近沟通", "多少企微消息", "多少邮件",
                "邮件往来", "企微沟通")) {
            return customerCommunication(question, context);
        }
        if (containsAny(question, "沟通最多", "沟通排行", "完全没沟通", "邮件很多但企微很少", "企微很多但没有跟进")) {
            return communicationInsight(question, context);
        }
        if (containsAny(question, "订单总金额", "订单金额是多少", "最大的一笔订单", "订单金额最高",
                "新订单金额最高", "订单总数")) {
            return orderAmountInsight(question, context);
        }
        if (containsAny(question, "快到交期", "已经逾期", "交期是什么时候")) {
            return deliveryInsight(question, context);
        }
        if (containsAny(question, "订单号", "某订单号")) {
            return orderStatusByNo(question, context);
        }
        if (containsAny(question, "某产品", "产品最近有哪些订单")) {
            return productOrders(question, context);
        }
        if (containsAny(question, "有订单但最近没有沟通", "新订单但没有跟进", "订单金额高但长期没跟进",
                "客户最值得关注", "流失风险", "主管介入", "互动下降", "突然没有互动", "优先拜访", "需要跟进")) {
            return attentionSignals(question, context);
        }
        if (containsAny(question, "正在操作", "还没有结束", "未结束", "进行中订单", "操作中的订单")) {
            return question.contains("负责的客户")
                    ? salesActiveOrders(question, context)
                    : customerOrders(question, context, true);
        }
        if (containsAny(question, "新订单", "新的订单", "新合同", "新单")) {
            return question.contains("负责的客户")
                    ? salesNewOrders(question, context)
                    : customerNewOrders(question, context);
        }
        if (containsAny(question, "沟通少", "订单金额高", "高金额")) {
            return lowCommunicationHighOrderValue(context);
        }
        if (containsAny(question, "最近有哪些订单", "最近订单", "最近有订单", "订单有哪些")) {
            return recentOrdersByQuestion(question, context);
        }
        if (containsAny(question, "沟通", "微信", "企微", "邮件", "邮箱")) {
            return salesCommunication(question, context);
        }
        if (containsAny(question, "负责哪些客户", "负责的客户", "联系专员", "销售负责哪些客户", "销售负责的客户")) {
            return salesCustomerList(question, context);
        }
        if (containsAny(question, "很久没跟进", "多久没跟进", "长期没跟进", "没跟进")) {
            return staleFollowCustomers(context);
        }
        if (containsAny(question, "合同", "合同状态", "合同有哪些", "状态分别是什么")) {
            return customerOrders(question, context, false);
        }
        if (containsAny(question, "客户", "基础信息", "汇总", "总结")) {
            return customerSummary(question, context);
        }
        return fallback(context);
    }

    private AiAgentChatResponse routeStableRuleQuestion(String question, AiAgentContext context) {
        if (isCustomerSourceListQuestion(question)) {
            return customerSourceList(question, context);
        }
        if (isVisibleCustomerListQuestion(question)) {
            return visibleCustomerList(question, context);
        }
        if (isCrmOrderQuestion(question)) {
            return salesOrderDatabaseQuery(question, context);
        }
        if (isSpecialistNoFollowQuestion(question)) {
            String specialistName = extractSpecialistName(question);
            if (StringUtils.isNotBlank(specialistName)) {
                return specialistNoFollowDatabaseQuery(specialistName, context);
            }
        }
        if (isSpecialistCustomerListQuestion(question)) {
            return salesCustomerList(question, context);
        }
        if (isSpecialistCommunicationQuestion(question)) {
            return salesCommunication(question, context);
        }
        if (containsAny(question, "正在操作", "还没有结束", "未结束", "进行中订单", "操作中的订单")
                && containsAny(question, "负责的客户", "负责客户", "联系专员", "销售")) {
            return salesActiveOrders(question, context);
        }
        if (containsAny(question, "新订单", "新的订单", "新合同", "新单")
                && containsAny(question, "负责的客户", "负责客户", "联系专员", "销售")) {
            return salesNewOrders(question, context);
        }
        if (containsAny(question, "最近有哪些订单", "最近订单", "最近有订单", "订单有哪些")
                && containsAny(question, "负责的客户", "负责客户", "联系专员", "销售")) {
            return recentOrdersByQuestion(question, context);
        }
        if (containsAny(question, "负责哪些客户", "负责的客户", "联系专员", "销售负责哪些客户", "销售负责的客户")) {
            return salesCustomerList(question, context);
        }
        return null;
    }

    private boolean isVisibleCustomerListQuestion(String question) {
        return containsAny(question, "我名下有哪些客户", "我的客户有哪些", "我有哪些客户", "我的团队有哪些客户", "全公司有哪些客户")
                && !containsAny(question, "需要跟进", "优先拜访", "值得关注");
    }

    private boolean isSpecialistCustomerListQuestion(String question) {
        if (isCustomerNameKeywordQuestion(question)) {
            return false;
        }
        if (containsAny(question, "负责哪些客户", "负责的客户", "联系专员", "销售负责哪些客户", "销售负责的客户",
                "名下客户有哪些", "名下有哪些客户")) {
            return StringUtils.isNotBlank(extractSpecialistName(question));
        }
        if (!containsAny(question, "的客户有哪些", "有哪些客户", "客户有哪些")) {
            return false;
        }
        String specialistName = extractSpecialistName(question);
        return StringUtils.isNotBlank(specialistName)
                && specialistName.length() <= 12
                && !containsAny(specialistName, "客户", "来源", "渠道", "展会", "展销", "新增", "最近", "全部", "所有",
                "团队", "公司", "名字", "名称", "全称", "包含", "带有", "关键词", "关键字", "有关", "相关");
    }

    private boolean isCustomerSourceListQuestion(String question) {
        return containsAny(question, "客户来源", "来源是", "来源为", "展会客户")
                && containsAny(question, "客户", "有哪些", "名单", "列表");
    }

    private boolean isCustomerNameKeywordQuestion(String question) {
        if (containsAny(question, "客户名称包含", "客户名字包含", "名称里包含", "名字里包含",
                "名称中包含", "名字中包含", "名称包含", "名字包含",
                "客户名称中带有", "客户名字中带有", "名称中带有", "名字中带有",
                "客户名称带有", "客户名字带有", "名称带有", "名字带有",
                "客户名称中有", "客户名字中有", "名称中有", "名字中有")) {
            return true;
        }
        if (containsAny(question, "客户名", "客户名称", "客户名字", "客户全称", "名称", "名字")
                && containsAny(question, "包含", "带有", "中有", "里有", "关键词", "关键字")) {
            return true;
        }
        return containsAny(question, "客户")
                && containsAny(question, "有关", "相关")
                && !containsAny(question, "订单", "合同", "跟进", "沟通", "邮件", "企微", "微信");
    }

    private AiAgentChatResponse customerSourceList(String question, AiAgentContext context) {
        String source = extractCustomerSource(question);
        if (StringUtils.isBlank(source)) {
            return null;
        }

        AiAgentQueryFilter sourceFilter = new AiAgentQueryFilter();
        sourceFilter.setField("customer_source");
        sourceFilter.setOperator("eq");
        sourceFilter.setValue(source);

        AiAgentQueryPlan queryPlan = new AiAgentQueryPlan();
        queryPlan.setIntent("CRM_DATABASE_QUERY");
        queryPlan.setQueryType("LIST");
        queryPlan.setEntity("customer");
        queryPlan.setSelectFields(List.of("id", "name", "full_name", "customer_source", "owner_name", "region"));
        queryPlan.setFilters(List.of(sourceFilter));
        queryPlan.setLimit(20);
        return aiAgentDatabaseQueryService.answer(queryPlan, context);
    }

    private String extractCustomerSource(String question) {
        String text = cleanupLeadingWords(question);
        for (String marker : List.of("客户来源是", "客户来源为", "来源是", "来源为")) {
            int index = text.indexOf(marker);
            if (index >= 0) {
                return cleanupCustomerSource(text.substring(index + marker.length()));
            }
        }
        if (text.contains("展会客户")) {
            return "展会客户";
        }
        if (text.contains("展会")) {
            return "展会客户";
        }
        return "";
    }

    private String cleanupCustomerSource(String text) {
        String result = cleanupName(text)
                .replace("的客户有哪些", "")
                .replace("客户有哪些", "")
                .replace("有哪些客户", "")
                .replace("客户列表", "")
                .replace("客户名单", "")
                .replace("的客户", "")
                .trim();
        return StringUtils.isBlank(result) ? null : StringUtils.abbreviate(result, 32);
    }

    private String cleanupCustomerKeyword(String text) {
        String result = cleanupName(text)
                .replace("的客户有哪些", "")
                .replace("客户有哪些", "")
                .replace("有哪些客户", "")
                .replace("客户列表", "")
                .replace("客户名单", "")
                .replace("的客户", "")
                .replace("客户", "")
                .replace("有关", "")
                .replace("相关", "")
                .replace("帮我列一下", "")
                .replace("列一下", "")
                .replace("查一下", "")
                .replace("看看", "")
                .trim();
        result = StringUtils.removeStart(result, "和").trim();
        result = StringUtils.removeStart(result, "与").trim();
        result = StringUtils.removeStart(result, "跟").trim();
        return StringUtils.isBlank(result) ? null : StringUtils.abbreviate(result, 64);
    }

    private boolean isSpecialistCommunicationQuestion(String question) {
        return containsAny(question, "和客户沟通", "与客户沟通", "跟客户沟通")
                || (containsAny(question, "客户沟通情况", "客户沟通统计", "客户沟通的情况")
                && !containsAny(question, "某客户", "客户最近沟通"));
    }

    private boolean isCrmOrderQuestion(String question) {
        if (StringUtils.isBlank(question) || !containsAny(question, "订单", "订单号", "加工单号", "生产单号")) {
            return false;
        }
        if (containsAny(question, "外部订单", "外部合同", "contract_info")) {
            return false;
        }
        return containsAny(question,
                "订单", "订单号", "加工单号", "生产单号", "原料", "成分", "加工商", "跟单员", "联系专员",
                "订单状态", "下单时间", "颜色", "色号", "加工工艺", "数量", "单价", "金额", "币种",
                "负责的订单", "订单有哪些", "有哪些订单", "最近订单", "订单总数", "订单总金额");
    }

    private AiAgentChatResponse salesOrderDatabaseQuery(String question, AiAgentContext context) {
        AiAgentQueryPlan queryPlan = new AiAgentQueryPlan();
        queryPlan.setIntent("CRM_DATABASE_QUERY");
        queryPlan.setEntity("sales_order");
        queryPlan.setLimit(20);

        List<AiAgentQueryFilter> filters = new ArrayList<>();
        String ownerName = extractOrderOwnerName(question);
        if (StringUtils.isNotBlank(ownerName)) {
            filters.add(filter("owner_name", "like", ownerName));
        }
        String orderNo = extractOrderNo(question);
        if (StringUtils.isNotBlank(orderNo)) {
            filters.add(filter("order_no", "like", orderNo));
        }
        String processOrderNo = extractProcessOrderNo(question);
        if (StringUtils.isNotBlank(processOrderNo)) {
            filters.add(filter("process_order_no", "like", processOrderNo));
        }
        String customerName = extractOrderCustomerName(question);
        if (StringUtils.isNotBlank(customerName)) {
            filters.add(filter("customer_name", "like", customerName));
        }
        String status = extractOrderStatus(question);
        if (StringUtils.isNotBlank(status)) {
            filters.add(filter("status", "like", status));
        }
        if (containsAny(question, "最近", "本月", "这个月", "近7天", "最近7天", "近30天", "本季度", "今年")) {
            filters.add(filter("order_time", "between", "CURRENT_TIME_WINDOW"));
        }
        queryPlan.setFilters(filters);

        if (containsAny(question, "总数", "多少条", "多少个")) {
            queryPlan.setQueryType("COUNT");
            return aiAgentDatabaseQueryService.answer(queryPlan, context);
        }
        if (containsAny(question, "每个联系专员", "每个销售", "各联系专员", "各销售")) {
            queryPlan.setQueryType("AGGREGATE");
            queryPlan.setGroupBy(List.of("owner_name"));
            AiAgentQueryMetric metric = new AiAgentQueryMetric();
            metric.setFunction("count");
            metric.setField("id");
            metric.setAlias("count_value");
            queryPlan.setMetrics(List.of(metric));
            return aiAgentDatabaseQueryService.answer(queryPlan, context);
        }
        if (containsAny(question, "总金额", "金额合计", "合计金额")) {
            queryPlan.setQueryType("AGGREGATE");
            AiAgentQueryMetric metric = new AiAgentQueryMetric();
            metric.setFunction("sum");
            metric.setField("amount");
            metric.setAlias("total_amount");
            queryPlan.setMetrics(List.of(metric));
            return aiAgentDatabaseQueryService.answer(queryPlan, context);
        }

        queryPlan.setQueryType("LIST");
        queryPlan.setSelectFields(defaultSalesOrderSelectFields(question));
        AiAgentQueryOrder order = new AiAgentQueryOrder();
        order.setField(containsAny(question, "最近", "新订单", "下单") ? "order_time" : "create_time");
        order.setDirection("desc");
        queryPlan.setOrderBy(List.of(order));
        return aiAgentDatabaseQueryService.answer(queryPlan, context);
    }

    private List<String> defaultSalesOrderSelectFields(String question) {
        if (containsAny(question, "原料", "成分")) {
            return List.of("order_no", "customer_name", "owner_name", "material_name", "material_type", "composition", "status");
        }
        if (containsAny(question, "加工单号", "生产单号", "加工商", "跟单员", "加工工艺")) {
            return List.of("order_no", "process_order_no", "processor", "merchandiser", "process_technology", "owner_name", "status");
        }
        if (containsAny(question, "金额", "单价", "数量", "币种")) {
            return List.of("order_no", "customer_name", "owner_name", "quantity", "unit", "unit_price", "amount", "currency", "status");
        }
        return List.of("order_no", "customer_name", "contract_name", "owner_name", "status", "material_name", "composition", "order_time", "amount");
    }

    private AiAgentQueryFilter filter(String field, String operator, Object value) {
        AiAgentQueryFilter filter = new AiAgentQueryFilter();
        filter.setField(field);
        filter.setOperator(operator);
        filter.setValue(value);
        return filter;
    }

    private String extractOrderOwnerName(String question) {
        String text = cleanupLeadingWords(question);
        for (String marker : List.of("负责的订单", "负责订单", "的订单有哪些", "有哪些订单", "订单有哪些",
                "负责的客户订单", "名下订单", "联系专员", "销售")) {
            int index = text.indexOf(marker);
            if (index > 0) {
                String value = cleanupName(text.substring(0, index));
                if (StringUtils.isNotBlank(value) && !containsAny(value, "客户", "订单", "最近", "本月", "所有", "全部")) {
                    return value;
                }
            }
        }
        return "";
    }

    private String extractOrderCustomerName(String question) {
        if (!containsAny(question, "客户")) {
            return "";
        }
        String text = cleanupLeadingWords(question);
        for (String marker : List.of("客户的订单", "这个客户", "的订单", "订单有哪些", "最近有哪些订单")) {
            int index = text.indexOf(marker);
            if (index > 0) {
                String value = cleanupName(text.substring(0, index));
                if (StringUtils.isNotBlank(value) && !containsAny(value, "销售", "联系专员", "负责")) {
                    return value;
                }
            }
        }
        return "";
    }

    private String extractOrderNo(String question) {
        Matcher matcher = Pattern.compile("(?i)(MLS[_\\-]?[A-Za-z0-9]+|[A-Z]{2,}[_\\-]?[0-9][A-Za-z0-9_\\-]*)").matcher(question);
        if (matcher.find()) {
            return matcher.group(1);
        }
        String value = extractAfterAny(question, "订单号");
        if (StringUtils.isBlank(value) || containsAny(value, "状态", "是什么", "多少", "哪些", "有哪些", "订单")) {
            return "";
        }
        return value;
    }

    private String extractProcessOrderNo(String question) {
        if (!containsAny(question, "加工单号", "生产单号")) {
            return "";
        }
        String value = extractAfterAny(question, "加工单号", "生产单号");
        if (StringUtils.isBlank(value) || containsAny(value, "是什么", "多少", "哪些", "有哪些", "订单")) {
            return "";
        }
        return value;
    }

    private String extractOrderStatus(String question) {
        if (!containsAny(question, "状态为", "状态是", "订单状态为", "订单状态是")) {
            return "";
        }
        return cleanupName(extractAfterAny(question, "订单状态为", "订单状态是", "状态为", "状态是"));
    }

    private void logLlmParsedQuestion(String question, ParsedAiAgentQuestion parsedQuestion) {
        if (parsedQuestion == null) {
            log.debug("AI agent LLM parse skipped or empty: question={}", abbreviateForLog(question));
            return;
        }
        log.debug("AI agent LLM parse: question={}, intent={}, customerName={}, specialistName={}, keyword={}, timeRange={}, confidence={}, needClarification={}, sqlRequired={}, source={}",
                abbreviateForLog(question),
                parsedQuestion.getIntent(),
                parsedQuestion.getCustomerName(),
                parsedQuestion.getSpecialistName(),
                parsedQuestion.getKeyword(),
                parsedQuestion.getTimeRange(),
                parsedQuestion.getConfidence(),
                parsedQuestion.isNeedClarification(),
                parsedQuestion.isSqlRequired(),
                parsedQuestion.getSource());
    }

    private String abbreviateForLog(String value) {
        return StringUtils.abbreviate(StringUtils.defaultString(value).replaceAll("\\s+", " ").trim(), 200);
    }

    private boolean hasRequiredLlmConfidence(ParsedAiAgentQuestion parsedQuestion) {
        if (!StringUtils.equals(parsedQuestion.getSource(), "LLM")) {
            return true;
        }
        double minConfidence = aiAgentLlmProperties.getMinConfidence();
        if (minConfidence <= 0) {
            return true;
        }
        boolean accepted = parsedQuestion.getConfidence() >= minConfidence;
        if (!accepted) {
            log.debug("AI agent LLM parse ignored because confidence is below threshold: intent={}, confidence={}, minConfidence={}",
                    parsedQuestion.getIntent(), parsedQuestion.getConfidence(), minConfidence);
        }
        return accepted;
    }

    private void applyDeterministicParameterHints(ParsedAiAgentQuestion parsedQuestion) {
        if (!isSpecialistIntent(parsedQuestion.getIntent())) {
            return;
        }
        String specialistName = extractSpecialistName(parsedQuestion.getRawQuestion());
        if (StringUtils.isBlank(specialistName)) {
            return;
        }
        String currentSpecialistName = StringUtils.trimToNull(parsedQuestion.getSpecialistName());
        if (StringUtils.equals(currentSpecialistName, specialistName)) {
            return;
        }
        log.debug("AI agent specialist parameter adjusted by question text: intent={}, llmSpecialistName={}, questionSpecialistName={}",
                parsedQuestion.getIntent(), currentSpecialistName, specialistName);
        parsedQuestion.setSpecialistName(specialistName);
    }

    private boolean isSpecialistIntent(String intent) {
        return switch (StringUtils.defaultString(intent)) {
            case "SPECIALIST_CUSTOMER_LIST",
                 "SPECIALIST_COMMUNICATION_SUMMARY",
                 "SPECIALIST_STALE_FOLLOW_CUSTOMER_LIST",
                 "SALES_CUSTOMER_NEW_ORDER_CHECK",
                 "SALES_CUSTOMER_ACTIVE_ORDER_LIST",
                 "SALES_RECENT_ORDER_LIST" -> true;
            default -> false;
        };
    }

    private AiAgentChatResponse routeParsedQuestion(ParsedAiAgentQuestion parsedQuestion, AiAgentContext context) {
        if (parsedQuestion == null) {
            return null;
        }
        if (!hasRequiredLlmConfidence(parsedQuestion)) {
            return null;
        }
        applyDeterministicParameterHints(parsedQuestion);
        rewriteCrmOrderQueryToSalesOrder(parsedQuestion);
        if (parsedQuestion.isNeedClarification()) {
            AiAgentChatResponse response = clarification(parsedQuestion);
            addLlmReasoningTrace(response, parsedQuestion);
            return response;
        }
        if (parsedQuestion.isSqlRequired()) {
            AiAgentChatResponse response = aiAgentSqlQueryService.explainCandidateSql(parsedQuestion.getCandidateSql());
            addLlmReasoningTrace(response, parsedQuestion);
            return response;
        }
        if (StringUtils.isBlank(parsedQuestion.getIntent())) {
            return null;
        }
        if (isCrmOrderQuestion(parsedQuestion.getRawQuestion()) && shouldUseSalesOrderQuery(parsedQuestion.getIntent())) {
            AiAgentChatResponse response = salesOrderDatabaseQuery(parsedQuestion.getRawQuestion(), context);
            addLlmReasoningTrace(response, parsedQuestion);
            return response;
        }
        if (isSpecialistIntent(parsedQuestion.getIntent())
                && isCustomerNameKeywordQuestion(parsedQuestion.getRawQuestion())) {
            AiAgentChatResponse response = customerSearchByKeyword(parsedQuestion.getRawQuestion(), context);
            addLlmReasoningTrace(response, parsedQuestion);
            return response;
        }
        String customerName = StringUtils.trimToNull(parsedQuestion.getCustomerName());
        String specialistName = StringUtils.trimToNull(parsedQuestion.getSpecialistName());
        if (isSpecialistNoFollowQuestion(parsedQuestion.getRawQuestion()) && StringUtils.isNotBlank(specialistName)) {
            AiAgentChatResponse response = specialistNoFollowDatabaseQuery(specialistName, context);
            addLlmReasoningTrace(response, parsedQuestion);
            return response;
        }
        AiAgentChatResponse response = switch (parsedQuestion.getIntent()) {
            case "CRM_DATABASE_QUERY" -> aiAgentDatabaseQueryService.answer(parsedQuestion.getQueryPlan(), context);
            case "CUSTOMER_NAME_SEARCH" -> StringUtils.isBlank(parsedQuestion.getKeyword())
                    ? null : customerSearchByKeywordValue(parsedQuestion.getKeyword(), context);
            case "RECENT_CUSTOMER_LIST" -> recentCustomers(parsedQuestion.getRawQuestion(), context);
            case "VISIBLE_CUSTOMER_LIST" -> visibleCustomerList(parsedQuestion.getRawQuestion(), context);
            case "SPECIALIST_CUSTOMER_LIST" -> StringUtils.isBlank(specialistName)
                    ? null : salesCustomerListBySpecialist(specialistName, context);
            case "CUSTOMER_OWNER_LOOKUP" -> customerName == null
                    ? null : customerFieldLookupByName(customerName, context, "CUSTOMER_OWNER_LOOKUP", "customer_owner_lookup", "负责人");
            case "CUSTOMER_CONTACT_LOOKUP" -> customerName == null
                    ? null : customerFieldLookupByName(customerName, context, "CUSTOMER_CONTACT_LOOKUP", "customer_contact_lookup", "联系方式");
            case "CUSTOMER_LAST_FOLLOW_LOOKUP" -> customerName == null
                    ? null : customerFieldLookupByName(customerName, context, "CUSTOMER_LAST_FOLLOW_LOOKUP", "customer_last_follow_lookup", "最近跟进时间");
            case "CUSTOMER_REMARK_LOOKUP" -> customerName == null
                    ? null : customerFieldLookupByName(customerName, context, "CUSTOMER_REMARK_LOOKUP", "customer_remark_lookup", "备注");
            case "CUSTOMER_REGION_LOOKUP" -> customerName == null
                    ? null : customerFieldLookupByName(customerName, context, "CUSTOMER_REGION_LOOKUP", "customer_region_lookup", "地区");
            case "CUSTOMER_WECOM_ID_LOOKUP" -> customerName == null
                    ? null : customerFieldLookupByName(customerName, context, "CUSTOMER_WECOM_ID_LOOKUP", "customer_wecom_id_lookup", "企微ID");
            case "CUSTOMER_ROOMID_LOOKUP" -> customerName == null
                    ? null : customerFieldLookupByName(customerName, context, "CUSTOMER_ROOMID_LOOKUP", "customer_roomid_lookup", "微信群 roomid");
            case "CUSTOMER_FOLLOW_RECORD_LIST" -> customerName == null ? null : customerFollowRecordsByName(customerName, context);
            case "CUSTOMER_LAST_FOLLOWER_LOOKUP" -> customerName == null ? null : customerLastFollowerByName(customerName, context);
            case "SPECIALIST_STALE_FOLLOW_CUSTOMER_LIST" -> StringUtils.isBlank(specialistName)
                    ? null : specialistStaleFollowCustomersByName(specialistName, context);
            case "STALE_FOLLOW_CUSTOMER_LIST" -> staleFollowCustomers(context);
            case "CUSTOMER_COMMUNICATION_SUMMARY" -> customerName == null ? null : customerCommunicationByName(customerName, context);
            case "SPECIALIST_COMMUNICATION_SUMMARY" -> StringUtils.isBlank(specialistName)
                    ? null : salesCommunicationBySpecialist(specialistName, context);
            case "COMMUNICATION_SIGNAL_LIST" -> communicationInsight(parsedQuestion.getRawQuestion(), context);
            case "ORDER_AMOUNT_INSIGHT" -> orderAmountInsight(parsedQuestion.getRawQuestion(), context);
            case "ORDER_DELIVERY_SIGNAL_LIST" -> deliveryInsight(parsedQuestion.getRawQuestion(), context);
            case "ORDER_STATUS_BY_NO" -> orderStatusByNo(parsedQuestion.getRawQuestion(), context);
            case "PRODUCT_ORDER_LIST" -> productOrders(parsedQuestion.getRawQuestion(), context);
            case "CUSTOMER_ATTENTION_SIGNAL_LIST" -> attentionSignals(parsedQuestion.getRawQuestion(), context);
            case "LOW_COMMUNICATION_HIGH_VALUE_CUSTOMER" -> lowCommunicationHighOrderValue(context);
            case "THIS_WEEK_NOT_FOLLOWED_CUSTOMER_LIST" -> notFollowedThisWeek(context);
            case "LEAST_FOLLOW_CUSTOMER_LIST", "NO_FOLLOW_RECORD_CUSTOMER_LIST" ->
                    leastFollowCustomers(parsedQuestion.getRawQuestion(), context);
            case "SALES_CUSTOMER_NEW_ORDER_CHECK" -> StringUtils.isBlank(specialistName)
                    ? null : salesNewOrdersBySpecialist(specialistName, context);
            case "SALES_RECENT_ORDER_LIST" -> StringUtils.isBlank(specialistName)
                    ? null : salesRecentOrders(context, specialistName);
            case "SALES_CUSTOMER_ACTIVE_ORDER_LIST" -> StringUtils.isBlank(specialistName)
                    ? null : salesActiveOrdersBySpecialist(specialistName, context);
            case "CUSTOMER_NEW_ORDER_CHECK" -> customerName == null ? null : customerNewOrdersByName(customerName, context);
            case "CUSTOMER_ACTIVE_ORDER_LIST" -> customerName == null ? null : customerOrdersByName(customerName, context, true);
            case "CUSTOMER_CONTRACT_STATUS_LIST" -> customerName == null ? null : customerOrdersByName(customerName, context, false);
            case "CUSTOMER_SUMMARY" -> customerName == null ? null : customerSummaryByName(customerName, context);
            default -> null;
        };
        addLlmReasoningTrace(response, parsedQuestion);
        return response;
    }

    private void rewriteCrmOrderQueryToSalesOrder(ParsedAiAgentQuestion parsedQuestion) {
        if (!StringUtils.equals(parsedQuestion.getIntent(), "CRM_DATABASE_QUERY")
                || parsedQuestion.getQueryPlan() == null
                || !isCrmOrderQuestion(parsedQuestion.getRawQuestion())) {
            return;
        }
        AiAgentQueryPlan plan = parsedQuestion.getQueryPlan();
        if (!StringUtils.equalsAny(plan.getEntity(), "contract_info", "contract")) {
            return;
        }
        plan.setEntity("sales_order");
        if (plan.getSelectFields() == null || plan.getSelectFields().isEmpty()
                || plan.getSelectFields().contains("product_name")
                || plan.getSelectFields().contains("manager")
                || plan.getSelectFields().contains("order_status")) {
            plan.setSelectFields(defaultSalesOrderSelectFields(parsedQuestion.getRawQuestion()));
        } else {
            plan.setSelectFields(plan.getSelectFields().stream()
                    .map(this::mapOrderFieldToSalesOrderField)
                    .distinct()
                    .toList());
        }
        if (plan.getFilters() != null) {
            for (AiAgentQueryFilter filter : plan.getFilters()) {
                filter.setField(mapOrderFieldToSalesOrderField(filter.getField()));
            }
        }
        if (plan.getGroupBy() != null) {
            plan.setGroupBy(plan.getGroupBy().stream()
                    .map(this::mapOrderFieldToSalesOrderField)
                    .distinct()
                    .toList());
        }
        if (plan.getOrderBy() != null) {
            for (AiAgentQueryOrder order : plan.getOrderBy()) {
                order.setField(mapOrderFieldToSalesOrderField(order.getField()));
            }
        }
        if (plan.getMetrics() != null) {
            for (AiAgentQueryMetric metric : plan.getMetrics()) {
                metric.setField(mapOrderFieldToSalesOrderField(metric.getField()));
            }
        }
    }

    private String mapOrderFieldToSalesOrderField(String field) {
        return switch (StringUtils.defaultString(field)) {
            case "manager" -> "owner_name";
            case "order_status", "approval_status" -> "status";
            case "product_name" -> "material_name";
            case "customer" -> "customer_name";
            case "number" -> "order_no";
            default -> field;
        };
    }

    private boolean shouldUseSalesOrderQuery(String intent) {
        return switch (StringUtils.defaultString(intent)) {
            case "ORDER_AMOUNT_INSIGHT",
                 "ORDER_DELIVERY_SIGNAL_LIST",
                 "ORDER_STATUS_BY_NO",
                 "PRODUCT_ORDER_LIST",
                 "SALES_CUSTOMER_NEW_ORDER_CHECK",
                 "SALES_CUSTOMER_ACTIVE_ORDER_LIST",
                 "SALES_RECENT_ORDER_LIST",
                 "CUSTOMER_NEW_ORDER_CHECK",
                 "CUSTOMER_ACTIVE_ORDER_LIST",
                 "CUSTOMER_CONTRACT_STATUS_LIST" -> true;
            default -> false;
        };
    }

    private AiAgentChatResponse clarification(ParsedAiAgentQuestion parsedQuestion) {
        AiAgentChatResponse response = base("QUESTION_CLARIFICATION_REQUIRED");
        response.setAnswer(StringUtils.defaultIfBlank(parsedQuestion.getClarificationQuestion(),
                "这个问题还缺少关键条件，请补充客户名、销售名、时间范围或要查询的数据类型。"));
        response.getTools().add(tool("question_clarification", "SUCCESS",
                "大模型解析后判断需要用户补充问题参数", 0L));
        return response;
    }

    private void addLlmAttemptTrace(AiAgentChatResponse response, AiAgentContext context) {
        if (response == null || !context.isLlmParseAttempted()) {
            return;
        }
        ParsedAiAgentQuestion parsedQuestion = context.getLlmParsedQuestion();
        if (parsedQuestion == null) {
            addToolFirst(response, tool("llm_question_parser", "SKIPPED",
                    "已先调用大模型解析问题，但模型未返回可执行 JSON 解析结果", 0L));
            addWarningOnce(response, "已先经过大模型解析；模型未返回可执行计划时，后端只使用兜底规则或帮助提示。");
            return;
        }
        addLlmReasoningTrace(response, parsedQuestion);
    }

    private void addLlmReasoningTrace(AiAgentChatResponse response, ParsedAiAgentQuestion parsedQuestion) {
        if (response == null || parsedQuestion == null) {
            return;
        }
        addToolFirst(response, tool("llm_question_parser", "SUCCESS", llmTraceSummary(parsedQuestion), 0L));
        addWarningOnce(response, "已先经过大模型解析，再由后端按白名单生成受控查询或调用受控工具。");
    }

    private void markDeterministicFallback(AiAgentChatResponse response, ParsedAiAgentQuestion parsedQuestion) {
        if (response == null) {
            return;
        }
        String reason = parsedQuestion == null
                ? "模型未返回可执行解析结果"
                : "模型解析结果未命中可执行处理器，intent=" + StringUtils.defaultIfBlank(parsedQuestion.getIntent(), "未识别");
        response.getTools().add(tool("deterministic_fallback", "SUCCESS",
                reason + "，已使用后端兜底规则", 0L));
        addWarningOnce(response, reason + "，本次使用后端兜底规则。");
    }

    private String llmTraceSummary(ParsedAiAgentQuestion parsedQuestion) {
        StringBuilder summary = new StringBuilder("大模型已解析问题");
        summary.append("，intent=").append(StringUtils.defaultIfBlank(parsedQuestion.getIntent(), "未识别"));
        summary.append("，confidence=").append(parsedQuestion.getConfidence());
        if (parsedQuestion.getQueryPlan() != null) {
            AiAgentQueryPlan plan = parsedQuestion.getQueryPlan();
            summary.append("，queryPlan=")
                    .append(StringUtils.defaultIfBlank(plan.getEntity(), "未指定实体"))
                    .append("/")
                    .append(StringUtils.defaultIfBlank(plan.getQueryType(), "未指定类型"));
            if (plan.getFilters() != null && !plan.getFilters().isEmpty()) {
                summary.append("，filters=").append(plan.getFilters().size());
            }
        }
        return summary.toString();
    }

    private void addToolFirst(AiAgentChatResponse response, AiAgentToolCallDTO toolCall) {
        boolean exists = response.getTools().stream()
                .anyMatch(existing -> StringUtils.equals(existing.getName(), toolCall.getName()));
        if (!exists) {
            response.getTools().add(0, toolCall);
        }
    }

    private void addWarningOnce(AiAgentChatResponse response, String warning) {
        if (response.getWarnings().stream().noneMatch(existing -> StringUtils.equals(existing, warning))) {
            response.getWarnings().add(warning);
        }
    }

    private AiAgentChatResponse pendingUnansweredQuestions(AiAgentContext context) {
        long start = System.currentTimeMillis();
        List<AiAgentUnansweredQuestionRow> rows = aiAgentInternalMapper.listPendingUnansweredQuestions(
                context.getOrganizationId(), 20);
        AiAgentChatResponse response = base("PENDING_UNANSWERED_QUESTION_LIST");
        response.getEvidence().add("ai_agent_unanswered_question");
        response.getTools().add(tool("pending_unanswered_question_list", "SUCCESS",
                "读取待处理的 AI Agent 未回答问题池", System.currentTimeMillis() - start));
        if (rows.isEmpty()) {
            response.setAnswer("当前组织暂无待处理的未回答问题。");
            return response;
        }
        response.setAnswer("当前待处理未回答问题共展示前 " + rows.size() + " 条，已按出现次数和最后提问时间排序。");
        rows.forEach(row -> response.getPoints().add(
                row.getQuestion() + "：出现 " + safeLong(row.getOccurCount()) + " 次，原因 "
                        + StringUtils.defaultIfBlank(row.getMissReason(), "NO_MATCH")
                        + "，最后提问 " + formatTimestamp(row.getLastAskTime())));
        return response;
    }

    private AiAgentChatResponse questionBankPromotionGuide() {
        AiAgentChatResponse response = base("QUESTION_BANK_PROMOTION_GUIDE");
        response.setAnswer("可以加入，但建议先进入未回答问题池，由管理员确认答案口径后再转入可回答问题库。现在系统会自动记录未命中问题，并统计出现次数。");
        response.getEvidence().add("ai_agent_answerable_question");
        response.getEvidence().add("ai_agent_unanswered_question");
        response.getTools().add(tool("question_bank_promotion_guide", "SUCCESS",
                "返回问题库转正流程说明", 0L));
        response.getPoints().add("已支持的问题会自动更新 ai_agent_answerable_question 的命中次数和别名。");
        response.getPoints().add("未支持的问题会进入 ai_agent_unanswered_question，状态为 PENDING。");
        return response;
    }

    private AiAgentChatResponse dataSourceGuide(String question) {
        AiAgentChatResponse response = base("AI_AGENT_DATA_SOURCE_GUIDE");
        response.setAnswer("当前智能体主要使用 CRM 客户、订单、合同、跟进、企微和邮件数据回答问题。");
        response.getEvidence().add("customer");
        response.getEvidence().add("sales_order");
        response.getEvidence().add("contract");
        response.getEvidence().add("follow_up_record");
        response.getEvidence().add("wecom_ingestion_message");
        response.getEvidence().add("email_webhook_event");
        response.getEvidence().add("mls_agent_data.contract_info");
        response.getTools().add(tool("data_source_guide", "SUCCESS", "返回 AI Agent 数据来源说明", 0L));
        response.getPoints().add("客户基础信息来自 customer。");
        response.getPoints().add("订单信息优先来自 CRM 主订单表 sales_order，包含订单号、客户、合同、联系专员、状态、原料、成分、下单时间、金额等字段。");
        response.getPoints().add("合同信息来自 contract。");
        response.getPoints().add("跟进记录来自 follow_up_record。");
        response.getPoints().add("企微统计来自 wecom_ingestion_message，只返回统计，不返回聊天正文。");
        response.getPoints().add("邮件统计来自 email_webhook_event，只返回统计，不返回邮件正文。");
        response.getPoints().add("外部 mls_agent_data.contract_info 仅作为历史外部合同/订单明细数据源，不再作为普通订单问题的默认来源。");
        if (question.contains("聊天正文")) {
            response.getWarnings().add("聊天正文、邮件正文和敏感字段默认不返回。");
        }
        return response;
    }

    private AiAgentChatResponse externalOrderConfigGuide() {
        AiAgentChatResponse response = base("EXTERNAL_ORDER_CONFIG_GUIDE");
        response.setAnswer("订单数据为空通常有三类原因：外部订单数据源未配置、当前账号没有对应客户/合同权限，或 CRM 客户名称没有匹配到外部订单表 customer 字段。");
        response.getEvidence().add("mls_agent_data.contract_info");
        response.getTools().add(tool("external_order_config_guide", "SUCCESS", "返回外部订单数据源排查说明", 0L));
        response.getPoints().add("配置项：crm.ai-agent.external-order.enabled/url/username/password。");
        response.getPoints().add("匹配口径：CRM customer.name 与外部 contract_info.customer 做模糊匹配。");
        response.getPoints().add("权限口径：合同数据会按当前账号可见负责人范围过滤。");
        return response;
    }

    private AiAgentChatResponse dataScopeGuide() {
        AiAgentChatResponse response = base("DATA_SCOPE_GUIDE");
        response.setAnswer("数据范围决定智能体能看哪些客户和订单：全公司、我的团队、仅本人客户分别对应 ALL、DEPARTMENT、SELF。");
        response.getTools().add(tool("data_scope_guide", "SUCCESS", "返回 AI Agent 数据范围说明", 0L));
        response.getPoints().add("全公司：按当前账号拥有的全量权限查询。");
        response.getPoints().add("我的团队：按部门权限查询。");
        response.getPoints().add("仅本人客户：只查询 owner 为当前用户的客户。");
        response.getWarnings().add("无论选择哪个范围，都不会绕过系统权限。");
        return response;
    }

    private AiAgentChatResponse customerSearchByKeyword(String question, AiAgentContext context) {
        String keyword = extractAfterAny(question,
                "客户名称中带有", "客户名字中带有", "名称中带有", "名字中带有",
                "客户名称带有", "客户名字带有", "名称带有", "名字带有",
                "客户名称中包含", "客户名字中包含", "名称中包含", "名字中包含",
                "客户名称包含", "客户名字包含", "名称里包含", "名字里包含",
                "名称包含", "名字包含", "客户名称中有", "客户名字中有",
                "名称中有", "名字中有", "包含", "带有", "和");
        return customerSearchByKeywordValue(keyword, context);
    }

    private AiAgentChatResponse customerSearchByKeywordValue(String keyword, AiAgentContext context) {
        String cleanedKeyword = cleanupCustomerKeyword(keyword);
        if (StringUtils.isBlank(cleanedKeyword)) {
            AiAgentChatResponse response = base("QUESTION_CLARIFICATION_REQUIRED");
            response.setAnswer("请补充要在客户名称里搜索的关键词。");
            response.getTools().add(tool("customer_name_keyword_clarification", "SUCCESS",
                    "客户名称关键词为空", 0L));
            return response;
        }

        AiAgentQueryFilter keywordFilter = new AiAgentQueryFilter();
        keywordFilter.setField("name_keyword");
        keywordFilter.setOperator("like");
        keywordFilter.setValue(cleanedKeyword);

        AiAgentQueryPlan queryPlan = new AiAgentQueryPlan();
        queryPlan.setIntent("CRM_DATABASE_QUERY");
        queryPlan.setQueryType("LIST");
        queryPlan.setEntity("customer");
        queryPlan.setSelectFields(List.of("id", "name", "full_name", "owner_name", "region", "follow_time"));
        queryPlan.setFilters(List.of(keywordFilter));
        queryPlan.setLimit(20);
        return aiAgentDatabaseQueryService.answer(queryPlan, context);
    }

    private boolean isSpecialistNoFollowQuestion(String question) {
        return containsAny(question, "客户")
                && containsAny(question, "没有跟进", "没跟进", "未跟进", "没有跟进记录", "未记录跟进", "从未跟进", "还没跟进")
                && !containsAny(question, "超过", "长期", "很久", "多久", "30天", "30 天", "本周", "这个月", "本月");
    }

    private AiAgentChatResponse specialistNoFollowDatabaseQuery(String specialistName, AiAgentContext context) {
        AiAgentQueryFilter ownerFilter = new AiAgentQueryFilter();
        ownerFilter.setField("owner_name");
        ownerFilter.setOperator("like");
        ownerFilter.setValue(specialistName);

        AiAgentQueryFilter noFollowFilter = new AiAgentQueryFilter();
        noFollowFilter.setField("follow_time");
        noFollowFilter.setOperator("is_null");

        AiAgentQueryOrder order = new AiAgentQueryOrder();
        order.setField("create_time");
        order.setDirection("desc");

        AiAgentQueryPlan queryPlan = new AiAgentQueryPlan();
        queryPlan.setIntent("CRM_DATABASE_QUERY");
        queryPlan.setQueryType("LIST");
        queryPlan.setEntity("customer");
        queryPlan.setSelectFields(List.of("id", "name", "full_name", "owner_name", "region", "follow_time"));
        queryPlan.setFilters(List.of(ownerFilter, noFollowFilter));
        queryPlan.setOrderBy(List.of(order));
        queryPlan.setLimit(20);
        return aiAgentDatabaseQueryService.answer(queryPlan, context);
    }

    private AiAgentChatResponse recentCustomers(String question, AiAgentContext context) {
        List<AiAgentCustomerRow> rows = customerTools.searchCustomers(context, "", 200)
                .stream()
                .sorted(Comparator.comparing((AiAgentCustomerRow row) -> row.getCreateTime() == null ? 0L : row.getCreateTime()).reversed())
                .limit(10)
                .toList();
        AiAgentChatResponse response = base("RECENT_CUSTOMER_LIST");
        response.getEvidence().add("customer");
        response.getTools().add(tool("recent_customer_list", "SUCCESS", "按 customer.create_time 查看最近新增客户", 0L));
        if (rows.isEmpty()) {
            response.setAnswer("当前权限范围内暂无可见客户。");
            return response;
        }
        response.setAnswer("当前权限范围内最近新增的客户如下，按创建时间倒序展示。");
        fillCustomerPoints(response, rows);
        return response;
    }

    private AiAgentChatResponse visibleCustomerList(String question, AiAgentContext context) {
        List<AiAgentCustomerRow> rows = customerTools.searchCustomers(context, "", 50);
        AiAgentChatResponse response = base("VISIBLE_CUSTOMER_LIST");
        response.getEvidence().add("customer");
        response.getTools().add(tool("visible_customer_list", "SUCCESS", "读取当前数据范围内的可见客户", 0L));
        if (rows.isEmpty()) {
            response.setAnswer("当前数据范围内暂无可见客户。");
            return response;
        }
        response.setAnswer("当前数据范围内可见客户共展示前 " + Math.min(rows.size(), 10) + " 个。");
        fillCustomerPoints(response, rows);
        return response;
    }

    private AiAgentChatResponse customerFieldLookup(String question, AiAgentContext context, String intent,
                                                    String toolName, String fieldName) {
        String customerName = extractCustomerName(question);
        return customerFieldLookupByName(customerName, context, intent, toolName, fieldName);
    }

    private AiAgentChatResponse customerFieldLookupByName(String customerName, AiAgentContext context, String intent,
                                                          String toolName, String fieldName) {
        List<AiAgentCustomerRow> rows = customerTools.searchCustomers(context, customerName, 1);
        AiAgentChatResponse response = base(intent);
        response.getEvidence().add("customer");
        response.getTools().add(tool(toolName, "SUCCESS", "读取 customer 主表中的" + fieldName, 0L));
        if (rows.isEmpty()) {
            response.setAnswer("在你当前权限范围内，未找到名称匹配“" + customerName + "”的客户。");
            return response;
        }
        AiAgentCustomerRow customer = rows.get(0);
        response.setAnswer("客户“" + customer.getName() + "”的" + fieldName + "如下。");
        switch (fieldName) {
            case "负责人" -> response.getPoints().add("负责人：" + StringUtils.defaultIfBlank(customer.getOwnerName(), "未设置"));
            case "联系方式" -> {
                response.getPoints().add("邮箱：" + maskEmail(customer.getEmail()));
                response.getPoints().add("电话：" + maskPhone(customer.getPhone()));
                response.getPoints().add("地址：" + StringUtils.defaultIfBlank(customer.getAddress(), "未填写"));
            }
            case "最近跟进时间" -> response.getPoints().add("最近跟进时间：" + formatTimestamp(customer.getFollowTime()));
            case "备注" -> response.getPoints().add("备注：" + StringUtils.defaultIfBlank(customer.getRemark(), "未填写"));
            case "地区" -> response.getPoints().add("地区：" + StringUtils.defaultIfBlank(customer.getRegion(), "未填写"));
            case "企微ID" -> response.getPoints().add("企微 external_userid：" + StringUtils.defaultIfBlank(customer.getWecomExternalId(), "未填写"));
            case "微信群 roomid" -> response.getPoints().add("roomid：" + StringUtils.defaultIfBlank(customer.getRoomid(), "未填写"));
            default -> fillCustomerPoints(response, rows);
        }
        response.getCitations().add(citation("crm_customer", "customer", customer.getName(), List.of(customer.getId())));
        return response;
    }

    private AiAgentChatResponse specialistStaleFollowCustomers(String question, AiAgentContext context) {
        String specialistName = StringUtils.defaultIfBlank(extractSpecialistName(question),
                aiAgentInternalMapper.findUserNameById(context.getUserId()));
        return specialistStaleFollowCustomersByName(specialistName, context);
    }

    private AiAgentChatResponse specialistStaleFollowCustomersByName(String specialistName, AiAgentContext context) {
        long threshold = Instant.now().minus(Duration.ofDays(STALE_FOLLOW_DAYS)).toEpochMilli();
        List<AiAgentCustomerRow> rows = customerTools.findCustomersBySpecialist(context, specialistName, 300)
                .stream()
                .filter(row -> row.getFollowTime() == null || row.getFollowTime() < threshold)
                .sorted(Comparator.comparing(row -> row.getFollowTime() == null ? Long.MIN_VALUE : row.getFollowTime()))
                .limit(10)
                .toList();
        AiAgentChatResponse response = base("SPECIALIST_STALE_FOLLOW_CUSTOMER_LIST");
        response.getEvidence().add("customer");
        response.getTools().add(tool("specialist_stale_follow_customer_list", "SUCCESS",
                "按销售专员筛选超过 30 天未跟进客户", 0L));
        if (rows.isEmpty()) {
            response.setAnswer("当前权限范围内未发现“" + specialistName + "”负责客户中超过 " + STALE_FOLLOW_DAYS + " 天未跟进的客户。");
            return response;
        }
        response.setAnswer("“" + specialistName + "”负责客户中，超过 " + STALE_FOLLOW_DAYS + " 天未跟进或未记录跟进时间的客户如下。");
        fillCustomerPoints(response, rows);
        return response;
    }

    private AiAgentChatResponse notFollowedThisWeek(AiAgentContext context) {
        LocalDate today = LocalDate.now(AGENT_ZONE);
        long weekStart = today.minusDays(today.getDayOfWeek().getValue() - 1L)
                .atStartOfDay(AGENT_ZONE).toInstant().toEpochMilli();
        List<AiAgentCustomerRow> rows = customerTools.searchCustomers(context, "", 500)
                .stream()
                .filter(row -> row.getFollowTime() == null || row.getFollowTime() < weekStart)
                .limit(10)
                .toList();
        AiAgentChatResponse response = base("THIS_WEEK_NOT_FOLLOWED_CUSTOMER_LIST");
        response.getEvidence().add("customer");
        response.getTools().add(tool("this_week_not_followed_customer_list", "SUCCESS",
                "筛选本周尚未跟进的可见客户", 0L));
        if (rows.isEmpty()) {
            response.setAnswer("当前权限范围内暂未发现本周未跟进的客户。");
            return response;
        }
        response.setAnswer("当前权限范围内，本周尚未跟进的客户如下。");
        fillCustomerPoints(response, rows);
        return response;
    }

    private AiAgentChatResponse leastFollowCustomers(String question, AiAgentContext context) {
        List<AiAgentCommunicationRow> rows = communicationTools.visibleCustomerCommunicationSummary(context, 200)
                .stream()
                .filter(row -> !question.contains("没有任何跟进记录") || safeLong(row.getFollowRecordCount()) == 0)
                .sorted(Comparator.comparingLong(row -> safeLong(row.getFollowRecordCount())))
                .limit(10)
                .toList();
        AiAgentChatResponse response = base(question.contains("没有任何跟进记录")
                ? "NO_FOLLOW_RECORD_CUSTOMER_LIST" : "LEAST_FOLLOW_CUSTOMER_LIST");
        response.getEvidence().add("customer");
        response.getEvidence().add("follow_up_record");
        response.getTools().add(tool("least_follow_customer_list", "SUCCESS", "统计可见客户跟进记录数量", 0L));
        if (rows.isEmpty()) {
            response.setAnswer("当前时间范围内暂未找到符合条件的客户。");
            return response;
        }
        response.setAnswer("当前时间范围内，跟进较少的客户如下。");
        rows.forEach(row -> response.getPoints().add(row.getCustomerName() + "：跟进 "
                + safeLong(row.getFollowRecordCount()) + " 条，负责人 "
                + StringUtils.defaultIfBlank(row.getOwnerName(), "未设置")));
        return response;
    }

    private AiAgentChatResponse customerFollowRecords(String question, AiAgentContext context) {
        String customerName = extractCustomerName(question);
        return customerFollowRecordsByName(customerName, context);
    }

    private AiAgentChatResponse customerFollowRecordsByName(String customerName, AiAgentContext context) {
        List<AiAgentFollowRecordRow> rows = aiAgentInternalMapper.listCustomerFollowRecords(
                context.getOrganizationId(), context.getUserId(), customerName, context.getDataPermission(), 10);
        AiAgentChatResponse response = base("CUSTOMER_FOLLOW_RECORD_LIST");
        response.getEvidence().add("follow_up_record");
        response.getTools().add(tool("customer_follow_record_list", "SUCCESS", "读取客户最近跟进记录", 0L));
        if (rows.isEmpty()) {
            response.setAnswer("当前权限范围内未查到“" + customerName + "”的跟进记录。");
            return response;
        }
        response.setAnswer("客户“" + rows.get(0).getCustomerName() + "”最近跟进记录如下，最多展示 10 条。");
        rows.forEach(row -> response.getPoints().add(formatTimestamp(row.getFollowTime()) + "："
                + StringUtils.defaultIfBlank(row.getOwnerName(), "未设置") + " 通过 "
                + StringUtils.defaultIfBlank(row.getFollowMethod(), "未填写方式") + " 跟进，内容："
                + StringUtils.abbreviate(StringUtils.defaultIfBlank(row.getContent(), "未填写"), 120)));
        return response;
    }

    private AiAgentChatResponse customerLastFollower(String question, AiAgentContext context) {
        String customerName = extractCustomerName(question);
        return customerLastFollowerByName(customerName, context);
    }

    private AiAgentChatResponse customerLastFollowerByName(String customerName, AiAgentContext context) {
        List<AiAgentFollowRecordRow> rows = aiAgentInternalMapper.listCustomerFollowRecords(
                context.getOrganizationId(), context.getUserId(), customerName, context.getDataPermission(), 1);
        AiAgentChatResponse response = base("CUSTOMER_LAST_FOLLOWER_LOOKUP");
        response.getEvidence().add("follow_up_record");
        response.getTools().add(tool("customer_last_follower_lookup", "SUCCESS", "读取客户最近一条跟进记录", 0L));
        if (rows.isEmpty()) {
            response.setAnswer("当前权限范围内未查到“" + customerName + "”的跟进记录。");
            return response;
        }
        AiAgentFollowRecordRow row = rows.get(0);
        response.setAnswer("客户“" + row.getCustomerName() + "”最近一次由“"
                + StringUtils.defaultIfBlank(row.getOwnerName(), "未设置") + "”跟进。");
        response.getPoints().add("跟进时间：" + formatTimestamp(row.getFollowTime()));
        response.getPoints().add("跟进方式：" + StringUtils.defaultIfBlank(row.getFollowMethod(), "未填写"));
        return response;
    }

    private AiAgentChatResponse customerCommunication(String question, AiAgentContext context) {
        String customerName = extractCustomerName(question);
        return customerCommunicationByName(customerName, context);
    }

    private AiAgentChatResponse customerCommunicationByName(String customerName, AiAgentContext context) {
        List<AiAgentCommunicationRow> rows = communicationTools.customerCommunicationSummary(context, customerName, 10);
        AiAgentChatResponse response = base("CUSTOMER_COMMUNICATION_SUMMARY");
        response.getEvidence().add("customer");
        response.getEvidence().add("wecom_ingestion_message");
        response.getEvidence().add("email_webhook_event");
        response.getEvidence().add("follow_up_record");
        response.getTools().add(tool("customer_communication_summary", "SUCCESS",
                "统计客户企微、邮件和跟进数量", 0L));
        if (rows.isEmpty()) {
            response.setAnswer("当前权限范围内未查到“" + customerName + "”在" + context.getTimeWindow().label() + "的沟通统计。");
            return response;
        }
        AiAgentCommunicationRow row = rows.get(0);
        response.setAnswer("客户“" + row.getCustomerName() + "”在" + context.getTimeWindow().label()
                + "的沟通统计如下。");
        response.getPoints().add("企微消息：" + safeLong(row.getWecomMessageCount()) + " 条");
        response.getPoints().add("邮件：" + safeLong(row.getEmailCount()) + " 封");
        response.getPoints().add("跟进记录：" + safeLong(row.getFollowRecordCount()) + " 条");
        response.getPoints().add("最近沟通时间：" + formatTimestamp(row.getLastCommunicationTime()));
        response.getWarnings().add("仅返回统计，不返回聊天正文或邮件正文。");
        return response;
    }

    private AiAgentChatResponse communicationInsight(String question, AiAgentContext context) {
        List<AiAgentCommunicationRow> rows = containsAny(question, "张三", "销售", "联系专员")
                ? communicationTools.salesCommunicationSummary(context,
                StringUtils.defaultIfBlank(extractSpecialistName(question), aiAgentInternalMapper.findUserNameById(context.getUserId())), 200)
                : communicationTools.visibleCustomerCommunicationSummary(context, 200);
        if (question.contains("完全没沟通")) {
            rows = rows.stream().filter(row -> communicationCount(row) == 0).toList();
        } else if (question.contains("邮件很多但企微很少")) {
            rows = rows.stream().filter(row -> safeLong(row.getEmailCount()) > safeLong(row.getWecomMessageCount())
                    && safeLong(row.getEmailCount()) > 0).toList();
        } else if (question.contains("企微很多但没有跟进")) {
            rows = rows.stream().filter(row -> safeLong(row.getWecomMessageCount()) > 0
                    && safeLong(row.getFollowRecordCount()) == 0).toList();
        } else {
            rows = rows.stream().sorted(Comparator.comparingLong(this::communicationCount).reversed()).toList();
        }
        AiAgentChatResponse response = base("COMMUNICATION_SIGNAL_LIST");
        response.getEvidence().add("customer");
        response.getEvidence().add("wecom_ingestion_message");
        response.getEvidence().add("email_webhook_event");
        response.getEvidence().add("follow_up_record");
        response.getTools().add(tool("communication_signal_list", "SUCCESS", "分析客户沟通统计信号", 0L));
        if (rows.isEmpty()) {
            response.setAnswer("当前时间范围内暂未找到符合条件的沟通信号。");
            return response;
        }
        response.setAnswer("当前时间范围内匹配到 " + rows.size() + " 个沟通信号，展示前 10 个。");
        rows.stream().limit(10).forEach(row -> response.getPoints().add(row.getCustomerName()
                + "：企微 " + safeLong(row.getWecomMessageCount()) + " 条，邮件 "
                + safeLong(row.getEmailCount()) + " 封，跟进 " + safeLong(row.getFollowRecordCount())
                + " 条，最近沟通 " + formatTimestamp(row.getLastCommunicationTime())));
        return response;
    }

    private AiAgentChatResponse orderAmountInsight(String question, AiAgentContext context) {
        long start = System.currentTimeMillis();
        ExternalOrderQueryResult orderResult = resolveOrderScope(question, context, question.contains("新订单") || question.contains("新单"), 1000);
        AiAgentChatResponse response = orderResponseBase("ORDER_AMOUNT_INSIGHT", orderResult,
                System.currentTimeMillis() - start, "order_amount_insight");
        if (!orderResult.isConfigured()) {
            return response;
        }
        List<ExternalOrderRow> rows = orderResult.getRows();
        if (rows.isEmpty()) {
            response.setAnswer("当前权限范围内未匹配到可用于统计金额的订单/合同记录。");
            return response;
        }
        if (question.contains("总数")) {
            response.setIntent("ORDER_COUNT_SUMMARY");
            response.setAnswer("当前匹配到订单/合同记录 " + rows.size() + " 条。");
            fillOrderPoints(response, rows);
            return response;
        }
        List<CustomerValueSignal> signals = aggregateOrderValues(rows);
        if (question.contains("最大的一笔")) {
            response.setIntent("LARGEST_ORDER_LOOKUP");
            rows.stream()
                    .max(Comparator.comparing(row -> parseAmount(row.getFields().get("amount"))))
                    .ifPresent(row -> {
                        response.setAnswer("当前匹配到的最大一笔订单/合同如下。");
                        response.getPoints().add(formatOrderPoint(row, 1));
                    });
            return response;
        }
        if (containsAny(question, "金额最高", "高价值")) {
            response.setIntent(question.contains("新订单") ? "TOP_NEW_ORDER_VALUE_CUSTOMER_LIST" : "TOP_ORDER_VALUE_CUSTOMER_LIST");
            response.setAnswer("当前匹配到的订单金额较高客户如下，按金额倒序展示。");
            signals.stream().limit(10).forEach(signal -> response.getPoints().add(formatCustomerValueSignalPoint(signal,
                    response.getPoints().size() + 1)));
            return response;
        }
        BigDecimal total = rows.stream()
                .map(row -> parseAmount(row.getFields().get("amount")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        response.setIntent("ORDER_AMOUNT_SUMMARY");
        response.setAnswer("当前匹配到订单/合同记录 " + rows.size() + " 条，合计金额约 " + formatAmount(total) + "。");
        fillOrderPoints(response, rows);
        return response;
    }

    private AiAgentChatResponse deliveryInsight(String question, AiAgentContext context) {
        long start = System.currentTimeMillis();
        ExternalOrderQueryResult orderResult = question.contains("客户")
                ? externalOrderTools.findOrdersForCustomer(context, extractCustomerName(question), false, 100)
                : externalOrderTools.findOrdersForVisibleCustomers(context, true, 500);
        AiAgentChatResponse response = orderResponseBase("ORDER_DELIVERY_SIGNAL_LIST", orderResult,
                System.currentTimeMillis() - start, "order_delivery_signal_list");
        if (!orderResult.isConfigured()) {
            return response;
        }
        long todayStart = LocalDate.now(AGENT_ZONE).atStartOfDay(AGENT_ZONE).toInstant().toEpochMilli();
        long sevenDaysLater = LocalDate.now(AGENT_ZONE).plusDays(7).atStartOfDay(AGENT_ZONE).toInstant().toEpochMilli();
        List<ExternalOrderRow> rows = orderResult.getRows().stream()
                .filter(row -> {
                    long delivery = parseDateMillis(row.getFields().get("delivery_date"));
                    if (delivery <= 0) {
                        return question.contains("交期是什么时候");
                    }
                    return question.contains("逾期") ? delivery < todayStart : delivery <= sevenDaysLater;
                })
                .limit(10)
                .toList();
        if (rows.isEmpty()) {
            response.setAnswer("当前匹配订单中暂未找到符合交期条件的记录。");
            return response;
        }
        response.setAnswer("当前匹配到 " + rows.size() + " 条交期相关订单/合同记录。");
        fillOrderPoints(response, rows);
        return response;
    }

    private AiAgentChatResponse orderStatusByNo(String question, AiAgentContext context) {
        String orderNo = cleanupName(extractAfterAny(question, "订单号", "某订单号"));
        long start = System.currentTimeMillis();
        ExternalOrderQueryResult orderResult = externalOrderTools.findOrdersByOrderNo(context, orderNo, 10);
        AiAgentChatResponse response = orderResponseBase("ORDER_STATUS_BY_NO", orderResult,
                System.currentTimeMillis() - start, "order_status_by_no");
        if (!orderResult.isConfigured()) {
            return response;
        }
        if (orderResult.getRows().isEmpty()) {
            response.setAnswer("当前权限范围内未匹配到订单号包含“" + orderNo + "”的记录。");
            return response;
        }
        response.setAnswer("订单号匹配“" + orderNo + "”的记录如下。");
        fillOrderPoints(response, orderResult.getRows());
        return response;
    }

    private AiAgentChatResponse productOrders(String question, AiAgentContext context) {
        String productName = cleanupName(extractAfterAny(question, "某产品", "产品", "产品名称"));
        long start = System.currentTimeMillis();
        ExternalOrderQueryResult orderResult = externalOrderTools.findOrdersByProduct(context, productName, 20);
        AiAgentChatResponse response = orderResponseBase("PRODUCT_ORDER_LIST", orderResult,
                System.currentTimeMillis() - start, "product_order_list");
        if (!orderResult.isConfigured()) {
            return response;
        }
        if (orderResult.getRows().isEmpty()) {
            response.setAnswer("当前权限范围内未匹配到产品名称包含“" + productName + "”的订单/合同记录。");
            return response;
        }
        response.setAnswer("产品名称匹配“" + productName + "”的订单/合同记录如下。");
        fillOrderPoints(response, orderResult.getRows());
        return response;
    }

    private AiAgentChatResponse attentionSignals(String question, AiAgentContext context) {
        long start = System.currentTimeMillis();
        ExternalOrderQueryResult orderResult = externalOrderTools.findOrdersForVisibleCustomers(context, true, 1000);
        List<AiAgentCommunicationRow> communicationRows = communicationTools.visibleCustomerCommunicationSummary(context, 500);
        Map<String, AiAgentCommunicationRow> communicationByCustomer = new LinkedHashMap<>();
        for (AiAgentCommunicationRow row : communicationRows) {
            communicationByCustomer.put(row.getCustomerName(), row);
        }
        List<CustomerValueSignal> signals = aggregateOrderValues(orderResult.getRows())
                .stream()
                .peek(signal -> {
                    AiAgentCommunicationRow communication = communicationByCustomer.get(signal.customerName);
                    if (communication != null) {
                        signal.ownerName = communication.getOwnerName();
                        signal.wecomMessageCount = safeLong(communication.getWecomMessageCount());
                        signal.emailCount = safeLong(communication.getEmailCount());
                        signal.followRecordCount = safeLong(communication.getFollowRecordCount());
                        signal.lastCommunicationTime = communication.getLastCommunicationTime();
                    }
                })
                .filter(signal -> {
                    if (question.contains("没有沟通") || question.contains("互动下降") || question.contains("突然没有互动")) {
                        return signal.communicationCount() == 0;
                    }
                    if (question.contains("没有跟进") || question.contains("长期没跟进")) {
                        return signal.followRecordCount == 0;
                    }
                    return signal.communicationCount() <= LOW_COMMUNICATION_COUNT;
                })
                .limit(10)
                .toList();
        AiAgentChatResponse response = base("CUSTOMER_ATTENTION_SIGNAL_LIST");
        response.getEvidence().add("customer");
        response.getEvidence().add("follow_up_record");
        response.getEvidence().add("wecom_ingestion_message");
        response.getEvidence().add("email_webhook_event");
        response.getEvidence().add("mls_agent_data.contract_info");
        response.getTools().add(tool("customer_attention_signal_list",
                orderResult.isConfigured() ? "SUCCESS" : "SKIPPED",
                "结合进行中订单金额、沟通和跟进统计筛选关注信号", System.currentTimeMillis() - start));
        response.getWarnings().addAll(orderResult.getWarnings());
        if (!orderResult.isConfigured()) {
            response.setAnswer("外部订单/合同数据源未配置，暂时不能生成客户关注信号。");
            return response;
        }
        if (signals.isEmpty()) {
            response.setAnswer("当前权限范围内暂未发现符合条件的客户关注信号。");
            return response;
        }
        response.setAnswer("按当前规则，值得优先关注的客户如下：有进行中订单，且最近沟通/跟进偏少。");
        for (int index = 0; index < signals.size(); index++) {
            response.getPoints().add(formatCustomerValueSignalPoint(signals.get(index), index + 1));
        }
        response.getWarnings().add("这是规则型提示，不代表系统已判断客户一定流失。");
        return response;
    }


    private AiAgentChatResponse salesCommunication(String question, AiAgentContext context) {
        long start = System.currentTimeMillis();
        String specialistName = extractSpecialistName(question);
        if (StringUtils.isBlank(specialistName)) {
            specialistName = aiAgentInternalMapper.findUserNameById(context.getUserId());
        }
        return salesCommunicationBySpecialist(specialistName, context, start);
    }

    private AiAgentChatResponse salesCommunicationBySpecialist(String specialistName, AiAgentContext context) {
        return salesCommunicationBySpecialist(specialistName, context, System.currentTimeMillis());
    }

    private AiAgentChatResponse salesCommunicationBySpecialist(String specialistName, AiAgentContext context, long start) {
        List<AiAgentCommunicationRow> rows = communicationTools.salesCommunicationSummary(context, specialistName, 30);
        long duration = System.currentTimeMillis() - start;
        log.debug("AI agent sales communication query: specialistName={}, timeWindow={}, start={}, end={}, dataScope={}, rowCount={}",
                specialistName,
                context.getTimeWindow().label(),
                context.getTimeWindow().startTime(),
                context.getTimeWindow().endTime(),
                context.getDataScope(),
                rows.size());

        AiAgentChatResponse response = base("SPECIALIST_COMMUNICATION_SUMMARY");
        response.getEvidence().add("customer");
        response.getEvidence().add("wecom_ingestion_message");
        response.getEvidence().add("email_webhook_event");
        response.getEvidence().add("follow_up_record");
        response.getTools().add(tool("sales_communication_summary", "SUCCESS", "统计销售专员可见客户的企微、邮件和跟进数量", duration));
        response.getCitations().add(citation("crm_communication", "customer", "客户沟通统计",
                rows.stream().map(AiAgentCommunicationRow::getCustomerId).toList()));

        long wecomCount = rows.stream().mapToLong(row -> safeLong(row.getWecomMessageCount())).sum();
        long emailCount = rows.stream().mapToLong(row -> safeLong(row.getEmailCount())).sum();
        long followCount = rows.stream().mapToLong(row -> safeLong(row.getFollowRecordCount())).sum();
        if (rows.isEmpty()) {
            response.setAnswer("在你当前权限范围内，未查到“" + specialistName + "”在" + context.getTimeWindow().label()
                    + "与客户发生的企微、邮件或跟进记录。");
            response.getWarnings().add("只统计当前登录用户有权限查看的客户，不展示聊天内容和邮件正文。");
            return response;
        }

        response.setAnswer("“" + specialistName + "”在" + context.getTimeWindow().label() + "与 "
                + rows.size() + " 个可见客户有沟通记录：企微消息 " + wecomCount + " 条，邮件 " + emailCount
                + " 封，跟进记录 " + followCount + " 条。");
        rows.stream().limit(10).forEach(row -> response.getPoints().add(
                row.getCustomerName() + "：企微 " + safeLong(row.getWecomMessageCount()) + " 条，邮件 "
                        + safeLong(row.getEmailCount()) + " 封，跟进 " + safeLong(row.getFollowRecordCount()) + " 条"
        ));
        response.getWarnings().add("按要求未返回聊天内容、邮件正文，只返回客户名单和统计信息。");
        return response;
    }

    private AiAgentChatResponse salesCustomerList(String question, AiAgentContext context) {
        long start = System.currentTimeMillis();
        String specialistName = StringUtils.defaultIfBlank(extractSpecialistName(question),
                aiAgentInternalMapper.findUserNameById(context.getUserId()));
        return salesCustomerListBySpecialist(specialistName, context, start);
    }

    private AiAgentChatResponse salesCustomerListBySpecialist(String specialistName, AiAgentContext context) {
        return salesCustomerListBySpecialist(specialistName, context, System.currentTimeMillis());
    }

    private AiAgentChatResponse salesCustomerListBySpecialist(String specialistName, AiAgentContext context, long start) {
        List<AiAgentCustomerRow> rows = customerTools.findCustomersBySpecialist(context, specialistName, 30);
        long duration = System.currentTimeMillis() - start;

        AiAgentChatResponse response = base("SPECIALIST_CUSTOMER_LIST");
        response.getEvidence().add("customer");
        response.getTools().add(tool("specialist_customer_list", "SUCCESS",
                "读取 customer 主表中销售/联系专员负责的可见客户", duration));
        if (rows.isEmpty()) {
            response.setAnswer("在你当前权限范围内，未找到“" + specialistName + "”负责的客户。");
            response.getWarnings().add("请确认销售/联系专员姓名是否正确，或当前账号是否有查看这些客户的权限。");
            return response;
        }
        response.setAnswer("“" + specialistName + "”负责的可见客户共 " + rows.size() + " 个。");
        fillCustomerPoints(response, rows);
        return response;
    }

    private AiAgentChatResponse staleFollowCustomers(AiAgentContext context) {
        long start = System.currentTimeMillis();
        long threshold = Instant.now().minus(Duration.ofDays(STALE_FOLLOW_DAYS)).toEpochMilli();
        List<AiAgentCustomerRow> rows = customerTools.searchCustomers(context, "", 500)
                .stream()
                .filter(row -> row.getFollowTime() == null || row.getFollowTime() < threshold)
                .sorted(Comparator.comparing(row -> row.getFollowTime() == null ? Long.MIN_VALUE : row.getFollowTime()))
                .limit(10)
                .toList();
        long duration = System.currentTimeMillis() - start;

        AiAgentChatResponse response = base("STALE_FOLLOW_CUSTOMER_LIST");
        response.getEvidence().add("customer");
        response.getTools().add(tool("stale_follow_customer_list", "SUCCESS",
                "按 customer.follow_time 查找长期未跟进客户", duration));
        if (rows.isEmpty()) {
            response.setAnswer("当前可见客户中，暂未发现超过 " + STALE_FOLLOW_DAYS + " 天未跟进的客户。");
            return response;
        }
        response.setAnswer("当前可见客户中，超过 " + STALE_FOLLOW_DAYS + " 天未跟进或未记录跟进时间的客户有 "
                + rows.size() + " 个。");
        fillCustomerPoints(response, rows);
        response.getWarnings().add("“很久没跟进”当前默认按超过 " + STALE_FOLLOW_DAYS + " 天未跟进计算。");
        return response;
    }

    private AiAgentChatResponse lowCommunicationHighOrderValue(AiAgentContext context) {
        long start = System.currentTimeMillis();
        ExternalOrderQueryResult orderResult = externalOrderTools.findOrdersForVisibleCustomers(context, false, 1000);
        List<AiAgentCommunicationRow> communicationRows = communicationTools.visibleCustomerCommunicationSummary(context, 500);
        long duration = System.currentTimeMillis() - start;

        AiAgentChatResponse response = base("LOW_COMMUNICATION_HIGH_VALUE_CUSTOMER");
        response.getEvidence().add("customer");
        response.getEvidence().add("wecom_ingestion_message");
        response.getEvidence().add("email_webhook_event");
        response.getEvidence().add("follow_up_record");
        response.getEvidence().add("mls_agent_data.contract_info");
        response.getTools().add(tool("low_communication_high_value_customer",
                orderResult.isConfigured() ? "SUCCESS" : "SKIPPED",
                "汇总可见客户最近沟通次数与外部订单金额", duration));
        response.getWarnings().addAll(orderResult.getWarnings());
        if (!orderResult.isConfigured()) {
            response.setAnswer("外部订单/合同数据源还没有配置，所以暂时不能判断“订单金额高”的客户。");
            return response;
        }

        Map<String, AiAgentCommunicationRow> communicationByCustomer = new LinkedHashMap<>();
        for (AiAgentCommunicationRow row : communicationRows) {
            communicationByCustomer.put(row.getCustomerName(), row);
        }
        Map<String, CustomerValueSignal> signals = new LinkedHashMap<>();
        for (ExternalOrderRow order : orderResult.getRows()) {
            String customerName = StringUtils.defaultIfBlank(order.getCustomer(), order.getFields().get("customer"));
            if (StringUtils.isBlank(customerName)) {
                continue;
            }
            String currency = StringUtils.defaultIfBlank(order.getFields().get("currency"), "未填写");
            String key = customerName + "\u0001" + currency;
            CustomerValueSignal signal = signals.computeIfAbsent(key, ignored -> {
                CustomerValueSignal created = new CustomerValueSignal();
                created.customerName = customerName;
                created.currency = currency;
                return created;
            });
            signal.orderCount++;
            signal.totalAmount = signal.totalAmount.add(parseAmount(order.getFields().get("amount")));
            if (StringUtils.isBlank(signal.latestOrderNo)) {
                signal.latestOrderNo = order.getOrderNo();
                signal.latestOrderStatus = order.getOrderStatus();
            }
            AiAgentCommunicationRow communication = communicationByCustomer.get(customerName);
            if (communication != null) {
                signal.ownerName = communication.getOwnerName();
                signal.wecomMessageCount = safeLong(communication.getWecomMessageCount());
                signal.emailCount = safeLong(communication.getEmailCount());
                signal.followRecordCount = safeLong(communication.getFollowRecordCount());
                signal.lastCommunicationTime = communication.getLastCommunicationTime();
            }
        }

        List<CustomerValueSignal> candidates = signals.values()
                .stream()
                .filter(signal -> signal.communicationCount() <= LOW_COMMUNICATION_COUNT)
                .filter(signal -> signal.totalAmount.compareTo(BigDecimal.ZERO) > 0)
                .sorted(Comparator.comparing((CustomerValueSignal signal) -> signal.totalAmount).reversed())
                .limit(10)
                .toList();
        if (candidates.isEmpty()) {
            response.setAnswer("当前可见客户中，暂未匹配到“沟通较少且订单金额较高”的客户。");
            response.getWarnings().add("当前默认口径：最近 " + context.getTimeWindow().label()
                    + "沟通次数不超过 " + LOW_COMMUNICATION_COUNT + " 次，且外部订单金额大于 0。");
            return response;
        }
        response.setAnswer("当前可见客户中，沟通较少但订单金额较高的客户有 " + candidates.size()
                + " 个，已按订单金额从高到低排序。");
        for (int index = 0; index < candidates.size(); index++) {
            response.getPoints().add(formatCustomerValueSignalPoint(candidates.get(index), index + 1));
        }
        response.getWarnings().add("当前默认口径：最近 " + context.getTimeWindow().label()
                + "沟通次数不超过 " + LOW_COMMUNICATION_COUNT + " 次；订单金额按 contract_info.amount 原值汇总，并保留币种。");
        return response;
    }

    private AiAgentChatResponse salesNewOrders(String question, AiAgentContext context) {
        String specialistName = StringUtils.defaultIfBlank(extractSpecialistName(question),
                aiAgentInternalMapper.findUserNameById(context.getUserId()));
        return salesNewOrdersBySpecialist(specialistName, context);
    }

    private AiAgentChatResponse salesNewOrdersBySpecialist(String specialistName, AiAgentContext context) {
        long start = System.currentTimeMillis();
        ExternalOrderQueryResult orderResult = externalOrderTools.findRecentOrdersForSales(context, specialistName, 30);
        long duration = System.currentTimeMillis() - start;
        AiAgentChatResponse response = orderResponseBase("SALES_CUSTOMER_NEW_ORDER_CHECK", orderResult, duration,
                "sales_customer_new_order_check");
        if (!orderResult.isConfigured()) {
            return response;
        }
        List<ExternalOrderRow> rows = orderResult.getRows();
        response.setAnswer("“" + specialistName + "”负责的可见客户在外部订单表中匹配到 "
                + rows.size() + " 条订单记录。");
        if (!orderResult.isDateFilterAvailable()) {
            response.getWarnings().add("外部表 contract_info 当前未配置可用订单日期字段，因此不能严格判断“本月新增”。");
        }
        fillOrderPoints(response, rows);
        return response;
    }

    private AiAgentChatResponse recentOrdersByQuestion(String question, AiAgentContext context) {
        if (containsAny(question, "负责的客户", "负责客户", "联系专员", "销售")) {
            String specialistName = StringUtils.defaultIfBlank(extractSpecialistName(question),
                    aiAgentInternalMapper.findUserNameById(context.getUserId()));
            return salesRecentOrders(context, specialistName);
        }
        String customerName = extractCustomerName(question);
        if (StringUtils.isNotBlank(customerName) && !containsAny(customerName, "最近", "订单", "合同")) {
            return customerNewOrders(question, context);
        }
        return recentVisibleOrders(context);
    }

    private AiAgentChatResponse salesRecentOrders(AiAgentContext context, String specialistName) {
        long start = System.currentTimeMillis();
        ExternalOrderQueryResult orderResult = externalOrderTools.findRecentOrdersForSales(context, specialistName, 30);
        long duration = System.currentTimeMillis() - start;
        AiAgentChatResponse response = orderResponseBase("SALES_RECENT_ORDER_LIST", orderResult, duration,
                "sales_recent_order_list");
        if (!orderResult.isConfigured()) {
            return response;
        }
        response.setAnswer("“" + specialistName + "”负责的可见客户最近匹配到 "
                + orderResult.getRows().size() + " 条订单/合同记录。");
        fillOrderPoints(response, orderResult.getRows());
        return response;
    }

    private AiAgentChatResponse recentVisibleOrders(AiAgentContext context) {
        long start = System.currentTimeMillis();
        ExternalOrderQueryResult orderResult = externalOrderTools.findRecentOrdersForVisibleCustomers(context, 30);
        long duration = System.currentTimeMillis() - start;
        AiAgentChatResponse response = orderResponseBase("VISIBLE_RECENT_ORDER_LIST", orderResult, duration,
                "visible_recent_order_list");
        if (!orderResult.isConfigured()) {
            return response;
        }
        response.setAnswer("当前可见客户最近匹配到 " + orderResult.getRows().size() + " 条订单/合同记录。");
        fillOrderPoints(response, orderResult.getRows());
        return response;
    }

    private AiAgentChatResponse salesActiveOrders(String question, AiAgentContext context) {
        String specialistName = StringUtils.defaultIfBlank(extractSpecialistName(question),
                aiAgentInternalMapper.findUserNameById(context.getUserId()));
        return salesActiveOrdersBySpecialist(specialistName, context);
    }

    private AiAgentChatResponse salesActiveOrdersBySpecialist(String specialistName, AiAgentContext context) {
        long start = System.currentTimeMillis();
        ExternalOrderQueryResult orderResult = externalOrderTools.findOrdersForSales(context, specialistName, true, 30);
        long duration = System.currentTimeMillis() - start;
        AiAgentChatResponse response = orderResponseBase("SALES_CUSTOMER_ACTIVE_ORDER_LIST", orderResult, duration,
                "sales_customer_active_order_list");
        if (!orderResult.isConfigured()) {
            return response;
        }
        response.setAnswer("“" + specialistName + "”负责的可见客户中，外部订单表匹配到 "
                + orderResult.getRows().size() + " 条尚未结束的订单记录。");
        fillOrderPoints(response, orderResult.getRows());
        return response;
    }

    private AiAgentChatResponse customerNewOrders(String question, AiAgentContext context) {
        String customerName = extractCustomerName(question);
        return customerNewOrdersByName(customerName, context);
    }

    private AiAgentChatResponse customerNewOrdersByName(String customerName, AiAgentContext context) {
        long start = System.currentTimeMillis();
        ExternalOrderQueryResult orderResult = externalOrderTools.findRecentOrdersForCustomer(context, customerName, 30);
        long duration = System.currentTimeMillis() - start;
        AiAgentChatResponse response = orderResponseBase("CUSTOMER_NEW_ORDER_CHECK", orderResult, duration,
                "customer_new_order_check");
        if (!orderResult.isConfigured()) {
            return response;
        }
        if (orderResult.getSearchedCustomers().isEmpty()) {
            response.setAnswer("在你当前权限范围内，未找到名称匹配“" + customerName + "”的客户，因此不能查询该客户新订单。");
            response.getWarnings().add("可能是客户不存在，或当前账号没有查看该客户的权限。");
            return response;
        }
        response.setAnswer("客户“" + orderResult.getSearchedCustomers().get(0) + "”在"
                + context.getTimeWindow().label() + "内匹配到 " + orderResult.getRows().size() + " 条新订单/合同记录。");
        fillOrderPoints(response, orderResult.getRows());
        return response;
    }

    private AiAgentChatResponse customerOrders(String question, AiAgentContext context, boolean activeOnly) {
        String customerName = extractCustomerName(question);
        return customerOrdersByName(customerName, context, activeOnly);
    }

    private AiAgentChatResponse customerOrdersByName(String customerName, AiAgentContext context, boolean activeOnly) {
        long start = System.currentTimeMillis();
        ExternalOrderQueryResult orderResult = externalOrderTools.findOrdersForCustomer(context, customerName, activeOnly, 30);
        long duration = System.currentTimeMillis() - start;
        AiAgentChatResponse response = orderResponseBase(activeOnly ? "CUSTOMER_ACTIVE_ORDER_LIST" : "CUSTOMER_CONTRACT_STATUS_LIST",
                orderResult, duration, activeOnly ? "customer_active_order_list" : "customer_contract_status_list");
        if (!orderResult.isConfigured()) {
            return response;
        }
        if (orderResult.getSearchedCustomers().isEmpty()) {
            response.setAnswer("在你当前权限范围内，未找到名称匹配“" + customerName + "”的客户，因此不能查询该客户订单。");
            response.getWarnings().add("可能是客户不存在，或当前账号没有查看该客户的权限。");
            return response;
        }
        response.setAnswer("客户“" + orderResult.getSearchedCustomers().get(0) + "”在外部 contract_info 表中匹配到 "
                + orderResult.getRows().size() + " 条" + (activeOnly ? "尚未结束的订单/合同记录。" : "合同/订单记录，下面会展示每条记录的全部字段和状态。"));
        fillOrderPoints(response, orderResult.getRows());
        return response;
    }

    private AiAgentChatResponse customerSummary(String question, AiAgentContext context) {
        String customerName = extractCustomerName(question);
        return customerSummaryByName(customerName, context);
    }

    private AiAgentChatResponse customerSummaryByName(String customerName, AiAgentContext context) {
        List<AiAgentCustomerRow> customers = customerTools.searchCustomers(context, customerName, 1);
        AiAgentChatResponse response = base("CUSTOMER_SUMMARY");
        response.getEvidence().add("customer");
        response.getTools().add(tool("customer_summary", "SUCCESS", "读取 customer 主表中的客户基础信息", 0L));
        if (customers.isEmpty()) {
            response.setAnswer("在你当前权限范围内，未找到名称匹配“" + customerName + "”的客户。");
            response.getWarnings().add("客户字段优先来自 customer 主表；未命中时不会绕过权限去查扩展表。");
            return response;
        }
        AiAgentCustomerRow customer = customers.get(0);
        response.setAnswer("客户“" + customer.getName() + "”的基础信息已从 customer 主表读取。负责人："
                + StringUtils.defaultIfBlank(customer.getOwnerName(), "未设置") + "。");
        response.getPoints().add("客户全称：" + StringUtils.defaultIfBlank(customer.getFullName(), "未填写"));
        response.getPoints().add("地区：" + StringUtils.defaultIfBlank(customer.getRegion(), "未填写"));
        response.getPoints().add("邮箱：" + maskEmail(customer.getEmail()));
        response.getPoints().add("电话：" + maskPhone(customer.getPhone()));
        response.getPoints().add("地址：" + StringUtils.defaultIfBlank(customer.getAddress(), "未填写"));
        response.getPoints().add("备注：" + StringUtils.defaultIfBlank(customer.getRemark(), "未填写"));
        response.getCitations().add(citation("crm_customer", "customer", customer.getName(), List.of(customer.getId())));
        return response;
    }

    private AiAgentChatResponse orderResponseBase(String intent, ExternalOrderQueryResult result,
                                                  long duration, String toolName) {
        AiAgentChatResponse response = base(intent);
        response.getEvidence().add("mls_agent_data.contract_info");
        response.getTools().add(tool(toolName, result.isConfigured() ? "SUCCESS" : "SKIPPED",
                result.isConfigured() ? "只读查询外部 contract_info 订单表" : "外部订单数据源未配置", duration));
        response.getWarnings().addAll(result.getWarnings());
        if (!result.isConfigured()) {
            response.setAnswer("外部订单/合同数据源还没有配置，所以暂时不能查询 contract_info。");
        }
        return response;
    }

    private AiAgentChatResponse refusal(String question) {
        AiAgentChatResponse response = base("SECURITY_REFUSAL");
        response.setAnswer("这个问题涉及无权限数据、敏感字段或消息正文，我不能直接返回明细。可以在你有权限的范围内返回脱敏后的聚合统计。");
        response.getWarnings().add("默认拒绝返回聊天正文、邮件正文、密码、token、授权码、无权限客户或无权限销售的明细。");
        response.getTools().add(tool("permission_guard", "SUCCESS", "命中越权/敏感内容保护规则", 0L));
        return response;
    }

    private AiAgentChatResponse fallback(AiAgentContext context) {
        AiAgentChatResponse response = base("HELP");
        response.setAnswer("我现在支持优先回答销售专员与客户沟通统计、客户新订单、客户进行中订单、客户基础信息汇总这几类问题。");
        response.getPoints().add("例如：张三这个月和客户沟通的情况怎么样？");
        response.getPoints().add("例如：张三负责的客户这个月有没有新的订单？");
        response.getPoints().add("例如：某客户最近有没有新订单？");
        response.getWarnings().add("所有结果只基于当前账号可见客户和已配置的数据源。");
        return response;
    }

    private AiAgentChatResponse base(String intent) {
        AiAgentChatResponse response = new AiAgentChatResponse();
        response.setIntent(intent);
        return response;
    }

    private AiAgentToolCallDTO tool(String name, String status, String summary, Long durationMs) {
        AiAgentToolCallDTO tool = new AiAgentToolCallDTO();
        tool.setName(name);
        tool.setStatus(status);
        tool.setSummary(summary);
        tool.setDurationMs(durationMs);
        tool.setEvidenceId("ev_" + name);
        return tool;
    }

    private AiAgentCitationDTO citation(String type, String module, String title, List<String> recordIds) {
        AiAgentCitationDTO citation = new AiAgentCitationDTO();
        citation.setType(type);
        citation.setModule(module);
        citation.setTitle(title);
        citation.setRecordIds(new ArrayList<>(recordIds));
        citation.setUpdatedAt(Instant.now().toString());
        return citation;
    }

    private void fillCustomerPoints(AiAgentChatResponse response, List<AiAgentCustomerRow> rows) {
        int rowCount = Math.min(rows.size(), 10);
        for (int index = 0; index < rowCount; index++) {
            response.getPoints().add(formatCustomerPoint(rows.get(index), index + 1));
        }
        if (rows.size() > rowCount) {
            response.getWarnings().add("当前最多展示前 " + rowCount + " 个客户。");
        }
    }

    private String formatCustomerPoint(AiAgentCustomerRow row, int index) {
        return "第 " + index + " 个客户"
                + "\n客户名称：" + StringUtils.defaultIfBlank(row.getName(), "未填写")
                + "\n客户全称：" + StringUtils.defaultIfBlank(row.getFullName(), "未填写")
                + "\n负责人：" + StringUtils.defaultIfBlank(row.getOwnerName(), "未设置")
                + "\n地区：" + StringUtils.defaultIfBlank(row.getRegion(), "未填写")
                + "\n最近跟进时间：" + formatTimestamp(row.getFollowTime());
    }

    private String formatCustomerValueSignalPoint(CustomerValueSignal signal, int index) {
        return "第 " + index + " 个客户"
                + "\n客户名称：" + StringUtils.defaultIfBlank(signal.customerName, "未填写")
                + "\n负责人：" + StringUtils.defaultIfBlank(signal.ownerName, "未设置")
                + "\n订单总金额：" + formatAmount(signal.totalAmount) + " " + StringUtils.defaultIfBlank(signal.currency, "未填写")
                + "\n订单数量：" + signal.orderCount
                + "\n最近订单号：" + StringUtils.defaultIfBlank(signal.latestOrderNo, "未填写")
                + "\n最近订单状态：" + StringUtils.defaultIfBlank(signal.latestOrderStatus, "未填写")
                + "\n沟通总次数：" + signal.communicationCount()
                + "\n企微消息数：" + signal.wecomMessageCount
                + "\n邮件数：" + signal.emailCount
                + "\n跟进记录数：" + signal.followRecordCount
                + "\n最近沟通时间：" + formatTimestamp(signal.lastCommunicationTime);
    }

    private void fillOrderPoints(AiAgentChatResponse response, List<ExternalOrderRow> rows) {
        int rowCount = Math.min(rows.size(), 10);
        for (int index = 0; index < rowCount; index++) {
            response.getPoints().add(formatOrderPoint(rows.get(index), index + 1));
        }
        if (rows.size() > rowCount) {
            response.getWarnings().add("当前最多展示前 " + rowCount + " 条订单/合同明细。");
        }
    }

    private String formatOrderPoint(ExternalOrderRow row, int index) {
        StringBuilder builder = new StringBuilder("第 " + index + " 条订单/合同");
        if (row.getFields().isEmpty()) {
            builder.append("\n订单号：").append(StringUtils.defaultIfBlank(row.getOrderNo(), "未填写"));
            builder.append("\n客户名称：").append(StringUtils.defaultIfBlank(row.getCustomer(), "未填写"));
            builder.append("\n产品名称：").append(StringUtils.defaultIfBlank(row.getProductName(), "未填写"));
            builder.append("\n负责人：").append(StringUtils.defaultIfBlank(row.getManager(), "未填写"));
            builder.append("\n订单状态：").append(StringUtils.defaultIfBlank(row.getOrderStatus(), "未填写"));
            return builder.toString();
        }
        row.getFields().forEach((field, value) -> builder
                .append("\n")
                .append(CONTRACT_FIELD_LABELS.getOrDefault(field, field))
                .append("：")
                .append(StringUtils.defaultIfBlank(value, "未填写")));
        return builder.toString();
    }

    private ExternalOrderQueryResult resolveOrderScope(String question, AiAgentContext context, boolean recentOnly, int limit) {
        if (containsAny(question, "负责的客户", "负责客户", "销售", "张三")) {
            String specialistName = StringUtils.defaultIfBlank(extractSpecialistName(question),
                    aiAgentInternalMapper.findUserNameById(context.getUserId()));
            return recentOnly
                    ? externalOrderTools.findRecentOrdersForSales(context, specialistName, limit)
                    : externalOrderTools.findOrdersForSales(context, specialistName, false, limit);
        }
        String customerName = extractCustomerName(question);
        if (StringUtils.isNotBlank(customerName) && !containsAny(customerName, "订单", "金额", "最高", "客户", "本月", "最近")) {
            return recentOnly
                    ? externalOrderTools.findRecentOrdersForCustomer(context, customerName, limit)
                    : externalOrderTools.findOrdersForCustomer(context, customerName, false, limit);
        }
        return recentOnly
                ? externalOrderTools.findRecentOrdersForVisibleCustomers(context, limit)
                : externalOrderTools.findOrdersForVisibleCustomers(context, false, limit);
    }

    private List<CustomerValueSignal> aggregateOrderValues(List<ExternalOrderRow> rows) {
        Map<String, CustomerValueSignal> signals = new LinkedHashMap<>();
        for (ExternalOrderRow order : rows) {
            String customerName = StringUtils.defaultIfBlank(order.getCustomer(), order.getFields().get("customer"));
            if (StringUtils.isBlank(customerName)) {
                customerName = "未填写客户";
            }
            String currency = StringUtils.defaultIfBlank(order.getFields().get("currency"), "未填写");
            String key = customerName + "\u0001" + currency;
            String finalCustomerName = customerName;
            CustomerValueSignal signal = signals.computeIfAbsent(key, ignored -> {
                CustomerValueSignal created = new CustomerValueSignal();
                created.customerName = finalCustomerName;
                created.currency = currency;
                return created;
            });
            signal.orderCount++;
            signal.totalAmount = signal.totalAmount.add(parseAmount(order.getFields().get("amount")));
            if (StringUtils.isBlank(signal.latestOrderNo)) {
                signal.latestOrderNo = order.getOrderNo();
                signal.latestOrderStatus = order.getOrderStatus();
                signal.ownerName = order.getManager();
            }
        }
        return signals.values()
                .stream()
                .sorted(Comparator.comparing((CustomerValueSignal signal) -> signal.totalAmount).reversed())
                .toList();
    }

    private long communicationCount(AiAgentCommunicationRow row) {
        return safeLong(row.getWecomMessageCount()) + safeLong(row.getEmailCount()) + safeLong(row.getFollowRecordCount());
    }

    private long parseDateMillis(String value) {
        String text = StringUtils.defaultString(value).trim();
        if (StringUtils.isBlank(text)) {
            return 0L;
        }
        try {
            if (text.length() >= 10) {
                return LocalDate.parse(text.substring(0, 10)).atStartOfDay(AGENT_ZONE).toInstant().toEpochMilli();
            }
        } catch (Exception ignored) {
            return 0L;
        }
        return 0L;
    }

    private BigDecimal parseAmount(String amount) {
        String value = StringUtils.defaultString(amount).trim().replace(",", "");
        if (StringUtils.isBlank(value)) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private String formatAmount(BigDecimal amount) {
        return amount == null ? "0" : amount.stripTrailingZeros().toPlainString();
    }

    private String formatTimestamp(Long value) {
        if (value == null || value <= 0) {
            return "未记录";
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(value), AGENT_ZONE).format(DATE_TIME_FORMATTER);
    }

    private AiAgentTimeWindow resolveTimeWindow(String question, String requestedRange) {
        LocalDate today = LocalDate.now(AGENT_ZONE);
        LocalDate startDate;
        String label;
        if (StringUtils.equals(requestedRange, "7d")) {
            startDate = today.minusDays(7);
            label = "近 7 天";
        } else if (StringUtils.equals(requestedRange, "month")) {
            startDate = today.withDayOfMonth(1);
            label = "本月";
        } else if (StringUtils.equals(requestedRange, "quarter") || question.contains("本季度")) {
            int firstMonth = ((today.getMonthValue() - 1) / 3) * 3 + 1;
            startDate = LocalDate.of(today.getYear(), firstMonth, 1);
            label = "本季度";
        } else if (StringUtils.equals(requestedRange, "year") || question.contains("今年")) {
            startDate = LocalDate.of(today.getYear(), 1, 1);
            label = "本年度";
        } else if (StringUtils.equals(requestedRange, "30d")) {
            startDate = today.minusDays(30);
            label = "近 30 天";
        } else if (containsAny(question, "这个月", "本月")) {
            startDate = today.withDayOfMonth(1);
            label = "本月";
        } else if (containsAny(question, "近7天", "最近7天")) {
            startDate = today.minusDays(7);
            label = "近 7 天";
        } else {
            startDate = today.minusDays(30);
            label = "近 30 天";
        }
        long start = startDate.atStartOfDay(AGENT_ZONE).toInstant().toEpochMilli();
        long end = today.plusDays(1).atStartOfDay(AGENT_ZONE).toInstant().toEpochMilli();
        return new AiAgentTimeWindow(start, end, label);
    }

    private String resolveViewId(String dataScope) {
        if (StringUtils.equals(dataScope, "mine")) {
            return InternalUserView.SELF.name();
        }
        if (StringUtils.equals(dataScope, "team")) {
            return InternalUserView.DEPARTMENT.name();
        }
        return InternalUserView.ALL.name();
    }

    private DeptDataPermissionDTO getOrderDataPermission(String userId, String orgId, String viewId) {
        DeptDataPermissionDTO basePermission = dataScopeService.getDeptDataPermission(userId, orgId, PermissionConstants.ORDER_READ);
        if (basePermission != null && Boolean.TRUE.equals(basePermission.getAll()) && InternalUserView.isSelf(viewId)) {
            basePermission.setViewId(viewId);
            return basePermission;
        }
        return dataScopeService.getDeptDataPermission(userId, orgId, viewId, PermissionConstants.ORDER_READ);
    }

    private boolean isSensitiveOrUnauthorizedProbe(String question) {
        return containsAny(question, "没有权限", "无权限", "越权", "全部客户手机号", "所有客户手机号",
                "密码", "token", "授权码", "聊天内容", "聊天正文", "邮件正文");
    }

    private boolean isHelpQuestion(String question) {
        return containsAny(question, "能问哪些", "问哪些问题", "可以问", "支持哪些", "能回答什么",
                "推荐问题", "你会什么", "帮助");
    }

    private String extractSpecialistName(String question) {
        String text = cleanupLeadingWords(question);
        for (String marker : List.of("负责哪些客户", "负责的客户", "负责客户", "的客户有哪些", "有哪些客户",
                "名下客户有哪些", "名下有哪些客户", "客户有哪些", "这个月和客户", "本月和客户", "和客户沟通", "与客户沟通")) {
            int index = text.indexOf(marker);
            if (index > 0) {
                return cleanupName(text.substring(0, index));
            }
        }
        return "";
    }

    private String extractCustomerName(String question) {
        String text = cleanupLeadingWords(question);
        for (String marker : List.of("最近有没有新订单", "有没有新订单", "最近有哪些订单", "的合同有哪些", "合同有哪些",
                "合同状态", "状态分别是什么", "有哪些合同", "的订单状态", "的基础信息", "的情况", "汇总", "总结",
                "是谁负责的", "是谁负责", "属于哪个销售", "哪个销售负责", "负责人是谁", "联系方式", "联系电话",
                "电话邮箱", "最近一次跟进", "上次跟进是什么时候", "备注里写了什么", "客户备注", "备注是什么",
                "在哪个地区", "客户地区", "地区是什么", "有没有企微", "有没有关联微信群", "最近沟通情况",
                "这个月有多少企微消息", "这个月有多少邮件", "最近有没有邮件往来", "最近有没有企微沟通",
                "最近有哪些跟进记录", "上次是谁跟进", "交期是什么时候", "订单金额是多少", "订单总金额是多少",
                "最大的一笔订单是什么", "所有订单有哪些")) {
            int index = text.indexOf(marker);
            if (index > 0) {
                return cleanupName(text.substring(0, index));
            }
        }
        return cleanupName(text);
    }

    private String extractAfterAny(String text, String... markers) {
        String value = cleanupLeadingWords(text);
        for (String marker : markers) {
            int index = value.indexOf(marker);
            if (index >= 0) {
                return cleanupName(value.substring(index + marker.length()));
            }
        }
        return cleanupName(value);
    }

    private String cleanupLeadingWords(String question) {
        return StringUtils.defaultString(question)
                .replace("请问", "")
                .replace("给我看看", "")
                .replace("帮我看看", "")
                .replace("帮我查一下", "")
                .replace("查询", "")
                .trim();
    }

    private String cleanupName(String text) {
        String result = StringUtils.defaultString(text)
                .replace("<", "")
                .replace(">", "")
                .replace("`", "")
                .replace("“", "")
                .replace("”", "")
                .replace("？", "")
                .replace("?", "")
                .replace("销售", "")
                .replace("联系专员", "")
                .trim();
        if (result.length() > 40) {
            return result.substring(0, 40);
        }
        return result;
    }

    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (StringUtils.contains(text, needle)) {
                return true;
            }
        }
        return false;
    }

    private long safeLong(Long value) {
        return value == null ? 0L : value;
    }

    private String maskEmail(String email) {
        if (StringUtils.isBlank(email) || !email.contains("@")) {
            return "未填写";
        }
        int at = email.indexOf('@');
        String prefix = email.substring(0, at);
        if (prefix.length() <= 2) {
            return prefix.charAt(0) + "***" + email.substring(at);
        }
        return prefix.substring(0, 2) + "***" + email.substring(at);
    }

    private String maskPhone(String phone) {
        if (StringUtils.isBlank(phone)) {
            return "未填写";
        }
        if (phone.length() < 7) {
            return "***";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    private static class CustomerValueSignal {
        private String customerName;
        private String ownerName;
        private String currency;
        private String latestOrderNo;
        private String latestOrderStatus;
        private int orderCount;
        private BigDecimal totalAmount = BigDecimal.ZERO;
        private long wecomMessageCount;
        private long emailCount;
        private long followRecordCount;
        private Long lastCommunicationTime;

        private long communicationCount() {
            return wecomMessageCount + emailCount + followRecordCount;
        }
    }
}
