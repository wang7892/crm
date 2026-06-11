# AI Agent 通用数据库问答能力开发方案

## 目标

用户希望以后可以直接问以前没有配置过的问题，AI Agent 能理解问题语义，并基于 CRM 数据库给出答案。

示例：

```text
客户来源是展会客户的客户有哪些？
本月新增客户有哪些？
巴西地区客户有多少？
哪些客户没有填写电话？
每个销售名下客户数量是多少？
这个月每个销售新增了多少客户？
最近 30 天没有跟进的客户有哪些？
客户来源为展会客户且负责人是 Administrator 的客户有哪些？
```

这个目标不是“把每个问题都加入问题库”，而是建设一套通用链路：

```text
自然语言问题
-> 根据问题召回相关表字段语义
-> LLM 解析语义
-> 生成受控查询计划
-> 后端校验字段和权限
-> 后端生成 SQL
-> 只读执行数据库查询
-> 生成可读答案
```

大模型负责理解语义，后端负责安全、权限、SQL 和数据真实性。

## 字段含义是否需要人工逐个提供

不需要把每个表、每个字段的含义都人工告诉大模型。

更推荐的方式是：系统自动从项目已有 DDL、数据库元数据、CRM 字段配置表中抽取字段语义，生成一份“数据库语义字典”，再把和当前问题相关的少量字段提供给大模型。

现有字段解释来源包括：

```text
backend/crm/src/main/resources/migration/*/ddl
backend/crm/src/main/resources/migration/*/dml
数据库 information_schema.COLUMNS
sys_module_field
sys_module_field_blob
customer_field / contract_field 等自定义字段表
Java domain / DTO / Mapper XML
前端 table enum / model / locale 文案
```

优先级建议：

1. 当前数据库 `information_schema.COLUMNS` 中的表名、字段名、字段类型、字段注释。
2. CRM 系统字段配置，例如 `sys_module_field`、`sys_module_field_blob` 中的字段名称、内部 key、选项值。
3. 迁移 SQL 中的 `COMMENT`、`MODIFY COLUMN ... COMMENT`、`ALTER TABLE ... ADD COLUMN ... COMMENT`。
4. Java domain、DTO、前端 model 和 locale 中的业务命名。
5. 人工补充的业务别名和敏感级别。

注意：不要理解为“让大模型永久学习数据库”。通过 API 接入的大模型不会因为看过一次 DDL 就永久记住。正确做法是做一个 Schema 语义服务，每次按需把相关表字段放进 prompt，或者用检索方式召回相关 schema。

## Schema 语义字典生成方案

建议新增一个 schema 生成任务，把 DDL 和数据库元数据转成结构化文件或数据库表。

输出示例：

```json
{
  "entity": "customer",
  "table": "customer",
  "label": "客户",
  "fields": [
    {
      "field": "customer_source",
      "column": "customer.customer_source",
      "type": "varchar",
      "comment": "Customer source",
      "businessLabel": "客户来源",
      "aliases": ["客户来源", "来源", "获客来源", "来源渠道"],
      "selectable": true,
      "filterable": true,
      "sortable": false,
      "aggregatable": true,
      "sensitive": false
    },
    {
      "field": "phone",
      "column": "customer.phone",
      "type": "varchar",
      "comment": "电话",
      "businessLabel": "电话",
      "aliases": ["电话", "手机号", "联系方式"],
      "selectable": true,
      "filterable": true,
      "sortable": false,
      "aggregatable": false,
      "sensitive": true,
      "mask": "phone"
    }
  ]
}
```

生成流程：

```text
扫描 migration/*/ddl/*.sql
-> 解析 CREATE TABLE / ALTER TABLE / MODIFY COLUMN / COMMENT
-> 读取当前数据库 information_schema.COLUMNS
-> 合并 sys_module_field / sys_module_field_blob 的业务字段名和选项值
-> 合并人工维护的 aliases、敏感级别、是否可查
-> 输出 ai_agent_schema_catalog
-> AI Agent 每次按问题召回相关 entity 和 fields
```

建议不要每次把所有表所有字段都塞给模型，原因是：

1. token 成本高。
2. 字段太多会让模型更容易选错。
3. 会暴露不该查询的表和字段。
4. 复杂 schema 会让 prompt 不稳定。

推荐先做“粗召回 + 精解析”：

```text
用户问题
-> 根据关键词召回候选实体：customer / contract / follow_up_record
-> 只注入候选实体的可查询字段
-> LLM 输出查询计划
-> 后端白名单再次校验
```

## 是否需要让大模型看到全部数据库

不建议让大模型看到全部数据库 schema。

推荐把 schema 分为三层：

```text
公开给模型的语义层：表业务名、字段业务名、别名、可用操作符
后端执行层：真实表名、真实字段名、join 规则、权限条件
禁止访问层：敏感表、系统表、正文表、token/密码/授权字段
```

模型只看第一层。真实 SQL 生成和权限判断全部在后端完成。

## 第一版允许大模型看到的表范围

当前允许大模型看到以下表的字段名、字段注释、字段业务含义和可用查询操作。这里的“看到”指的是 schema 语义可见，不代表模型可以绕过后端权限直接读取全部数据。

### CRM 主库

```text
customer
sys_user
contract
follow_up_record
wecom_ingestion_session_day
wecom_ingestion_message
wecom_ingestion_media
wecom_ingestion_message_follow_record
email_webhook_event
email_webhook_attachment
```

表含义建议：

```text
customer：客户主表，客户名称、负责人、来源、地区、电话、邮箱、备注等。
sys_user：系统用户表，销售、负责人、创建人、更新人等人员信息。
contract：CRM 合同/订单表，客户、负责人、金额、订单号、合同状态等。
follow_up_record：CRM 跟进记录表，客户跟进时间、跟进方式、跟进内容、负责人等。
wecom_ingestion_session_day：企业微信日会话汇总，一天一条会话统计，用于分析沟通频次。
wecom_ingestion_message：企业微信消息明细，一条聊天消息一条记录。
wecom_ingestion_media：企业微信消息媒体元数据，图片、语音、视频、文件等。
wecom_ingestion_message_follow_record：企业微信消息与 CRM 跟进记录关联表。
email_webhook_event：邮件 Webhook 事件记录，邮件主题、发件人、收件人、状态、关联跟进记录等。
email_webhook_attachment：邮件附件记录，文件名、类型、大小、事件关联等。
```

### 外部合同/订单数据源

外部库：

```text
mls_agent_data
```

第一版只允许这个表进入 schema 语义字典，用于读取合同/订单信息：

```text
mls_agent_data.contract_info
```

`contract_info` 已经被现有 `ExternalOrderTools` 查询，用于外部订单/合同问题，例如订单号、产品名称、负责人、客户、订单状态、金额、交期等。

暂不纳入：

```text
mls_agent_data.customer_info
mls_agent_data.order_info
mls_agent_data.order_timeline
mls_agent_data.order_relevancy
mls_agent_data.conversations
mls_agent_data.messages
mls_agent_data.knowledge_bases
mls_agent_data.knowledge_documents
```

这些表后续如果确实需要，再按新增表流程逐个加入，不作为第一版默认可见 schema。

后续如果需要新增表，不要直接把整个数据库全部开放给 SQL 执行，而是按下面流程添加：

```text
确认表可以用于 AI Agent 问答
-> 读取 information_schema 和字段注释
-> 加入 schema 语义字典
-> 配置字段是否可查询、可过滤、可排序、可聚合
-> 配置敏感字段脱敏规则
-> 配置 join 关系和数据权限规则
-> 加入后端 SQL 白名单
-> 加测试问题验收
```

### 正文类字段处理

邮件正文、企微聊天文本、消息原文、附件下载地址等字段即使字段含义可以给模型看，也建议默认作为敏感字段处理。

建议策略：

```text
允许统计：消息数量、邮件数量、最近沟通时间、是否有沟通。
允许摘要：在明确授权范围内生成脱敏摘要。
默认禁止原文：不直接返回完整邮件正文、完整聊天正文、附件下载链接。
```

如果后续业务上确实要允许查看原文，建议单独做开关和审计，不要混在通用数据库问答里默认开放。

## 当前系统现状

当前项目已经具备 AI Agent 的基础框架：

1. `LlmAiAgentQuestionParser` 已经接入大模型，可以把用户问题解析成 JSON。
2. `AiAgentIntentValidator` 已经有 intent 白名单，避免模型随意生成不可控意图。
3. `AiAgentChatService` 已经负责会话、权限上下文、路由、审计和答案落库。
4. `CustomerTools` 已经有少量客户查询工具。
5. `AiAgentInternalMapper.xml` 已经有客户数据权限片段：
   - `customerPermissionJoin`
   - `customerPermissionWhere`
   - `customerBaseWhere`
6. `AiAgentSqlGuard` 和 `AiAgentSqlQueryService` 已经有受控 SQL 的雏形。

但当前能力仍然偏“固定问题固定工具”，例如：

```text
按客户名称搜索
按销售查询客户
查询客户订单
查询客户沟通情况
```

所以当用户问一个以前没问过的字段问题时，系统无法自动知道：

```text
“客户来源” 对应 customer.customer_source
“展会客户” 是字段值
“有哪些” 是列表查询
```

这就是目前问不出来的根本原因。

## 核心原则

### 1. 不再为每个问题写死 intent

不要为每个具体问法新增一个独立 intent，例如：

```text
CUSTOMER_SOURCE_LIST
CUSTOMER_REGION_LIST
CUSTOMER_AVAILABLE_LIST
CUSTOMER_OWNER_LIST
```

这种方式可以解决一个问题，但无法支持大量未知问题。

推荐新增通用 intent：

```text
DATABASE_QUERY
```

或按业务域拆分为：

```text
CUSTOMER_DATABASE_QUERY
CONTRACT_DATABASE_QUERY
FOLLOW_DATABASE_QUERY
CRM_DATABASE_QUERY
```

初期建议先做 `CUSTOMER_DATABASE_QUERY`，稳定后再扩展到合同、跟进记录、订单、企微消息、邮件记录。

### 2. 模型输出查询计划，不直接输出 SQL

不要让 GPT5.5 直接生成 SQL 并执行。

推荐让模型输出结构化查询计划：

```json
{
  "intent": "CUSTOMER_DATABASE_QUERY",
  "queryType": "LIST",
  "entity": "customer",
  "selectFields": ["name", "customer_source", "owner_name", "region", "follow_time"],
  "filters": [
    {
      "field": "customer_source",
      "operator": "eq",
      "value": "展会客户"
    }
  ],
  "metrics": [],
  "groupBy": [],
  "orderBy": [
    {
      "field": "follow_time",
      "direction": "desc"
    }
  ],
  "limit": 20,
  "needClarification": false,
  "clarificationQuestion": null
}
```

后端只接受这种查询计划，然后根据字段白名单生成 SQL。

### 3. 后端必须做字段白名单和权限注入

模型不能决定哪些表能查、哪些字段能查、是否带组织条件、是否带数据权限。

这些必须由后端控制：

```text
允许查哪些表
允许查哪些字段
字段中文名和数据库字段的映射
哪些字段敏感，需要脱敏
哪些字段允许过滤
哪些字段允许排序
哪些字段允许聚合
每张表必须注入什么权限条件
```

### 4. 所有数据库问答都必须有审计

需要记录：

```text
用户原问题
LLM 输出的查询计划
后端生成的 SQL 模板或脱敏 SQL
实际使用的数据表
返回行数
是否命中敏感字段
是否被权限或字段白名单拦截
```

这样后续可以排查为什么答错、为什么查不到、为什么被拒绝。

## 推荐总体架构

```text
AiAgentChatService
  |
  |-- AiAgentSchemaCatalogService
  |      从 DDL、information_schema、系统字段配置中生成/读取语义字典
  |
  |-- LlmAiAgentQuestionParser
  |      接收相关 schema，输出 ParsedAiAgentQuestion / AiAgentQueryPlan
  |
  |-- AiAgentQueryPlanner
  |      校验 queryType、entity、filters、metrics、groupBy、orderBy
  |
  |-- AiAgentSemanticSchemaService
  |      提供表、字段、别名、关系、敏感级别、权限规则
  |
  |-- AiAgentQueryPermissionService
  |      注入 organization_id、owner、部门、协作客户等权限条件
  |
  |-- AiAgentSqlBuilder
  |      根据查询计划生成参数化 SQL
  |
  |-- AiAgentSqlGuard
  |      最后校验 SQL 只读、白名单表、limit、禁止危险语句
  |
  |-- AiAgentQueryExecutor
  |      使用只读数据源执行 SQL
  |
  |-- AiAgentAnswerRenderer
         把结果渲染成回答、points、citations、warnings
```

## 查询计划 DTO 设计

建议新增 DTO：

```java
public class AiAgentQueryPlan {
    private String intent;
    private String queryType;
    private String entity;
    private List<String> selectFields;
    private List<AiAgentQueryFilter> filters;
    private List<AiAgentQueryMetric> metrics;
    private List<String> groupBy;
    private List<AiAgentQueryOrder> orderBy;
    private Integer limit;
    private Boolean needClarification;
    private String clarificationQuestion;
}
```

过滤条件：

```java
public class AiAgentQueryFilter {
    private String field;
    private String operator;
    private Object value;
}
```

聚合指标：

```java
public class AiAgentQueryMetric {
    private String function;
    private String field;
    private String alias;
}
```

排序：

```java
public class AiAgentQueryOrder {
    private String field;
    private String direction;
}
```

允许的 `queryType`：

```text
LIST       明细列表，例如“展会客户有哪些”
COUNT      数量统计，例如“巴西客户有多少”
AGGREGATE  聚合统计，例如“每个销售有多少客户”
SUMMARY    简要概览，例如“这个客户情况怎么样”
TREND      趋势统计，例如“最近 6 个月新增客户趋势”
```

允许的 `operator`：

```text
eq
ne
like
not_like
in
not_in
gt
gte
lt
lte
between
is_null
not_null
```

## 语义字段白名单

建议新增一个语义层，不要让模型直接认识数据库全部字段。

初期可以先用 Java 配置，后续再落数据库表。

客户字段示例：

```java
customer:
  table: customer
  alias: c
  primaryKey: id
  label: 客户
  fields:
    name:
      column: c.name
      labels: ["客户名称", "名称", "客户"]
      selectable: true
      filterable: true
      sortable: true
      sensitive: false
    full_name:
      column: c.full_name
      labels: ["客户全称", "全称"]
      selectable: true
      filterable: true
      sortable: false
      sensitive: false
    customer_source:
      column: c.customer_source
      labels: ["客户来源", "来源", "获客来源", "来源渠道"]
      values: ["展会客户", "公司客户"]
      selectable: true
      filterable: true
      sortable: false
      sensitive: false
    customer_available:
      column: c.customer_available
      labels: ["是否可用", "可用状态"]
      selectable: true
      filterable: true
      sortable: false
      sensitive: false
    region:
      column: c.region
      labels: ["地区", "国家", "区域"]
      selectable: true
      filterable: true
      sortable: true
      sensitive: false
    phone:
      column: c.phone
      labels: ["电话", "手机号", "联系方式"]
      selectable: true
      filterable: true
      sortable: false
      sensitive: true
      mask: phone
    email:
      column: c.email
      labels: ["邮箱", "邮件地址"]
      selectable: true
      filterable: true
      sortable: false
      sensitive: true
      mask: email
```

后端只允许查询语义层里配置过的字段。

如果用户问了未配置字段，例如：

```text
客户利润率最高的是谁？
```

但当前没有利润率字段，则应该回答：

```text
当前可查询字段中没有“利润率”，暂时不能基于数据库回答这个问题。
```

不能让模型猜答案。

## 数据权限设计

数据库问答必须复用 CRM 当前权限逻辑。

以客户表为例，查询必须包含：

```text
organization_id = 当前组织
共享池过滤
当前账号数据范围
本人 / 部门 / 全部 / 协作客户
```

现有 `AiAgentInternalMapper.xml` 已经有客户权限片段，后续 SQL Builder 或 Mapper 查询需要复用同样逻辑。

如果走通用 SQL Builder，可以把权限规则抽成代码配置：

```java
customer:
  mandatoryWhere:
    - c.organization_id = :orgId
    - (c.in_shared_pool IS NULL OR c.in_shared_pool IS FALSE)
  permissionProvider: CustomerDataPermissionProvider
```

`CustomerDataPermissionProvider` 根据 `DeptDataPermissionDTO` 生成权限条件。

不要让 LLM 自己生成权限条件。

## SQL 生成和执行策略

推荐后端根据查询计划生成参数化 SQL，例如：

用户问：

```text
客户来源是展会客户的客户有哪些？
```

模型输出：

```json
{
  "intent": "CUSTOMER_DATABASE_QUERY",
  "queryType": "LIST",
  "entity": "customer",
  "filters": [
    {
      "field": "customer_source",
      "operator": "eq",
      "value": "展会客户"
    }
  ],
  "limit": 20
}
```

后端生成：

```sql
SELECT c.id,
       c.name,
       c.customer_source,
       c.region,
       c.owner,
       u.name AS owner_name,
       c.follow_time
FROM customer c
LEFT JOIN sys_user u ON c.owner = u.id
WHERE c.organization_id = ?
  AND (c.in_shared_pool IS NULL OR c.in_shared_pool IS FALSE)
  AND c.customer_source = ?
  AND <当前用户数据权限条件>
ORDER BY COALESCE(c.follow_time, c.update_time, c.create_time) DESC
LIMIT 20
```

注意：

1. SQL 必须参数化，不能字符串拼接用户值。
2. 必须强制 `LIMIT`，默认 20，最大建议 100。
3. 只允许 `SELECT`。
4. 禁止 `INSERT`、`UPDATE`、`DELETE`、`DROP`、`ALTER`。
5. 禁止查系统库和非白名单表。
6. 建议用只读数据库账号执行。

## 回答生成策略

数据库返回结果后，不建议再让模型自由发挥。

推荐后端先生成结构化回答：

```text
当前权限范围内找到 1359 个客户，以下展示前 20 个。
```

`points` 中放明细：

```text
第 1 个客户
客户名称：李小龙
客户来源：展会客户
负责人：Administrator
地区：未填写
最近跟进时间：未记录
```

如果需要自然语言润色，可以让模型只基于查询结果做总结，并在 prompt 里明确：

```text
只能基于给定结果回答，不得补充数据库中没有的事实。
```

如果查询结果为空：

```text
当前权限范围内未找到符合条件的数据。
```

不要回答“数据库没有”，因为可能是权限范围内没有。

## 多轮问题支持

后续用户可能继续追问：

```text
这些客户里哪些最近 30 天没跟进？
这些客户分别是谁负责？
只看巴西的。
导出前 50 个。
```

这需要保存上一轮查询计划和结果摘要。

建议在会话中保存：

```text
lastQueryPlan
lastEntity
lastFilters
lastResultIds
lastResultSummary
```

追问时，LLM 可以把“这些客户”解析为上一轮的 `lastResultIds` 或上一轮 filters。

初期可以先不做多轮引用，只支持单轮完整问题。上线后再做多轮。

## 开发阶段规划

### 第一阶段：核心表通用查询 MVP

目标：先让已允许的核心表支持以前没问过的字段查询、列表查询、数量统计和简单分组统计。

支持问题：

```text
客户来源是展会客户的客户有哪些？
巴西地区客户有哪些？
哪些客户没有电话？
客户来源为展会客户且负责人是 Administrator 的客户有哪些？
每个客户来源分别有多少客户？
每个销售名下有多少客户？
本月新增合同有哪些？
每个销售这个月签了多少合同？
最近 30 天没跟进的客户有哪些？
每个客户最近一次企微沟通时间是什么时候？
每个客户最近一次邮件沟通时间是什么时候？
MLS_242241 这个订单是什么状态？
```

需要开发：

1. 新增 `AiAgentQueryPlan` 相关 DTO。
2. 新增 `AiAgentSchemaCatalogService`，从 DDL、`information_schema`、系统字段配置、`mls_agent_data.contract_info` 元数据生成字段语义字典。
3. 修改 `LlmAiAgentQuestionParser`，让模型基于召回的 schema 输出查询计划。
4. 新增 `AiAgentSemanticSchemaService`，维护允许表字段白名单、人工补充别名、敏感级别和 join 关系。
5. 新增 `AiAgentQueryPlanner`，校验实体、字段、操作符、limit。
6. 新增 SQL Builder 或通用 Mapper，支持单表查询和受控 join。
7. 新增 `AiAgentQueryExecutor`，只读执行查询。
8. 新增 `AiAgentAnswerRenderer`，生成列表、统计、聚合回答。
9. `AiAgentChatService` 中增加 `CRM_DATABASE_QUERY` 或分域 `CUSTOMER_DATABASE_QUERY`、`CONTRACT_DATABASE_QUERY`、`COMMUNICATION_DATABASE_QUERY` 路由。

### 第二阶段：合同和订单查询

目标：在第一阶段基础上强化合同、订单、外部 `mls_agent_data` 的分析能力。

支持问题：

```text
本月有哪些新订单？
订单金额最高的前 10 个客户是谁？
每个客户今年订单金额是多少？
哪些订单快到交期？
某个客户最近有哪些合同？
```

需要补充：

1. 合同/订单语义字段白名单。
2. 客户和合同的关联关系。
3. 合同数据权限。
4. 金额、日期、状态等聚合能力。
5. `contract` 与 `mls_agent_data.contract_info` 的匹配规则，例如订单号、客户名、负责人。

### 第三阶段：跟进记录和沟通数据查询

支持问题：

```text
最近 30 天没跟进的客户有哪些？
每个销售这个月跟进了多少次？
有订单但没有跟进记录的客户有哪些？
哪些客户最近邮件很多但企微沟通少？
```

需要补充：

1. 跟进记录字段白名单。
2. 企微消息和邮件事件字段白名单。
3. 客户、跟进记录、消息、邮件之间的安全关联规则。
4. 时间窗口解析。

### 第四阶段：多表联合分析

支持问题：

```text
展会客户里今年下单金额最高的是哪些？
最近 30 天没有跟进但有新订单的客户有哪些？
每个销售负责的展会客户今年订单金额是多少？
```

这一阶段需要更严格的 join 白名单，不能让模型自由决定 join。

## 需要修改或新增的核心文件

建议新增：

```text
backend/crm/src/main/java/cn/cordys/crm/aiagent/dto/query/AiAgentQueryPlan.java
backend/crm/src/main/java/cn/cordys/crm/aiagent/dto/query/AiAgentQueryFilter.java
backend/crm/src/main/java/cn/cordys/crm/aiagent/dto/query/AiAgentQueryMetric.java
backend/crm/src/main/java/cn/cordys/crm/aiagent/dto/query/AiAgentQueryOrder.java
backend/crm/src/main/java/cn/cordys/crm/aiagent/service/AiAgentSchemaCatalogService.java
backend/crm/src/main/java/cn/cordys/crm/aiagent/service/AiAgentSemanticSchemaService.java
backend/crm/src/main/java/cn/cordys/crm/aiagent/service/AiAgentQueryPlanner.java
backend/crm/src/main/java/cn/cordys/crm/aiagent/service/AiAgentQueryPermissionService.java
backend/crm/src/main/java/cn/cordys/crm/aiagent/service/AiAgentSqlBuilder.java
backend/crm/src/main/java/cn/cordys/crm/aiagent/service/AiAgentQueryExecutor.java
backend/crm/src/main/java/cn/cordys/crm/aiagent/service/AiAgentAnswerRenderer.java
```

建议修改：

```text
backend/crm/src/main/java/cn/cordys/crm/aiagent/service/LlmAiAgentQuestionParser.java
backend/crm/src/main/java/cn/cordys/crm/aiagent/service/AiAgentIntentValidator.java
backend/crm/src/main/java/cn/cordys/crm/aiagent/service/AiAgentChatService.java
backend/crm/src/main/java/cn/cordys/crm/aiagent/service/AiAgentSqlGuard.java
```

如果继续使用 MyBatis XML，也可以新增：

```text
backend/crm/src/main/java/cn/cordys/crm/aiagent/mapper/AiAgentQueryMapper.java
backend/crm/src/main/java/cn/cordys/crm/aiagent/mapper/AiAgentQueryMapper.xml
```

## LLM Prompt 设计重点

`LlmAiAgentQuestionParser` 的 system prompt 要从“选择固定 intent”升级成“生成查询计划”。

关键要求：

```text
你是 CRM 数据库问答解析器。
你只输出 JSON，不回答业务问题。
你不能编造字段、表名、客户、金额、数量。
你只能使用给定的实体、字段、操作符。
如果问题中的字段无法映射到白名单字段，设置 needClarification=true。
如果问题缺少必要条件，但可以查询全量可见数据，则不追问。
如果问题有歧义，优先追问。
不要输出 SQL。
```

Prompt 中要动态注入语义字段：

```text
可查询实体：
- customer：客户
- sys_user：系统用户、销售、负责人
- contract：CRM 合同/订单
- follow_up_record：跟进记录
- wecom_ingestion_message：企业微信消息
- email_webhook_event：邮件事件
- mls_agent_data.contract_info：外部订单/合同明细

customer 可查询字段：
- name：客户名称、名称、客户
- full_name：客户全称、全称
- customer_source：客户来源、来源、获客来源
- customer_available：是否可用、可用状态
- region：地区、国家、区域
- phone：电话、手机号、联系方式
- email：邮箱、邮件地址
- owner_name：负责人、销售、归属人
- follow_time：最近跟进时间、上次跟进时间
- create_time：创建时间、新增时间
```

实际 prompt 不需要一次注入所有实体字段。推荐先根据用户问题召回 1 到 3 个候选实体，再注入这些实体的字段说明、允许 join 关系和示例。

## 安全边界

必须拒绝或限制以下问题：

```text
查询所有客户手机号
导出所有客户邮箱
查看没有权限的客户
查看聊天正文
查看邮件正文
查看密码、token、授权码
删除、修改、更新数据
查询系统表
查询非白名单表
```

敏感字段可以允许在有权限时脱敏返回，例如：

```text
电话：138****5371
邮箱：ma***@example.com
```

## 验收问题

第一阶段完成后，用这些问题验收：

```text
客户来源是展会客户的客户有哪些？
展会客户有哪些？
公司客户有多少？
每个客户来源分别有多少客户？
巴西地区客户有哪些？
哪些客户没有电话？
负责人是 Administrator 的客户有哪些？
客户来源是展会客户并且地区是巴西的客户有哪些？
这个月新增客户有哪些？
每个销售这个月新增客户数量是多少？
```

期望结果：

1. 未配置过的问法也能解析成 `CUSTOMER_DATABASE_QUERY`。
2. 模型输出的是查询计划，不是 SQL。
3. 后端根据字段白名单生成 SQL。
4. SQL 自动注入组织和数据权限。
5. 查询结果只包含当前账号可见数据。
6. 明细查询默认限制返回数量。
7. 统计查询能返回数量或分组结果。
8. 不支持的字段会明确说明不能查询，而不是编造答案。

## 下一步建议

建议下一步不要继续补单个问题规则，而是先开发“核心表通用查询 MVP”。

最小闭环：

```text
CRM_DATABASE_QUERY
-> customer / sys_user / contract / follow_up_record / wecom / email / mls_agent_data.contract_info 字段白名单
-> LLM 查询计划
-> QueryPlan 校验
-> CRM 权限注入
-> 参数化 SQL Builder
-> 只读查询执行
-> 列表/数量/分组回答
```

这个闭环做好后，“客户来源是展会客户的客户有哪些”只是其中一个普通查询，后续“地区是巴西的客户有哪些”“哪些客户没有电话”“每个销售多少客户”“最近 30 天没跟进的客户有哪些”“某个 MLS 订单状态是什么”都能用同一套能力回答。
