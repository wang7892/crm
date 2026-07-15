package cn.cordys.crm.aiagent.service;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AiAgentSemanticSchemaService {

    public enum DataSourceKind {
        CRM,
        EXTERNAL_CONTRACT
    }

    public enum PermissionKind {
        CUSTOMER,
        CONTRACT,
        ORDER,
        CUSTOMER_JOIN,
        ORGANIZATION,
        USER_ORGANIZATION,
        EXTERNAL_CONTRACT
    }

    public enum ValueKind {
        TEXT,
        NUMBER,
        EPOCH_MILLIS,
        SQL_TIMESTAMP
    }

    private static final Set<String> QUERY_TYPES = Set.of("LIST", "COUNT", "AGGREGATE");
    private static final Set<String> OPERATORS = Set.of(
            "eq", "ne", "like", "not_like", "in", "not_in",
            "gt", "gte", "lt", "lte", "between", "is_null", "not_null"
    );

    private final Map<String, EntitySpec> entities;

    public AiAgentSemanticSchemaService() {
        this.entities = buildEntities();
    }

    public Optional<EntitySpec> findEntity(String entity) {
        return Optional.ofNullable(entities.get(normalize(entity)));
    }

    public Map<String, EntitySpec> entities() {
        return entities;
    }

    public boolean isQueryTypeAllowed(String queryType) {
        return QUERY_TYPES.contains(StringUtils.upperCase(StringUtils.defaultString(queryType), Locale.ROOT));
    }

    public boolean isOperatorAllowed(String operator) {
        return OPERATORS.contains(normalize(operator));
    }

    public String schemaPrompt() {
        StringBuilder builder = new StringBuilder();
        builder.append("可查询实体和字段如下。只能使用这些 entity、field、operator，不要输出 SQL。\n");
        for (EntitySpec entity : entities.values()) {
            builder.append("- ")
                    .append(entity.name())
                    .append("：")
                    .append(entity.label())
                    .append("\n");
            for (FieldSpec field : entity.fields().values()) {
                builder.append("  - ")
                        .append(field.key())
                        .append("：")
                        .append(field.label());
                if (!field.aliases().isEmpty()) {
                    builder.append("，别名：").append(String.join("、", field.aliases()));
                }
                List<String> capabilities = new ArrayList<>();
                if (field.selectable()) {
                    capabilities.add("可展示");
                }
                if (field.filterable()) {
                    capabilities.add("可过滤");
                }
                if (field.sortable()) {
                    capabilities.add("可排序");
                }
                if (field.aggregatable()) {
                    capabilities.add("可统计");
                }
                if (field.sensitive()) {
                    capabilities.add("敏感字段需脱敏");
                }
                if (!capabilities.isEmpty()) {
                    builder.append("，").append(String.join("，", capabilities));
                }
                builder.append("\n");
            }
        }
        builder.append("允许 queryType：LIST、COUNT、AGGREGATE。\n");
        builder.append("允许 operator：eq、ne、like、not_like、in、not_in、gt、gte、lt、lte、between、is_null、not_null。\n");
        builder.append("相对时间范围请使用 value=CURRENT_TIME_WINDOW，后端会换成当前请求时间窗口。\n");
        return builder.toString();
    }

    private Map<String, EntitySpec> buildEntities() {
        Map<String, EntitySpec> map = new LinkedHashMap<>();
        register(map, customerEntity());
        register(map, sysUserEntity());
        register(map, contractEntity());
        register(map, salesOrderEntity());
        register(map, followRecordEntity());
        register(map, wecomSessionDayEntity());
        register(map, wecomMessageEntity());
        register(map, wecomMediaEntity());
        register(map, wecomMessageFollowRecordEntity());
        register(map, emailEventEntity());
        register(map, emailAttachmentEntity());
        register(map, externalContractInfoEntity());
        return Collections.unmodifiableMap(map);
    }

    private void register(Map<String, EntitySpec> map, EntitySpec entity) {
        map.put(entity.name(), entity);
    }

    private EntitySpec customerEntity() {
        return entity("customer", "客户", "customer", DataSourceKind.CRM, PermissionKind.CUSTOMER,
                """
                        customer c
                        LEFT JOIN sys_user owner_user ON CONVERT(c.owner USING utf8mb4) COLLATE utf8mb4_general_ci =
                            CONVERT(owner_user.id USING utf8mb4) COLLATE utf8mb4_general_ci
                        """,
                List.of("name", "customer_source", "owner_name", "region", "follow_time"),
                List.of(
                        field("id", "客户ID", "c.id").sortable(true),
                        field("name", "客户名称", "c.name", "客户", "名称").sortable(true),
                        field("full_name", "客户全称", "c.full_name", "全称"),
                        field("name_keyword", "客户名称关键词", "(c.name LIKE :__keyword__ OR c.full_name LIKE :__keyword__)",
                                "客户名关键词", "客户名称包含", "名字中带有", "名称中带有", "客户名称模糊搜索")
                                .selectable(false),
                        field("customer_source", "客户来源", "c.customer_source", "来源", "获客来源", "来源渠道")
                                .aggregatable(true),
                        field("customer_available", "是否可用", "c.customer_available", "可用状态")
                                .aggregatable(true),
                        field("owner", "负责人ID", "c.owner", "销售ID"),
                        field("owner_name", "负责人", "owner_user.name", "销售", "归属人", "联系专员")
                                .sortable(true).aggregatable(true),
                        field("email", "邮箱", "c.email", "邮件地址").sensitive("email"),
                        field("phone", "电话", "c.phone", "手机号", "联系方式").sensitive("phone"),
                        field("region", "地区", "c.region", "国家", "区域").sortable(true).aggregatable(true),
                        field("address", "地址", "c.address"),
                        field("remark", "备注", "c.remark"),
                        field("follow_time", "最近跟进时间", "c.follow_time", "上次跟进时间").sortable(true).valueKind(ValueKind.EPOCH_MILLIS),
                        field("create_time", "创建时间", "c.create_time", "新增时间").sortable(true).valueKind(ValueKind.EPOCH_MILLIS),
                        field("update_time", "更新时间", "c.update_time").sortable(true).valueKind(ValueKind.EPOCH_MILLIS)
                ));
    }

    private EntitySpec sysUserEntity() {
        return entity("sys_user", "系统用户", "sys_user", DataSourceKind.CRM, PermissionKind.USER_ORGANIZATION,
                """
                        sys_user su
                        JOIN sys_organization_user sou ON CONVERT(sou.user_id USING utf8mb4) COLLATE utf8mb4_general_ci =
                            CONVERT(su.id USING utf8mb4) COLLATE utf8mb4_general_ci
                        """,
                List.of("name", "email", "wecom_id"),
                List.of(
                        field("id", "用户ID", "su.id"),
                        field("name", "姓名", "su.name", "用户", "销售", "负责人").sortable(true).aggregatable(true),
                        field("email", "邮箱", "su.email").sensitive("email"),
                        field("phone", "电话", "su.phone").sensitive("phone"),
                        field("wecom_id", "企业微信ID", "su.wecom_id", "企微ID"),
                        field("roomid", "企业微信群 roomid", "su.roomid")
                ));
    }

    private EntitySpec contractEntity() {
        return entity("contract", "CRM合同", "contract", DataSourceKind.CRM, PermissionKind.CONTRACT,
                """
                        contract ct
                        LEFT JOIN customer c ON CONVERT(ct.customer_id USING utf8mb4) COLLATE utf8mb4_general_ci =
                            CONVERT(c.id USING utf8mb4) COLLATE utf8mb4_general_ci
                        LEFT JOIN sys_user owner_user ON CONVERT(ct.owner USING utf8mb4) COLLATE utf8mb4_general_ci =
                            CONVERT(owner_user.id USING utf8mb4) COLLATE utf8mb4_general_ci
                        """,
                List.of("name", "number", "customer_name", "owner_name", "amount", "approval_status"),
                List.of(
                        field("id", "合同ID", "ct.id"),
                        field("name", "合同名称", "ct.name", "订单名称"),
                        field("number", "合同编号", "ct.number", "订单号", "编号").sortable(true),
                        field("customer_id", "客户ID", "ct.customer_id"),
                        field("customer_name", "客户名称", "c.name", "客户").aggregatable(true),
                        field("owner", "负责人ID", "ct.owner"),
                        field("owner_name", "负责人", "owner_user.name", "销售").aggregatable(true),
                        field("amount", "金额", "ct.amount", "合同金额", "订单金额").sortable(true).aggregatable(true).valueKind(ValueKind.NUMBER),
                        field("approval_status", "审核状态", "ct.approval_status", "审批状态", "合同状态").aggregatable(true),
                        field("start_time", "合同开始时间", "ct.start_time").sortable(true).valueKind(ValueKind.EPOCH_MILLIS),
                        field("end_time", "合同结束时间", "ct.end_time").sortable(true).valueKind(ValueKind.EPOCH_MILLIS),
                        field("create_time", "创建时间", "ct.create_time", "新增时间").sortable(true).valueKind(ValueKind.EPOCH_MILLIS),
                        field("update_time", "更新时间", "ct.update_time").sortable(true).valueKind(ValueKind.EPOCH_MILLIS)
                ));
    }

    private EntitySpec salesOrderEntity() {
        return entity("sales_order", "CRM订单", "sales_order", DataSourceKind.CRM, PermissionKind.ORDER,
                """
                        sales_order so
                        LEFT JOIN customer c ON CONVERT(so.customer_id USING utf8mb4) COLLATE utf8mb4_general_ci =
                            CONVERT(c.id USING utf8mb4) COLLATE utf8mb4_general_ci
                        LEFT JOIN contract ct ON CONVERT(so.contract_id USING utf8mb4) COLLATE utf8mb4_general_ci =
                            CONVERT(ct.id USING utf8mb4) COLLATE utf8mb4_general_ci
                        LEFT JOIN sys_user owner_user ON (
                            CONVERT(so.owner USING utf8mb4) COLLATE utf8mb4_general_ci =
                                CONVERT(owner_user.id USING utf8mb4) COLLATE utf8mb4_general_ci
                            OR CONVERT(so.owner USING utf8mb4) COLLATE utf8mb4_general_ci =
                                CONVERT(owner_user.name USING utf8mb4) COLLATE utf8mb4_general_ci
                        )
                        """,
                List.of("order_no", "customer_name", "customer_region", "contract_name", "owner_name", "status", "material_name", "composition", "order_time", "amount"),
                List.of(
                        field("id", "订单ID", "so.id").sortable(true),
                        field("order_no", "订单号", "so.order_no", "MLS编号", "编号").sortable(true),
                        field("customer_id", "客户ID", "so.customer_id"),
                        field("customer_name", "客户名称", "c.name", "客户").aggregatable(true),
                        field("customer_region", "客户地区", "c.region", "地区", "客户地区", "国家", "区域").sortable(true).aggregatable(true),
                        field("contract_id", "合同ID", "so.contract_id"),
                        field("contract_name", "合同名称", "ct.name", "合同").aggregatable(true),
                        field("contract_number", "合同编号", "ct.number", "合同订单号"),
                        field("owner", "联系专员ID/名称", "so.owner", "负责人ID", "销售ID"),
                        field("owner_name", "联系专员", "COALESCE(owner_user.name, so.owner)", "负责人", "销售", "负责专员")
                                .sortable(true).aggregatable(true),
                        field("process_order_no", "加工单号", "so.process_order_no", "生产单号").sortable(true),
                        field("processor", "加工商", "so.processor").aggregatable(true),
                        field("merchandiser", "跟单员", "so.merchandiser").aggregatable(true),
                        field("status", "订单状态", "so.status", "状态", "order_info状态").aggregatable(true),
                        field("color", "颜色", "so.color").aggregatable(true),
                        field("color_code", "色号", "so.color_code", "颜色编号").aggregatable(true),
                        field("composition", "成分", "so.composition", "订单成分"),
                        field("material_name", "原料名称", "so.material_name", "原料", "纱线名称"),
                        field("material_type", "原料类型", "so.material_type", "纱线类型").aggregatable(true),
                        field("process_technology", "加工工艺", "so.process_technology", "工艺").aggregatable(true),
                        field("order_time", "下单时间", "so.order_time", "订单时间").sortable(true).valueKind(ValueKind.EPOCH_MILLIS),
                        field("quantity", "数量", "so.quantity").sortable(true).aggregatable(true).valueKind(ValueKind.NUMBER),
                        field("unit", "单位", "so.unit").aggregatable(true),
                        field("unit_price", "单价", "so.unit_price").sortable(true).aggregatable(true).valueKind(ValueKind.NUMBER),
                        field("amount", "金额", "so.amount", "订单金额").sortable(true).aggregatable(true).valueKind(ValueKind.NUMBER),
                        field("currency", "币种", "so.currency").aggregatable(true),
                        field("organization_id", "组织ID", "so.organization_id"),
                        field("create_time", "创建时间", "so.create_time", "新增时间").sortable(true).valueKind(ValueKind.EPOCH_MILLIS),
                        field("update_time", "更新时间", "so.update_time").sortable(true).valueKind(ValueKind.EPOCH_MILLIS)
                ));
    }

    private EntitySpec followRecordEntity() {
        return entity("follow_up_record", "跟进记录", "follow_up_record", DataSourceKind.CRM, PermissionKind.CUSTOMER_JOIN,
                """
                        follow_up_record fr
                        JOIN customer c ON CONVERT(fr.customer_id USING utf8mb4) COLLATE utf8mb4_general_ci =
                            CONVERT(c.id USING utf8mb4) COLLATE utf8mb4_general_ci
                        LEFT JOIN sys_user owner_user ON CONVERT(fr.owner USING utf8mb4) COLLATE utf8mb4_general_ci =
                            CONVERT(owner_user.id USING utf8mb4) COLLATE utf8mb4_general_ci
                        """,
                List.of("customer_name", "owner_name", "follow_method", "follow_time"),
                List.of(
                        field("id", "跟进记录ID", "fr.id"),
                        field("customer_id", "客户ID", "fr.customer_id"),
                        field("customer_name", "客户名称", "c.name", "客户").aggregatable(true),
                        field("owner", "负责人ID", "fr.owner"),
                        field("owner_name", "负责人", "owner_user.name", "销售").aggregatable(true),
                        field("follow_method", "跟进方式", "fr.follow_method").aggregatable(true),
                        field("content", "跟进内容", "fr.content").selectable(true).filterable(false),
                        field("follow_time", "跟进时间", "fr.follow_time", "最近跟进时间").sortable(true).valueKind(ValueKind.EPOCH_MILLIS),
                        field("create_time", "创建时间", "fr.create_time").sortable(true).valueKind(ValueKind.EPOCH_MILLIS)
                ));
    }

    private EntitySpec wecomMessageEntity() {
        return entity("wecom_ingestion_message", "企业微信消息", "wecom_ingestion_message", DataSourceKind.CRM, PermissionKind.ORGANIZATION,
                "wecom_ingestion_message wm",
                List.of("chat_type", "msg_type", "message_direction", "send_time", "status"),
                List.of(
                        field("id", "消息ID", "wm.id"),
                        field("chat_type", "聊天类型", "wm.chat_type", "单聊", "群聊").aggregatable(true),
                        field("message_direction", "消息方向", "wm.message_direction", "客户发送", "专员发送").aggregatable(true),
                        field("msg_type", "消息类型", "wm.msg_type").aggregatable(true),
                        field("external_userid", "客户企微 external_userid", "wm.external_userid"),
                        field("matched_external_userid", "匹配客户 external_userid", "wm.matched_external_userid"),
                        field("roomid", "群聊 roomid", "wm.roomid"),
                        field("status", "处理状态", "wm.status").aggregatable(true),
                        field("send_time", "发送时间", "wm.send_time", "沟通时间").sortable(true).valueKind(ValueKind.EPOCH_MILLIS),
                        field("content_text", "消息文本预览", "wm.content_text").selectable(false).filterable(false).sensitive("text")
                ));
    }

    private EntitySpec wecomSessionDayEntity() {
        return entity("wecom_ingestion_session_day", "企业微信日会话汇总", "wecom_ingestion_session_day",
                DataSourceKind.CRM, PermissionKind.ORGANIZATION,
                "wecom_ingestion_session_day ws",
                List.of("chat_date", "chat_type", "message_count", "last_send_time", "status"),
                List.of(
                        field("id", "日会话记录ID", "ws.id"),
                        field("chat_date", "会话日期", "ws.chat_date", "日期").sortable(true).aggregatable(true),
                        field("chat_type", "聊天类型", "ws.chat_type", "单聊", "群聊").aggregatable(true),
                        field("external_userid", "客户 external_userid", "ws.external_userid"),
                        field("specialist_userid", "联系专员企微 userid", "ws.specialist_userid", "企微专员"),
                        field("roomid", "群聊 roomid", "ws.roomid"),
                        field("message_count", "消息数", "ws.message_count").sortable(true).aggregatable(true).valueKind(ValueKind.NUMBER),
                        field("media_count", "媒体数", "ws.media_count").sortable(true).aggregatable(true).valueKind(ValueKind.NUMBER),
                        field("first_send_time", "当天第一条消息时间", "ws.first_send_time").sortable(true).valueKind(ValueKind.EPOCH_MILLIS),
                        field("last_send_time", "当天最后一条消息时间", "ws.last_send_time").sortable(true).valueKind(ValueKind.EPOCH_MILLIS),
                        field("status", "处理状态", "ws.status").aggregatable(true),
                        field("follow_record_id", "跟进记录ID", "ws.follow_record_id"),
                        field("merged_content", "当天聊天内容预览", "ws.merged_content").selectable(false).filterable(false).sensitive("text")
                ));
    }

    private EntitySpec wecomMediaEntity() {
        return entity("wecom_ingestion_media", "企业微信媒体元数据", "wecom_ingestion_media",
                DataSourceKind.CRM, PermissionKind.ORGANIZATION,
                "wecom_ingestion_media wmedia",
                List.of("msg_media_type", "file_name", "fetch_status", "create_time"),
                List.of(
                        field("id", "媒体ID", "wmedia.id"),
                        field("message_id", "消息ID", "wmedia.message_id"),
                        field("msg_media_type", "媒体类型", "wmedia.msg_media_type", "图片", "语音", "视频", "文件").aggregatable(true),
                        field("file_name", "文件名", "wmedia.file_name"),
                        field("mime_type", "MIME 类型", "wmedia.mime_type").aggregatable(true),
                        field("size_bytes", "字节大小", "wmedia.size_bytes").sortable(true).aggregatable(true).valueKind(ValueKind.NUMBER),
                        field("duration_ms", "时长毫秒", "wmedia.duration_ms").sortable(true).aggregatable(true).valueKind(ValueKind.NUMBER),
                        field("fetch_status", "拉取状态", "wmedia.fetch_status").aggregatable(true),
                        field("crm_asset_ref", "CRM 附件引用", "wmedia.crm_asset_ref").selectable(false).filterable(false).sensitive("url"),
                        field("create_time", "创建时间", "wmedia.create_time").sortable(true).valueKind(ValueKind.EPOCH_MILLIS)
                ));
    }

    private EntitySpec wecomMessageFollowRecordEntity() {
        return entity("wecom_ingestion_message_follow_record", "企业微信消息与跟进记录关联", "wecom_ingestion_message_follow_record",
                DataSourceKind.CRM, PermissionKind.ORGANIZATION,
                "wecom_ingestion_message_follow_record wmfr",
                List.of("message_id", "follow_record_id", "create_time"),
                List.of(
                        field("id", "关联ID", "wmfr.id"),
                        field("message_id", "企微消息ID", "wmfr.message_id"),
                        field("follow_record_id", "跟进记录ID", "wmfr.follow_record_id"),
                        field("create_time", "创建时间", "wmfr.create_time").sortable(true).valueKind(ValueKind.EPOCH_MILLIS)
                ));
    }

    private EntitySpec emailEventEntity() {
        return entity("email_webhook_event", "邮件事件", "email_webhook_event", DataSourceKind.CRM, PermissionKind.ORGANIZATION,
                "email_webhook_event em",
                List.of("subject", "from_address", "matched_target_mailbox", "status", "create_time"),
                List.of(
                        field("id", "邮件事件ID", "em.id"),
                        field("subject", "邮件主题", "em.subject", "主题"),
                        field("from_address", "发件地址", "em.from_address", "发件人").sensitive("email").aggregatable(true),
                        field("matched_target_mailbox", "命中的目标邮箱", "em.matched_target_mailbox", "目标邮箱").sensitive("email").aggregatable(true),
                        field("status", "处理状态", "em.status").aggregatable(true),
                        field("follow_record_id", "跟进记录ID", "em.follow_record_id"),
                        field("create_time", "创建时间", "em.create_time", "邮件时间").sortable(true).valueKind(ValueKind.EPOCH_MILLIS),
                        field("content_text", "邮件正文纯文本", "em.content_text").selectable(false).filterable(false).sensitive("text")
                ));
    }

    private EntitySpec emailAttachmentEntity() {
        return entity("email_webhook_attachment", "邮件附件", "email_webhook_attachment", DataSourceKind.CRM, PermissionKind.ORGANIZATION,
                "email_webhook_attachment ea",
                List.of("file_name", "content_type", "size_bytes", "create_time"),
                List.of(
                        field("id", "附件ID", "ea.id"),
                        field("event_id", "邮件事件ID", "ea.event_id"),
                        field("file_name", "文件名", "ea.file_name"),
                        field("content_type", "内容类型", "ea.content_type").aggregatable(true),
                        field("size_bytes", "字节大小", "ea.size_bytes").sortable(true).aggregatable(true).valueKind(ValueKind.NUMBER),
                        field("download_url", "下载链接", "ea.download_url").selectable(false).filterable(false).sensitive("url"),
                        field("create_time", "创建时间", "ea.create_time").sortable(true).valueKind(ValueKind.EPOCH_MILLIS)
                ));
    }

    private EntitySpec externalContractInfoEntity() {
        return entity("contract_info", "外部合同/订单明细", "mls_agent_data.contract_info",
                DataSourceKind.EXTERNAL_CONTRACT, PermissionKind.EXTERNAL_CONTRACT,
                "contract_info ci",
                List.of("order_no", "product_name", "manager", "customer", "order_status", "amount"),
                List.of(
                        field("id", "ID", "ci.id").sortable(true),
                        field("order_no", "订单号", "ci.order_no", "MLS编号", "编号").sortable(true),
                        field("product_name", "产品名称", "ci.product_name", "品名").aggregatable(true),
                        field("total_quantity", "总数量", "ci.total_quantity", "数量", "采购数量")
                                .sortable(true).aggregatable(true).valueKind(ValueKind.NUMBER),
                        field("unit", "单位", "ci.unit").aggregatable(true),
                        field("manager", "负责人", "ci.manager", "销售").aggregatable(true),
                        field("customer", "客户名称", "ci.customer", "客户").aggregatable(true),
                        field("order_status", "订单状态", "ci.order_status", "状态").aggregatable(true),
                        field("composition", "成分", "ci.composition"),
                        field("amount", "金额", "ci.amount", "订单金额").sortable(true).aggregatable(true).valueKind(ValueKind.NUMBER),
                        field("currency", "币种", "ci.currency").aggregatable(true),
                        field("delivery_date", "交期", "ci.delivery_date", "交货日期").sortable(true).valueKind(ValueKind.SQL_TIMESTAMP),
                        field("create_time", "创建时间", "ci.create_time", "下单时间").sortable(true).valueKind(ValueKind.SQL_TIMESTAMP),
                        field("update_time", "更新时间", "ci.update_time").sortable(true).valueKind(ValueKind.SQL_TIMESTAMP)
                ));
    }

    private EntitySpec entity(String name, String label, String evidence, DataSourceKind dataSourceKind,
                              PermissionKind permissionKind, String fromSql, List<String> defaultFields,
                              List<FieldSpec> fields) {
        Map<String, FieldSpec> fieldMap = fields.stream()
                .collect(Collectors.toMap(FieldSpec::key, field -> field, (left, right) -> left, LinkedHashMap::new));
        return new EntitySpec(name, label, evidence, dataSourceKind, permissionKind, fromSql, defaultFields, fieldMap);
    }

    private FieldSpec field(String key, String label, String expression, String... aliases) {
        return new FieldSpec(key, label, expression, List.of(aliases));
    }

    private String normalize(String value) {
        return StringUtils.defaultString(value).trim().toLowerCase(Locale.ROOT);
    }

    public record EntitySpec(
            String name,
            String label,
            String evidence,
            DataSourceKind dataSourceKind,
            PermissionKind permissionKind,
            String fromSql,
            List<String> defaultFields,
            Map<String, FieldSpec> fields
    ) {
        public Optional<FieldSpec> findField(String fieldName) {
            return Optional.ofNullable(fields.get(StringUtils.defaultString(fieldName).trim().toLowerCase(Locale.ROOT)));
        }

        public Optional<FieldSpec> resolveField(String fieldName) {
            String normalized = StringUtils.defaultString(fieldName).trim().toLowerCase(Locale.ROOT);
            FieldSpec direct = fields.get(normalized);
            if (direct != null) {
                return Optional.of(direct);
            }
            return fields.values().stream()
                    .filter(field -> StringUtils.equalsIgnoreCase(field.label(), fieldName)
                            || field.aliases().stream().anyMatch(alias -> StringUtils.equalsIgnoreCase(alias, fieldName)))
                    .findFirst();
        }
    }

    public static final class FieldSpec {
        private final String key;
        private final String label;
        private final String expression;
        private final List<String> aliases;
        private boolean selectable = true;
        private boolean filterable = true;
        private boolean sortable = false;
        private boolean aggregatable = false;
        private boolean sensitive = false;
        private String mask;
        private ValueKind valueKind = ValueKind.TEXT;

        private FieldSpec(String key, String label, String expression, List<String> aliases) {
            this.key = key;
            this.label = label;
            this.expression = expression;
            this.aliases = aliases;
        }

        public String key() {
            return key;
        }

        public String label() {
            return label;
        }

        public String expression() {
            return expression;
        }

        public List<String> aliases() {
            return aliases;
        }

        public boolean selectable() {
            return selectable;
        }

        public boolean filterable() {
            return filterable;
        }

        public boolean sortable() {
            return sortable;
        }

        public boolean aggregatable() {
            return aggregatable;
        }

        public boolean sensitive() {
            return sensitive;
        }

        public String mask() {
            return mask;
        }

        public ValueKind valueKind() {
            return valueKind;
        }

        public FieldSpec selectable(boolean selectable) {
            this.selectable = selectable;
            return this;
        }

        public FieldSpec filterable(boolean filterable) {
            this.filterable = filterable;
            return this;
        }

        public FieldSpec sortable(boolean sortable) {
            this.sortable = sortable;
            return this;
        }

        public FieldSpec aggregatable(boolean aggregatable) {
            this.aggregatable = aggregatable;
            return this;
        }

        public FieldSpec sensitive(String mask) {
            this.sensitive = true;
            this.mask = mask;
            return this;
        }

        public FieldSpec valueKind(ValueKind valueKind) {
            this.valueKind = valueKind;
            return this;
        }
    }
}
