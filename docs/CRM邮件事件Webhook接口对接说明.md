# CRM 邮件事件 Webhook 接口对接说明

## 1. 文档目的

本说明用于指导 CRM 系统实现“邮件监控系统”的事件接收接口。  
邮件监控系统负责识别“发件邮箱发送给客户邮箱”的行为，并通过 Webhook 推送事件到 CRM。  
CRM 接口收到事件后，可自行完成自动创建跟进、客户匹配、告警等业务逻辑。

---

## 2. 接口概览

- 方法：`POST`
- 路径（建议）：`/api/webhook/email-log`
- Content-Type：`application/json`
- 字符编码：`UTF-8`

说明：
- 监控系统当前默认推送路径是 `/api/webhook/email-log`；
- 如 CRM 使用其他路径，请同步给监控系统配置 `CRM_WEBHOOK_PATH`。

---

## 3. 请求头规范

监控系统可携带以下请求头：

- `Content-Type: application/json; charset=UTF-8`
- `Organization-Id: <organizationId>`（可选但建议）
- `Authorization: <accessKey>:<signature>`（可选；若配置了 Key 则会携带）

鉴权建议：
- 内网联调阶段：可先不校验 `Authorization`，只校验来源 IP；
- 正式环境：建议按你们现有 API Key 规则校验签名。

---

## 4. 请求体字段定义

```json
{
  "organizationId": "org001",
  "sourceMailbox": "19714514739@163.com",
  "messageId": "<202604202233465383970@163.com>",
  "threadId": "thread-001",
  "fromAddress": "19714514739@163.com",
  "toAddresses": ["3485069195@qq.com", "customer2@xx.com"],
  "ccAddresses": [],
  "subject": "报价沟通",
  "contentText": "邮件正文纯文本...",
  "sendTime": 1777000000000,
  "matchedTargetMailbox": "3485069195@qq.com"
}
```

字段说明：

- `organizationId`：组织ID（与监控系统配置一致）
- `sourceMailbox`：被监控的发件邮箱
- `messageId`：邮件唯一标识（建议作为幂等键核心字段）
- `threadId`：邮件线程ID（可能为空）
- `fromAddress`：发件人地址
- `toAddresses`：收件人列表
- `ccAddresses`：抄送列表
- `subject`：邮件主题
- `contentText`：正文纯文本（可能为空）
- `sendTime`：发送时间毫秒时间戳
- `matchedTargetMailbox`：本次命中的客户邮箱（在目标邮箱列表中匹配到的那一个）

---

## 5. CRM 响应规范（建议）

成功返回（HTTP 200）：

```json
{
  "success": true,
  "eventId": "evt-20260421-0001"
}
```

失败返回（HTTP 4xx/5xx）：

```json
{
  "success": false,
  "code": "INVALID_PARAM",
  "message": "customer mailbox not found"
}
```

说明：
- 监控系统将根据 HTTP 状态码判断是否成功；
- 非 2xx 会触发重试。

---

## 6. 幂等处理要求（CRM 必做）

建议 CRM 侧使用以下幂等键防重复：

- `organizationId + sourceMailbox + messageId`

推荐策略：
- 幂等命中时返回 200（或业务成功态），不要返回 500；
- 避免监控系统重复重试导致重复建跟进。

---

## 7. 错误码与重试协作建议

监控系统行为：
- 收到非 2xx：按重试策略重试（1m -> 5m -> 15m -> 1h）
- 超过重试上限：标记 DEAD，等待人工处理

CRM 建议返回：
- `400`：请求参数错误（不会因重试自动恢复）
- `401/403`：鉴权失败（检查 Key、签名、时钟）
- `404`：接口路径错误（监控系统需修配置）
- `409`：幂等冲突（可视为已处理）
- `500`：服务异常（允许监控系统重试）

---

## 8. CRM 最小实现参考（Spring Boot）

```java
@RestController
@RequestMapping("/api/webhook")
public class EmailWebhookController {

    @PostMapping("/email-log")
    public Map<String, Object> receive(@RequestBody Map<String, Object> payload,
                                       @RequestHeader(value = "Organization-Id", required = false) String organizationId,
                                       @RequestHeader(value = "Authorization", required = false) String authorization) {
        // 1) 可选：校验 authorization
        // 2) 校验必要字段 messageId/sourceMailbox/matchedTargetMailbox
        // 3) 做幂等判断（organizationId + sourceMailbox + messageId）
        // 4) 触发 CRM 自动建跟进逻辑
        // 5) 成功返回 200

        return Map.of(
                "success", true,
                "eventId", "evt-" + System.currentTimeMillis()
        );
    }
}
```

---

## 9. 联调步骤

1. CRM 先上线最小接口（只落日志并返回 200）。
2. 监控系统配置：
   - `CRM_BASE_URL`
   - `CRM_WEBHOOK_PATH=/api/webhook/email-log`
3. 发送测试邮件（监控邮箱 -> 客户邮箱）。
4. 验证 CRM 是否收到 payload。
5. 在 CRM 中接入自动建跟进逻辑。
6. 验证幂等（重复 messageId 不重复建跟进）。

---

## 10. 上线前检查清单

- [ ] CRM 接口路径与监控系统配置一致
- [ ] 接口响应码规范符合约定（成功 2xx）
- [ ] 幂等已启用（避免重复建单）
- [ ] 接口日志包含 `messageId`、`organizationId`
- [ ] 异常有告警（500、持续重试、死信）
- [ ] API Key 未泄露，已定期轮换

