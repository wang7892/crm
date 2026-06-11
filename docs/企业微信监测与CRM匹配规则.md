# 企业微信监测数据与 CRM「联系专员 / 客户」匹配规则

本文说明：在已写入企业微信监测事件（`wecom_message_event`）或 CRM 缓冲表（`wecom_ingestion_event`）的前提下，如何从**消息方向**与**企微侧 ID** 解析出「联系专员」与「客户」，并在 **cordys-crm** 库中落到 **系统用户**与**客户主数据**。

---

## 1. 涉及的数据表

| 库 / 表 | 用途 |
|--------|------|
| 监测库 `wecom_message_event` 或 CRM `wecom_ingestion_event` | 单条消息一条记录；含方向、发送方/接收方企微 ID、群 ID、快照等 |
| CRM `sys_user` | 内部用户（联系专员）；企微成员 ID 存于 **`wecom_id`**（见迁移 `V1.6.0_7__sys_user_wecom_id.sql`） |
| CRM `customer_field` | 客户自定义字段；**`field_value`** 中可存客户企微 **外部联系人 ID**；**`resource_id`** 指向客户主键 |
| CRM `customer` | 客户主表；**`id`** 与 `customer_field.resource_id` 对应 |

> **注意**：`customer_field.field_value` 在业务上可能对应多种「自定义字段」。实际匹配时，应限定为**专门存放企微外部联系人 ID** 的那条字段定义（通过 `field_id` 与系统内「客户字段配置」对齐），避免误匹配其它类型的字段值。

---

## 2. 缓冲 / 监测表中的关键列（概念）

以下列名以 `wecom_ingestion_event` / `wecom_message_event` 为准（两表语义一致）。

| 列 | 含义 |
|----|------|
| `message_direction` | `OUTBOUND`：企业侧发出；`INBOUND`：外部侧发来 |
| `sender_userid` | 发送方为企业成员时的 **userid**（内部员工） |
| `sender_external_userid` | 发送方为外部联系人时的 **external_userid** |
| `peer_userid` | 对端为企业成员时的 **userid**（常用于 INBOUND 时标识接待员工） |
| `external_userid` / `matched_external_userid` | 与消息关联的外部联系人 ID；**匹配 CRM 客户时优先使用 `matched_external_userid`（非空时），否则用 `external_userid` 或 `sender_external_userid` 等业务约定列** |
| `roomid` | 非空表示**群聊** |
| `room_external_snapshot` | 群内外部成员快照（JSON），用于群场景下辅助判断「谁在群里」 |

---

## 3. 从一条事件解析「联系专员的企微 userid」与「客户的企微 external_userid」

### 3.1 单聊：联系专员 → 客户（OUTBOUND）

- **联系专员（企微）**：`sender_userid`
- **客户（企微）**：通常取 `matched_external_userid`，若为空则取 `external_userid`（与入库实现一致）

### 3.2 单聊：客户 → 联系专员（INBOUND）

- **客户（企微）**：`sender_external_userid`（发送方为外部联系人）
- **联系专员（企微）**：`peer_userid`（对端为企业成员，即接待/跟进员工）

### 3.3 群聊：联系专员在群里发消息（OUTBOUND）

- **联系专员（企微）**：`sender_userid`（群内发言的企业成员）
- **客户（企微）**：若单条消息能明确一个外部联系人，用 `matched_external_userid` / `external_userid`；若消息本身未带单个客户 ID，则需结合 **`room_external_snapshot`** 与业务规则（例如：群内仅一名外部联系人时视为该客户；或取「当前会话绑定的客户」等）——**监测层可能只保证「发言员工 + 群」准确，具体客户需产品规则补全**。

### 3.4 群聊：客户在群里发消息（INBOUND）

- **客户（企微）**：`sender_external_userid`
- **联系专员（企微）**：`peer_userid`（若回调里对端员工明确）；若 `peer_userid` 为空，则需从 **`room_external_snapshot`** 中解析群内企业成员，再结合「谁是被监测的跟进人」配置或会话负责人表确定专员——同样属于**业务策略**，本文只约定优先使用 `peer_userid`。

---

## 4. CRM 匹配：联系专员 → `sys_user`

**规则**：将上节得到的 **联系专员企微 userid** 与 `sys_user.wecom_id` 做等值匹配。

```sql
-- :wecom_userid 为解析出的联系专员 userid（如 zhangsan）
SELECT id, name, wecom_id
FROM sys_user
WHERE wecom_id = :wecom_userid
  AND deleted = 0
LIMIT 1;
```

- 匹配到 0 条：专员未在 CRM 维护 `wecom_id`，需主数据补齐。
- 匹配到多条：数据异常，应告警并人工处理。

---

## 5. CRM 匹配：客户 → `customer`（经 `customer_field`）

**规则**（与您提供的业务说明一致）：

1. 用上节得到的 **客户企微 external_userid**，在 `customer_field` 中查找 **`field_value`** 相等的行（且限定正确的 `field_id` / 字段含义为「企微外部联系人 ID」）。
2. 取该行的 **`resource_id`**。
3. 用 `resource_id` 与 `customer.id` 等值关联，得到客户主数据。

```sql
-- :external_userid 为解析出的客户 external_userid
-- :field_id 为「存企微外部联系人 ID」的客户自定义字段 ID（由配置表或常量给出）

SELECT c.id, c.name
FROM customer_field cf
JOIN customer c ON c.id = cf.resource_id AND c.deleted = 0
WHERE cf.field_id = :field_id
  AND cf.field_value = :external_userid
LIMIT 1;
```

说明：

- 若 `field_value` 在库中为 JSON / 数组类型，需与现有「客户自定义字段」存储格式一致后再比较（例如先规范化再比对）。
- 若同一 `external_userid` 对应多条 `customer_field`（重复数据），应去重策略或人工介入。

---

## 6. 四种场景对照表（速查）

| 场景 | `message_direction` | 联系专员 userid 来源 | 客户 external_userid 来源 |
|------|---------------------|----------------------|---------------------------|
| 专员私聊发给客户 | OUTBOUND | `sender_userid` | `matched_external_userid` 优先，否则 `external_userid` 等 |
| 客户私聊发给专员 | INBOUND | `peer_userid` | `sender_external_userid` |
| 专员在客户群发言 | OUTBOUND | `sender_userid` | 优先 `matched_external_userid` / `external_userid`；否则结合 `roomid` + `room_external_snapshot` 与业务规则 |
| 客户在客户群发言 | INBOUND | `peer_userid`（优先）；否则快照 + 业务规则 | `sender_external_userid` |

---

## 7. 与监测设计方案的关联

- 表结构与字段说明见：**`docs/企业微信监测系统设计方案.md`**
- 建表脚本：**`wecom monitoring/docs/mysql_企业微信监测库.sql`**、**`wecom monitoring/docs/mysql_cordys_crm_企业微信表.sql`**

---

## 8. 实施建议

1. **统一解析函数**：输入一行 `wecom_*_event`，输出 `(specialist_wecom_userid, customer_external_userid)` 两个可选字符串，再分别走第 4、5 节 SQL。
2. **群聊缺省字段**：当客户 external_userid 无法唯一确定时，不要强行写错关联；应标记「待人工 / 待会话绑定」。
3. **审计**：保留原始 `wecom_msg_id`、`roomid`、快照 JSON，便于事后核对匹配结果。
