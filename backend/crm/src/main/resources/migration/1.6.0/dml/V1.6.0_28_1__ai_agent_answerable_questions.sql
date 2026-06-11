SET @ai_agent_question_seed_now = UNIX_TIMESTAMP() * 1000;

INSERT INTO ai_agent_answerable_question (
  id,
  organization_id,
  question,
  question_aliases,
  normalized_question,
  category,
  tags,
  answer_type,
  answer,
  intent,
  tool_name,
  tool_params_json,
  data_sources,
  match_threshold,
  status,
  review_status,
  hit_count,
  last_hit_time,
  remark,
  create_user,
  update_user,
  create_time,
  update_time
)
SELECT
  LOWER(SHA2(CONCAT(org.id, ':', seed.intent), 256)) AS id,
  org.id AS organization_id,
  seed.question,
  seed.question_aliases,
  seed.normalized_question,
  seed.category,
  seed.tags,
  seed.answer_type,
  seed.answer,
  seed.intent,
  seed.tool_name,
  seed.tool_params_json,
  seed.data_sources,
  seed.match_threshold,
  'ENABLED',
  'APPROVED',
  0,
  NULL,
  seed.remark,
  'admin',
  'admin',
  @ai_agent_question_seed_now,
  @ai_agent_question_seed_now
FROM sys_organization org
JOIN (
  SELECT
    '张三这个月和客户沟通的情况怎么样？' AS question,
    '["张三本月跟客户沟通了多少次？","张三最近和客户微信邮件沟通情况？","张三这个月企微和邮件沟通统计"]' AS question_aliases,
    '销售客户沟通统计' AS normalized_question,
    '沟通' AS category,
    '["销售","客户","沟通","企微","邮件","跟进"]' AS tags,
    'TOOL' AS answer_type,
    '按销售专员统计当前账号可见客户的企微、邮件和跟进记录数量，不返回聊天正文或邮件正文。' AS answer,
    'SPECIALIST_COMMUNICATION_SUMMARY' AS intent,
    'sales_communication_summary' AS tool_name,
    '{"specialistName":"{specialistName}","timeRange":"{timeRange}","dataScope":"{dataScope}"}' AS tool_params_json,
    '["customer","wecom_ingestion_message","email_webhook_event","follow_up_record"]' AS data_sources,
    0.8000 AS match_threshold,
    '对应 AiAgentChatService.salesCommunication。' AS remark
  UNION ALL
  SELECT
    '张三负责哪些客户？',
    '["张三负责的客户有哪些？","销售张三负责哪些客户？","张三这个联系专员名下有哪些客户？"]',
    '销售负责客户列表',
    '客户',
    '["销售","客户","联系专员"]',
    'TOOL',
    '查询销售或联系专员负责的当前账号可见客户列表。',
    'SPECIALIST_CUSTOMER_LIST',
    'specialist_customer_list',
    '{"specialistName":"{specialistName}","dataScope":"{dataScope}"}',
    '["customer"]',
    0.8000,
    '对应 AiAgentChatService.salesCustomerList。'
  UNION ALL
  SELECT
    '哪些客户很久没跟进？',
    '["超过30天未跟进的客户有哪些？","长期没跟进的客户列表","多久没跟进的客户需要关注？"]',
    '长期未跟进客户',
    '跟进',
    '["客户","跟进","风险"]',
    'TOOL',
    '按客户最近跟进时间筛选长期未跟进客户，当前默认超过 30 天。',
    'STALE_FOLLOW_CUSTOMER_LIST',
    'stale_follow_customer_list',
    '{"staleDays":30,"dataScope":"{dataScope}"}',
    '["customer"]',
    0.8000,
    '对应 AiAgentChatService.staleFollowCustomers。'
  UNION ALL
  SELECT
    '沟通少但是订单金额高的客户有哪些？',
    '["订单金额高但最近互动少的客户有哪些？","高金额客户里谁最近沟通少？","高价值客户里谁最近互动变少？"]',
    '低沟通高金额客户',
    '风险',
    '["客户","沟通","订单","金额","风险"]',
    'TOOL',
    '结合当前账号可见客户的沟通次数与外部订单金额，查找沟通较少但订单金额较高的客户。',
    'LOW_COMMUNICATION_HIGH_VALUE_CUSTOMER',
    'low_communication_high_value_customer',
    '{"maxCommunicationCount":2,"timeRange":"{timeRange}","dataScope":"{dataScope}"}',
    '["customer","wecom_ingestion_message","email_webhook_event","follow_up_record","mls_agent_data.contract_info"]',
    0.8000,
    '对应 AiAgentChatService.lowCommunicationHighOrderValue。'
  UNION ALL
  SELECT
    '张三负责的客户这个月有没有新的订单？',
    '["张三负责客户本月有没有新单？","张三名下客户最近有没有新合同？","张三负责的客户最近新增订单有哪些？"]',
    '销售负责客户新订单',
    '订单',
    '["销售","客户","订单","合同","新单"]',
    'TOOL',
    '查询销售负责的当前账号可见客户在外部订单表中的新订单或新合同。',
    'SALES_CUSTOMER_NEW_ORDER_CHECK',
    'sales_customer_new_order_check',
    '{"specialistName":"{specialistName}","timeRange":"{timeRange}","dataScope":"{dataScope}"}',
    '["customer","mls_agent_data.contract_info"]',
    0.8000,
    '对应 AiAgentChatService.salesNewOrders。'
  UNION ALL
  SELECT
    '张三负责的客户最近有哪些订单？',
    '["张三负责客户最近订单有哪些？","张三名下客户最近合同有哪些？","张三负责的客户订单列表"]',
    '销售负责客户最近订单',
    '订单',
    '["销售","客户","订单","合同"]',
    'TOOL',
    '查询销售负责客户最近匹配到的外部订单或合同记录。',
    'SALES_RECENT_ORDER_LIST',
    'sales_recent_order_list',
    '{"specialistName":"{specialistName}","timeRange":"{timeRange}","dataScope":"{dataScope}"}',
    '["customer","mls_agent_data.contract_info"]',
    0.8000,
    '对应 AiAgentChatService.salesRecentOrders。'
  UNION ALL
  SELECT
    '最近有哪些订单？',
    '["最近订单有哪些？","最近有订单吗？","订单有哪些？","最近合同订单列表"]',
    '可见客户最近订单',
    '订单',
    '["客户","订单","合同"]',
    'TOOL',
    '查询当前账号可见客户最近匹配到的外部订单或合同记录。',
    'VISIBLE_RECENT_ORDER_LIST',
    'visible_recent_order_list',
    '{"timeRange":"{timeRange}","dataScope":"{dataScope}"}',
    '["customer","mls_agent_data.contract_info"]',
    0.8000,
    '对应 AiAgentChatService.recentVisibleOrders。'
  UNION ALL
  SELECT
    '张三负责的客户最近有哪些订单正在操作，也就是还没有结束的订单？',
    '["张三负责客户进行中订单有哪些？","张三负责客户未结束订单有哪些？","张三名下客户正在操作的订单有哪些？"]',
    '销售负责客户进行中订单',
    '订单',
    '["销售","客户","订单","进行中","未结束"]',
    'TOOL',
    '查询销售负责客户在外部订单表中尚未结束或正在操作的订单记录。',
    'SALES_CUSTOMER_ACTIVE_ORDER_LIST',
    'sales_customer_active_order_list',
    '{"specialistName":"{specialistName}","activeOnly":true,"dataScope":"{dataScope}"}',
    '["customer","mls_agent_data.contract_info"]',
    0.8000,
    '对应 AiAgentChatService.salesActiveOrders。'
  UNION ALL
  SELECT
    '某客户最近有没有新订单？',
    '["某客户最近有没有新单？","某客户本月有没有新合同？","查询某客户最近新订单"]',
    '客户新订单',
    '订单',
    '["客户","订单","合同","新单"]',
    'TOOL',
    '查询指定客户在外部订单表中的新订单或新合同记录。',
    'CUSTOMER_NEW_ORDER_CHECK',
    'customer_new_order_check',
    '{"customerName":"{customerName}","timeRange":"{timeRange}","dataScope":"{dataScope}"}',
    '["customer","mls_agent_data.contract_info"]',
    0.8000,
    '对应 AiAgentChatService.customerNewOrders。'
  UNION ALL
  SELECT
    '某客户还没有结束的订单有哪些？',
    '["某客户进行中订单有哪些？","某客户正在操作的订单有哪些？","某客户未结束合同有哪些？"]',
    '客户进行中订单',
    '订单',
    '["客户","订单","合同","进行中","未结束"]',
    'TOOL',
    '查询指定客户在外部订单表中尚未结束或正在操作的订单记录。',
    'CUSTOMER_ACTIVE_ORDER_LIST',
    'customer_active_order_list',
    '{"customerName":"{customerName}","activeOnly":true,"dataScope":"{dataScope}"}',
    '["customer","mls_agent_data.contract_info"]',
    0.8000,
    '对应 AiAgentChatService.customerOrders(activeOnly=true)。'
  UNION ALL
  SELECT
    '某客户合同有哪些，状态分别是什么？',
    '["某客户合同状态是什么？","某客户有哪些合同？","某客户订单状态分别是什么？"]',
    '客户合同状态',
    '合同',
    '["客户","订单","合同","状态"]',
    'TOOL',
    '查询指定客户在外部订单表中的合同或订单记录，并展示每条记录的字段和状态。',
    'CUSTOMER_CONTRACT_STATUS_LIST',
    'customer_contract_status_list',
    '{"customerName":"{customerName}","activeOnly":false,"dataScope":"{dataScope}"}',
    '["customer","mls_agent_data.contract_info"]',
    0.8000,
    '对应 AiAgentChatService.customerOrders(activeOnly=false)。'
  UNION ALL
  SELECT
    '某客户的基础信息是什么？',
    '["某客户情况总结","某客户资料汇总","某客户的电话邮箱地址是什么？","某客户基础信息"]',
    '客户基础信息汇总',
    '客户',
    '["客户","基础信息","汇总"]',
    'TOOL',
    '查询 customer 主表中的客户基础信息，并对电话和邮箱做脱敏展示。',
    'CUSTOMER_SUMMARY',
    'customer_summary',
    '{"customerName":"{customerName}","dataScope":"{dataScope}"}',
    '["customer"]',
    0.8000,
    '对应 AiAgentChatService.customerSummary。'
  UNION ALL
  SELECT
    '我没有权限查看的销售、客户或订单能不能查？',
    '["能不能查无权限客户？","能不能返回聊天内容或邮件正文？","所有客户手机号能不能导出？","密码 token 授权码能不能查？"]',
    '权限敏感内容拒绝',
    '权限',
    '["权限","安全","敏感信息"]',
    'STATIC',
    '不能直接返回无权限数据、聊天正文、邮件正文、密码、token、授权码或未授权客户明细。可以在当前账号有权限的范围内返回脱敏后的聚合统计。',
    'SECURITY_REFUSAL',
    'permission_guard',
    '{"dataScope":"{dataScope}"}',
    '[]',
    0.8000,
    '对应 AiAgentChatService.refusal。'
  UNION ALL
  SELECT
    '现在这个智能体能问哪些问题？',
    '["你能回答什么？","支持哪些问题？","可以问你什么？","有哪些推荐问题？"]',
    '智能体能力说明',
    '帮助',
    '["帮助","智能问答","能力说明"]',
    'STATIC',
    '当前支持销售专员与客户沟通统计、销售负责客户列表、客户新订单、进行中订单、合同状态、客户基础信息、长期未跟进客户、沟通少但订单金额高客户、权限与敏感内容说明等问题。',
    'HELP',
    NULL,
    NULL,
    '[]',
    0.8000,
    '对应 AiAgentChatService.fallback。'
) seed ON 1 = 1
WHERE NOT EXISTS (
  SELECT 1
  FROM ai_agent_answerable_question existing
  WHERE existing.organization_id = org.id
    AND existing.intent = seed.intent
);
