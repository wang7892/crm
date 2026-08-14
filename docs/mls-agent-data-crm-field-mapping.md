# mls_agent_data 与 Cordys CRM 字段对应关系

## 1. 文档范围

- 外部数据源：`47.110.46.27:3306/mls_agent_data`
- CRM 数据库：`cordys-crm`
- 客户表：`mls_agent_data.customer_info` -> `cordys-crm.customer`
- 合同表：`mls_agent_data.contract_info` -> `cordys-crm.contract`
- 加工单表：`mls_agent_data.order_info` -> `cordys-crm.sales_order`
- 整理日期：2026-07-18

客户表对应关系按《同步对应字段.docx》原文整理；合同表对应关系按当前 CRM 合同实体和外部数据库实际表结构整理；加工单对应关系按当前 CRM 同步服务、实体模型和数据库迁移脚本整理。

## 2. 客户表字段对应关系

| 序号 | `customer_info` 源字段 | `customer` 目标字段 | 映射说明 |
| ---: | --- | --- | --- |
| 1 | `customer_name` | `name` | 直接映射 |
| 2 | `customer_full_name` | `full_name` | 直接映射 |
| 3 | `customer_type` | （未映射） | Word 原文中的目标字段为空，暂不推测目标字段 |
| 4 | `credit_limit` | `credit_limit` | 直接映射 |
| 5 | `address` | `address` | 直接映射 |
| 6 | `telephone` | `phone` | 字段改名映射 |
| 7 | `email` | `email` | 直接映射 |
| 8 | `remark` | `remark` | 直接映射 |
| 9 | `is_usable` | `customer_available` | 字段改名映射 |
| 10 | `customs_code` | `customs_code` | 直接映射 |
| 11 | `region` | `region` | 直接映射 |

## 3. 合同表字段对应关系

外部 `contract_info` 当前包含生产、包装、结算等完整信息；CRM `contract` 实体目前只保留合同核心字段。以下是当前可以直接或通过关联转换写入 CRM 的字段。

### 3.1 源字段到 CRM 字段

| 序号 | `contract_info` 源字段 | `contract` 目标字段 | CRM 目标类型 | 映射或转换规则 |
| ---: | --- | --- | --- | --- |
| 1 | `id` | （同步幂等键） | `BIGINT` | 外部自增主键，不能直接覆盖 CRM `contract.id`；建议保存到合同同步映射表或新增外部 ID 字段，用于更新和追踪 |
| 2 | `order_no` | `number` | `VARCHAR(50)` | 去除首尾空白后直接映射；外部当前无重复，可作为合同业务关联键 |
| 3 | `product_name` | `name` | `VARCHAR(255)` | 去除首尾空白后直接映射 |
| 4 | `customer` | `customer_id` | `VARCHAR(32)` | 不能直接写名称；按外部客户名称匹配当前组织的 CRM `customer.name`/`customer.full_name`，仅唯一匹配时写入客户 ID |
| 5 | `manager` | `owner` | `VARCHAR(32)` | 按本组织 CRM 用户 ID/名称唯一匹配；匹配不到或匹配不唯一时写 `admin` |
| 6 | `amount` | `amount` | `DECIMAL(20,10)` | 转换为十进制数后写入 |
| 7 | `order_status` | `contract.order_status` | `VARCHAR(255)` | 直接写入合同主表；历史自定义字段 `field_id=177980251841700000` 由迁移脚本回填后清理，页面字段内部键为 `orderStatus` |
| 8 | `currency` | `contract.currency` | `VARCHAR(20)` | 直接写入合同主表；历史自定义字段 `field_id=177977881820900000`、内部键 `orderCurrency` 由迁移脚本回填后清理 |
| 9 | `approval_status` | （不映射） | `VARCHAR(50)` | 这是 CRM 审批流状态，不是页面“订单状态”；外部数值状态不参与 CRM 审批流，新建合同使用 `NONE`，更新保留原值 |
| 10 | `release_date` | `start_time` | `BIGINT` | 对应页面“下单日期”；外部 `DATETIME` 转换为 Unix 毫秒时间戳，空值回退到外部 `create_time` |
| 11 | `delivery_date` | `end_time` | `BIGINT` | 对应页面“生产交期”；外部 `DATETIME` 转换为 Unix 毫秒时间戳，空值回退到 `start_time` |
| 12 | `creator` | `create_user` | `VARCHAR(50)` | 按客户表规则直接保存外部创建人文本，不匹配 CRM 用户 ID；页面匹配不到用户时回退显示原始值 |
| 13 | `create_time` | `create_time` | `BIGINT` | 外部 `DATETIME` 转换为 Unix 毫秒时间戳 |
| 14 | `updater` | `update_user` | `VARCHAR(50)` | 按客户表规则直接保存外部更新人文本，不匹配 CRM 用户 ID；页面匹配不到用户时回退显示原始值 |
| 15 | `update_time` | `update_time` | `BIGINT` | 外部 `DATETIME` 转换为 Unix 毫秒时间戳 |
| 16 | 当前组织配置 | `organization_id` | `VARCHAR(32)` | 写入 CRM 组织 ID `100001`（木林森）；不从外部表推断 |

### 3.2 外部存在但 CRM 当前没有直接字段的列

以下字段在外部 `contract_info` 中存在，但 CRM `contract` 当前实体没有对应的核心列。除非后续新增 CRM 自定义字段，否则暂不写入核心合同表：

```text
composition, width, weight, total_quantity, unit,
quality_requirement, process_technology, packaging_requirement, remark,
hot_stamping_requirement, shipping_mark, shipping_sample_remark,
settlement_method, delivery_method, overload_percent, shortage_percent,
process_order_no, exchange_rate, work_instruction,
length_coefficient, inventory_enabled,
delivery_date 以外的其它日期扩展字段
```

其中外部 `order_status` 已确认写入 CRM 合同主表 `order_status`，不应映射到 CRM `approval_status`；`process_technology` 等字段如果后续需要展示，应新增 CRM 合同自定义字段或扩展表。

### 3.3 页面部门和审计字段规则

| 页面字段 | 当前 CRM 实际来源 | 同步处理 |
| --- | --- | --- |
| 部门 | `contract` 主表没有 `department` 列，普通合同由负责人/创建人所属组织关系计算 | 通过 `mls_sync_mapping` 精确识别同步合同，列表、详情和快照固定显示“木林森”；不把组织 ID 误写成部门 ID，部门筛选仍沿用 CRM 原有的负责人权限关系 |
| 创建人 | `contract.create_user` | 外部 `creator` 原样保存；CRM 用户匹配成功显示用户名称，否则显示外部文本 |
| 创建时间 | `contract.create_time` | 使用外部 `create_time` |
| 更新人 | `contract.update_user` | 外部 `updater` 原样保存；CRM 用户匹配成功显示用户名称，否则显示外部文本 |
| 更新时间 | `contract.update_time` | 使用外部 `update_time` |

### 3.4 已确认的合同同步规则

1. `contract_info.id` 保存到独立的 `mls_sync_mapping`，不覆盖 CRM 合同主键。
2. 外部 `manager` 匹配不到唯一 CRM 用户时使用 `admin` 作为合同负责人。
3. 外部 `approval_status` 不映射 CRM 审批流；新建合同使用 `NONE`，更新时保留 CRM 原审批状态。
4. `release_date` 为空时回退到外部 `create_time`；`delivery_date` 为空时回退到 `start_time`。
5. `V1.6.0_44` 已将客户、合同的 `create_user/update_user` 扩展到 `VARCHAR(50)`，页面对非 CRM 用户 ID 回退显示原始文本。

## 4. 加工单字段对应关系

### 4.1 源字段到 CRM 字段

| 序号 | `order_info` 源字段 | `sales_order` 目标字段 | CRM 目标类型 | 映射或转换规则 |
| ---: | --- | --- | --- | --- |
| 1 | `id` | （当前导入批次定位键） | `BIGINT` | 写入 `mls_sync_mapping.source_id`；外部表清空重导后 ID 可能重排，因此不能单独作为长期业务身份，CRM 目标订单优先按旧来源 ID + 行哈希、再按相同行哈希重新绑定 |
| 2 | `order_no` | `order_no` | `VARCHAR(50)` | 去除首尾空白后写入；同时作为合同关联键 |
| 3 | `process_order_no` | `process_order_no` | `VARCHAR(50)` | 去除首尾空白后直接映射 |
| 4 | `processor` | `processor` | `VARCHAR(100)` | 去除首尾空白后直接映射 |
| 5 | `merchandiser` | `merchandiser` | `VARCHAR(50)` | 去除首尾空白后直接映射 |
| 6 | `status` | `status` | `VARCHAR(50)` | 去除首尾空白后直接映射 |
| 7 | `color` | `color` | `VARCHAR(50)` | 去除首尾空白后直接映射 |
| 8 | `color_code` | `color_code` | `VARCHAR(50)` | 去除首尾空白后直接映射 |
| 9 | `composition` | `composition` | `VARCHAR(200)` | 去除首尾空白后直接映射 |
| 10 | `material_name` | `material_name` | `VARCHAR(100)` | 去除首尾空白后直接映射 |
| 11 | `material_type` | `material_type` | `VARCHAR(50)` | 去除首尾空白后直接映射 |
| 12 | `process_technology` | `process_technology` | `VARCHAR(100)` | 去除首尾空白后直接映射 |
| 13 | `order_time` | `order_time` | `BIGINT` | 转换为 Unix 时间戳，单位为毫秒 |
| 14 | `quantity` | `quantity` | `DECIMAL(15,2)` | 转换为 `BigDecimal` 后写入 |
| 15 | `unit` | `unit` | `VARCHAR(20)` | 去除首尾空白后直接映射 |
| 16 | `unit_price` | `unit_price` | `DECIMAL(15,2)` | 转换为 `BigDecimal` 后写入 |
| 17 | `amount` | `amount` | `DECIMAL(20,10)` | 转换为 `BigDecimal` 后写入 |
| 18 | `currency` | `currency` | `VARCHAR(20)` | 去除首尾空白后直接映射 |
| 19 | `order_timeline.warehouse_actual_ship_date` | `warehouse_actual_ship_date` | `BIGINT` | 按唯一的 `order_timeline.order_no = order_info.order_no` 关联；外部 `DATETIME` 转换为 Unix 毫秒时间戳，无时间记录时写入 `NULL` |

### 4.2 CRM 派生字段

以下字段不直接来自 `order_info` 的同名列，而是在同步时由 CRM 关联数据或当前操作上下文生成。

| 依据 | `sales_order` 目标字段 | 生成规则 |
| --- | --- | --- |
| `order_info.order_no`、当前 `organization_id` | `contract_id` | 查找 `contract.number = order_info.order_no` 且 `contract.organization_id = 当前组织` 的合同，写入 `contract.id` |
| 已匹配的 `contract` | `customer_id` | 写入 `contract.customer_id` |
| 已匹配的 `contract` | `owner` | 写入去除首尾空白后的 `contract.owner`，字段含义为联系专员 |
| 当前组织上下文 | `organization_id` | 新建订单时写入当前组织 ID |
| CRM 服务器当前时间 | `create_time` | 新建订单时写入当前毫秒时间戳 |
| CRM 服务器当前时间 | `update_time` | 新建或更新订单时写入当前毫秒时间戳 |
| 同步系统账号 | `create_user` | 新建订单时写入 `admin`；`order_info` 没有创建人字段 |
| 同步系统账号 | `update_user` | 新建或更新订单时写入 `admin`；`order_info` 没有更新人字段 |

如果根据 `order_no` 没有找到本组织内同步状态为 `SUCCESS` 的唯一合同，当前订单整行同步失败并记录到 `mls_sync_run_error`，不会创建缺少客户或合同关联的孤立订单。

## 5. 加工单同步规则

1. 同步查询固定读取上述 18 个 `order_info` 字段，并按唯一订单号左连接读取 `order_timeline.warehouse_actual_ship_date`，再按 `order_info.id` 升序分页；`store_out.container_time` 当前不参与同步。
2. 每次任务都从最小 `id` 开始全量扫描，默认每页 2,000 条，最大每页 10,000 条；`pageSize` 只控制单页大小，不限制同步总量。
3. `id` 或 `order_no` 为空时该行同步失败，并写入失败审计。
4. 字符串值统一去除首尾空白，空字符串转换为 `NULL`。
5. `quantity`、`unit_price`、`amount` 转换为十进制数；无法转换时该条同步失败并被跳过。
6. `order_time` 支持时间对象、秒或毫秒时间戳，以及常用的 `yyyy-MM-dd`、`yyyy/MM/dd` 和带时分秒的日期字符串；最终统一保存为毫秒时间戳。
7. CRM 通过 `mls_sync_mapping` 定位目标订单；外部 ID 重排时优先复用原来源 ID 且哈希相同的目标，再复用相同行哈希的目标。目标不存在时按行哈希及重复序号生成确定性 CRM ID；目标内容被 CRM 正常编辑后会在下一轮按 `update_time` 检测并恢复外部受控字段。
8. 旧版曾使用的 `sales_order.external_order_info_id` 已由迁移脚本删除，当前版本不再使用该字段保存外部 ID。

## 6. 校验状态与注意事项

- 客户表映射已按 Word 原文逐项转写，其中 `customer_type` 的目标字段确实为空。
- 合同表当前已整理 CRM 核心字段，并确认“币种”“订单状态”提升到合同主表 `contract.currency`、`contract.order_status`；历史自定义字段由 `V1.6.0_43_1__promote_contract_order_status_currency.sql` 迁移后清理，外部其它字段需要后续业务确认后再决定是否新增 CRM 自定义字段。
- 加工单映射已与 `ExternalOrderInfoSyncService` 的实际查询字段和赋值逻辑逐项核对，并与 `sales_order` 最新迁移字段类型核对。
- 2026-07-18 已以只读方式连接 `47.110.46.27:3306/mls_agent_data` 和当前 CRM 数据库，核对了三张外部表与三张 CRM 目标表的实际字段类型、长度和关键统计；本文合同字段类型以本次核对结果为准。
- 源字符串如果超过 CRM 目标字段长度，可能因数据库 SQL 模式不同而报错或被截断，应在正式全量同步前做长度检查。
- `order_time` 以及客户、合同日期统一按 `Asia/Shanghai` 转换为时间戳；Quartz 也显式使用该时区在每天 `00:00` 触发。
- 外部 ID 与 CRM ID 的关系以 `mls_sync_mapping` 为准；`V1.6.0_45` 会解除历史重复目标映射并建立目标唯一索引，不删除对应 CRM 业务记录。
- 目标漂移判断依赖 CRM 正常更新路径同步修改 `update_time`。其它运维脚本如果直接修改三张目标主表，也必须同时更新该列。

## 7. 整理依据

- `C:/Users/34850/OneDrive/Desktop/同步对应字段.docx`
- `backend/crm/src/main/java/cn/cordys/crm/contract/domain/Contract.java`
- CRM 本地数据库 `sys_module_field`、`contract_field`（2026-07-18 只读核对）
- `backend/crm/src/main/java/cn/cordys/crm/order/service/ExternalOrderInfoSyncService.java`
- `backend/crm/src/main/java/cn/cordys/crm/order/domain/Order.java`
- `backend/crm/src/main/resources/migration/1.6.0/ddl/V1.6.0_32__sales_order_main_order_info_fields.sql`
- `backend/crm/src/main/resources/migration/1.6.0/ddl/V1.6.0_35__sales_order_drop_legacy_columns_and_reorder.sql`
- `backend/crm/src/main/resources/migration/1.6.0/ddl/V1.6.0_48__mls_mirror_sync_protection.sql`
- `backend/crm/src/main/resources/migration/1.6.0/ddl/V1.6.0_49__sales_order_warehouse_actual_ship_date.sql`

## 8. 自动同步实现（V1.6.0_44 / V1.6.0_45 / V1.6.0_48）

已落地的同步服务为 `MlsAgentDataSyncService`，由 CRM 内置 Quartz 每天 `00:00` 执行，顺序固定为：

```text
customer_info -> contract_info -> order_info
```

- 外部数据库连接沿用只读的 `aiAgentExternalOrderJdbcTemplate`；服务只对本地 `cordys-crm` 写入。
- `mls_sync_mapping` 以 `organization_id + source_table + source_id` 保证当前导入批次唯一，外部 ID 不会覆盖 CRM 主键。合同长期身份使用唯一合同编号；订单使用旧来源 ID、行哈希及重复序号重新绑定。客户只复用唯一的“公司客户”同名记录，展会客户不会被合并或删除。
- 客户、合同、订单的目标映射保持一对一；跨组织主键冲突会将该源行标记失败，不会通过 upsert 改写其它组织的数据。下游只使用状态为 `SUCCESS` 的客户/合同映射。
- 客户同步写入来源“公司客户”、`is_usable` 的 `1/0`，领取时间写本次同步时间；企微、群聊、跟进、公海等外部不存在的列不参与更新。客户负责人在合同阶段按最新合同 `update_time` 的 `manager` 回填，用户不存在或不唯一时写 `admin`。
- 合同负责人按本组织 CRM 用户 ID/姓名匹配，匹配不到写 `admin`；外部 `approval_status` 不映射到 CRM 审批流，新建合同使用 `NONE`，更新时保留 CRM 原审批状态。`release_date`/`delivery_date` 为空时分别回退到外部 `create_time`/`start_time`，以满足 CRM 两列的非空约束。
- 合同和客户的外部 `creator/updater` 原文及时间会同步，V1.6.0_44 将 `create_user/update_user` 扩展为 `VARCHAR(50)`；页面对找不到 CRM 用户的审计文本显示原文。
- MLS 合同通过映射表识别后在列表、详情和快照中固定显示部门“木林森”；手工合同不受影响。
- `order_info` 没有更新时间字段，因此每天按 `id` 全量分页读取，并以行哈希跳过未变化数据；行哈希包含 `order_timeline.warehouse_actual_ship_date`，因此仓库实际发货日期变化时会更新订单。客户、合同也采用全量扫描和哈希幂等，避免旧 ID 更新被遗漏。只有外部哈希和目标更新时间都保持上次同步状态时才跳过。
- 合同和订单采用受保护的镜像清理：外部数量为 0，或低于清理前 MLS 映射数量的 90% 时，本轮合同/订单镜像阶段整体停止且不推进缺失次数；读取期间外部数量或最大 ID 发生变化、存在失败行时，也不推进缺失次数。
- 只有同一 MLS 映射连续两次“完整且通过数量保护”的同步都未出现，才进入删除。先删除订单及其订单字段/快照，再删除合同；没有 MLS 映射的 CRM 手工订单、手工合同始终不参与镜像清理。
- 外部已不存在的合同只要仍有关联订单、回款计划、回款记录、发票、快照或合同字段值，就保留合同及关联数据，将映射标记为 `CONFLICT`，并在 `mls_sync_run_error` 记录 `DELETE_CONFLICT_RELATED_DATA`，不自动删除。
- 启用 `crm.mls-sync.enabled=true` 后，旧版 `ExternalOrderInfoSyncService` 手工导入入口停止写入，避免再次产生没有 MLS 映射、无法参与镜像判断的批量订单。
- `mls_sync_run`、`mls_sync_run_error` 和 `mls_sync_checkpoint` 保留运行统计、失败行和最近高水位；Redis 锁 `crm:mls-sync:{organizationId}` 防止定时任务与手动任务并发。

### 8.1 配置

在生产服务器 `/data/CORDYSCRM/conf/cordys-crm.properties`（或本地私有 `cordys-crm.properties`）配置只读外部账号。生产连接优先使用校验服务端身份的 TLS，`<MLS_DB_TLS_HOST>` 必须与证书 SAN 匹配：

```properties
crm.ai-agent.external-order.enabled=true
crm.ai-agent.external-order.url=jdbc:mysql://<MLS_DB_TLS_HOST>:3306/mls_agent_data?useUnicode=true&characterEncoding=UTF-8&sslMode=VERIFY_IDENTITY&trustCertificateKeyStoreUrl=file:/data/CORDYSCRM/conf/mls-agent-data-ca.p12&trustCertificateKeyStorePassword=<truststore-password>&trustCertificateKeyStoreType=PKCS12
crm.ai-agent.external-order.username=<read-only-user>
crm.ai-agent.external-order.password=<read-only-password>
crm.ai-agent.external-order.connection-timeout=5000
crm.ai-agent.external-order.socket-timeout=120000
crm.ai-agent.external-order.query-timeout-seconds=120
quartz.time-zone=Asia/Shanghai
crm.mls-sync.enabled=true
crm.mls-sync.organization-id=100001
crm.mls-sync.page-size=2000
```

公网 MySQL 使用 `useSSL=false` 会暴露认证与业务数据传输风险，不应作为生产方案；外部库暂不支持合规 TLS 时，应使用 VPN、专线或等效加密私网。账号、密码、信任库密码和证书材料只能保存在服务器私有配置中，不得提交仓库。

手动验证入口：`POST /integration/mls-agent-data/sync`，需要 `SYSTEM_SETTING:UPDATE` 权限以及已登录会话的 `X-AUTH-TOKEN`、`CSRF-TOKEN`、`Organization-Id` 请求头。请求体可传 `{"pageSize": 500}` 调整单页大小，但仍会按“客户 -> 合同 -> 订单”扫描全部源数据，并不限制总同步条数。运行状态和失败记录直接查询本地 `mls_sync_run`、`mls_sync_run_error`，不要查询或写入外部库。
