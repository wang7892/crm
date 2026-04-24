# 监控系统接入 CRM 配置指南

## 1. 目标

本文档用于指导你在“独立邮件监控系统”中完成以下能力：

- 配置 CRM 连接参数
- 使用 API Key 生成签名并调用 CRM 接口
- 触发自动创建跟进记录
- 预留附件上传能力

---

## 2. 必要前置条件

请先确认以下信息已准备好：

- CRM 访问地址（`crm.baseUrl`）
- 组织 ID（请求头 `Organization-Id`）
- Access Key / Secret Key（个人中心 API Keys 中生成）
- 监控系统可访问 CRM 网络地址

---

## 3. 配置文件示例

建议在监控系统使用如下配置（YAML 示例）：

```yaml
crm:
  baseUrl: "http://localhost:5173"
  organizationId: "your-org-id"
  accessKey: "your-access-key"
  secretKey: "your-secret-key"
  timeout:
    connectMs: 3000
    readMs: 10000
  retry:
    maxAttempts: 3
    backoffMs: 1000
```

如果你的监控系统是 `application.properties`：

```properties
crm.baseUrl=http://localhost:5173
crm.organizationId=your-org-id
crm.accessKey=your-access-key
crm.secretKey=your-secret-key
crm.timeout.connectMs=3000
crm.timeout.readMs=10000
crm.retry.maxAttempts=3
crm.retry.backoffMs=1000
```

---

## 4. CRM 鉴权规则（必须一致）

监控系统每次请求 CRM 时，都要带这两个头：

- `Organization-Id: <organizationId>`
- `Authorization: <accessKey>:<signature>`

其中 `signature` 生成规则需要与 CRM 校验逻辑一致：

1. 先拼明文：`accessKey|random|timestamp`
2. 使用 `secretKey` 做 AES-GCM 加密
3. IV 使用 `accessKey.getBytes(UTF_8)`（与 CRM 侧逻辑对齐）
4. 最终请求头：`Authorization = accessKey + ":" + signature`

时间戳要用当前毫秒时间，且客户端时间不要偏差太大（CRM 会做有效期校验）。

---

## 5. Java 代码模板（可直接改造）

```java
import org.apache.commons.codec.binary.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class CrmAuthHelper {

    private static final int GCM_TAG_LENGTH = 128;

    public static String buildAuthorization(String accessKey, String secretKey) {
        String plain = accessKey + "|" + UUID.randomUUID() + "|" + System.currentTimeMillis();
        String signature = aesGcmEncrypt(plain, secretKey, accessKey.getBytes(StandardCharsets.UTF_8));
        return accessKey + ":" + signature;
    }

    private static String aesGcmEncrypt(String src, String secretKey, byte[] iv) {
        try {
            SecretKeySpec keySpec = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);
            byte[] encrypted = cipher.doFinal(src.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeBase64String(encrypted);
        } catch (Exception e) {
            throw new RuntimeException("build signature failed", e);
        }
    }
}
```

---

## 6. 创建跟进接口调用示例

接口：

- `POST /follow/record/add`

请求头：

- `Content-Type: application/json`
- `Organization-Id: your-org-id`
- `Authorization: accessKey:signature`

请求体示例：

```json
{
  "type": "CUSTOMER",
  "customerId": "customer001",
  "contactId": "contact001",
  "content": "主题: 报价沟通\n\n正文: 客户已确认下周三会议。",
  "followTime": 1777000000000,
  "followMethod": "EMAIL",
  "owner": "user001"
}
```

说明：

- `content` 最大长度 3000，建议超长截断。
- `owner` 可不传，不传会默认 API Key 关联用户。
- `followMethod` 必须是系统已有字典值（例如 `EMAIL`）。

---

## 7. 附件接入（第二步再做）

若邮件含附件，建议按这个顺序：

1. 调 `POST /attachment/upload/temp` 上传附件（`multipart/form-data`，字段名 `files`）
2. 获取临时附件 ID
3. 在建跟进时把附件 ID 填入对应附件字段（`moduleFields`）
4. CRM 会在保存时自动转存并绑定到跟进记录

---

## 8. 监控系统最小实现流程

当检测到 `邮箱A -> 邮箱B` 邮件时：

1. 检查幂等（`organizationId + mailbox + messageId`）
2. 组装跟进请求体
3. 生成签名并调用 `POST /follow/record/add`
4. 成功后记录 `followRecordId`
5. 失败重试（建议指数退避）

---

## 9. 建议的错误处理策略

- 401/鉴权失败：检查 accessKey、secretKey、时间同步、签名算法
- 400/参数错误：检查 `type`、`followMethod`、`content` 长度
- 404/客户或联系人不存在：进入待人工处理队列
- 500/服务异常：重试并打告警

---

## 10. 联调验收清单

- 可以通过监控系统成功调用 CRM 新增跟进接口
- 同一封邮件不会重复创建跟进（幂等生效）
- 断网恢复后能继续处理未完成事件
- 错误请求可定位（日志包含 messageId、organizationId、状态码）

---

## 11. 安全建议

- `secretKey` 禁止打印到日志
- 配置项建议走环境变量或密钥管理，不要硬编码
- 建议立即替换你截图中已暴露的 Key，避免泄露风险

