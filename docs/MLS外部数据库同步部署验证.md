# MLS 外部数据库同步部署与验证

## 配置

在服务器私有配置 `/data/CORDYSCRM/conf/cordys-crm.properties` 写入只读外部账号，不要提交 Git。生产示例优先使用校验服务端身份的 TLS；`<MLS_DB_TLS_HOST>` 必须与数据库证书的 SAN 匹配，CA 信任库及其密码只保存在服务器私有目录：

```properties
crm.ai-agent.external-order.enabled=true
crm.ai-agent.external-order.url=jdbc:mysql://<MLS_DB_TLS_HOST>:3306/mls_agent_data?useUnicode=true&characterEncoding=UTF-8&sslMode=VERIFY_IDENTITY&trustCertificateKeyStoreUrl=file:/data/CORDYSCRM/conf/mls-agent-data-ca.p12&trustCertificateKeyStorePassword=<truststore-password>&trustCertificateKeyStoreType=PKCS12
crm.ai-agent.external-order.username=<read-only-user>
crm.ai-agent.external-order.password=<read-only-password>
crm.ai-agent.external-order.connection-timeout=30000
crm.ai-agent.external-order.socket-timeout=120000
crm.ai-agent.external-order.query-timeout-seconds=120
quartz.time-zone=Asia/Shanghai
crm.mls-sync.enabled=true
crm.mls-sync.organization-id=100001
crm.mls-sync.page-size=2000
crm.mls-sync.time=00:00
```

`crm.mls-sync.time` uses strict `HH:mm` format in the `quartz.time-zone` time zone. For example, `09:30` runs the synchronization every day at 09:30. Restart the application after changing it. Invalid values such as `9:30`, `24:00`, or `09:60` stop application startup with a configuration error.

External MLS `SELECT` operations retry connection-acquisition failures up to three times, after 5, 15, and 30 seconds. SQL, schema, and data errors are not retried. Keep `crm.ai-agent.external-order.connection-timeout=30000` so a cold connection pool has enough time to establish its first connection.

外部连接池在代码中设置为只读，数据库账号仍应只授予 `SELECT` 权限。

`47.110.46.27:3306` 是公网 MySQL 地址，直接使用 `useSSL=false` 会使数据库认证和业务数据缺少传输层保护，不应作为生产配置。优先让数据库提供可信 TLS 证书并使用上面的 `VERIFY_IDENTITY`；如果当前数据库无法提供合规 TLS，应先通过 VPN、专线或等效加密私网连接数据库，再按公司安全策略配置连接，不能仅为连通而关闭 SSL。示例中的主机名、账号、密码和信任库密码均为占位符，仓库中不得保存真实凭据。

## 发布后检查

```bash
sudo systemctl restart cordys-crm
sudo journalctl -u cordys-crm -n 200 --no-pager | grep -E 'Flyway|MLS|mls_sync'
mysql -h 127.0.0.1 -P 3306 -u <CRM_USER> -p -D cordys-crm -e \
  "SELECT installed_rank,version,success FROM cordys_crm_version ORDER BY installed_rank DESC LIMIT 8; \
   SHOW COLUMNS FROM mls_sync_run; \
   SHOW INDEX FROM mls_sync_mapping WHERE Key_name='uk_mls_sync_mapping_target';"
```

`V1.6.0_44` 会扩展客户/合同审计列到 `VARCHAR(50)`，并创建 `mls_sync_mapping`、`mls_sync_run`、`mls_sync_run_error`、`mls_sync_checkpoint`；`V1.6.0_45` 会解除历史重复目标映射并建立目标唯一索引，不删除 CRM 业务行。

## 手动全量验证

需要 `SYSTEM_SETTING:UPDATE` 权限。建议先登录 CRM，再从浏览器开发者工具或 Swagger 复用当前登录会话发起请求；直接使用 `curl` 时，必须从已登录会话取得 `X-AUTH-TOKEN` 和 `CSRF-TOKEN`，并显式指定组织：

```bash
curl -X POST 'http://127.0.0.1:8081/integration/mls-agent-data/sync' \
  -H 'Content-Type: application/json' \
  -H 'X-AUTH-TOKEN: <CRM_LOGIN_TOKEN>' \
  -H 'CSRF-TOKEN: <CRM_CSRF_TOKEN>' \
  -H 'Organization-Id: 100001' \
  -d '{"pageSize": 2000}'
```

这里的 `pageSize=2000` 只调整数据库查询的单页大小，不是总同步条数上限；该请求仍会按“客户 -> 合同 -> 订单”执行一次完整的全量同步。首次全量可能超过 HTTP 超时时间，请以 `mls_sync_run` 的最终状态为准，不要因客户端断开重复触发。响应为 `DISABLED`、`NOT_CONFIGURED`、`REJECTED_ORGANIZATION` 或 `SKIPPED_LOCKED` 时不会创建新的 `run_id`。不要使用过期或其他组织的令牌，也不要将令牌写入脚本、Shell 历史或文档。

查看运行统计和失败行：

```bash
mysql -h 127.0.0.1 -P 3306 -u <CRM_USER> -p -D cordys-crm -e \
  "SELECT run_id,trigger_type,status,stage,\
          customer_read_count,customer_created_count,customer_updated_count,customer_skipped_count,customer_failed_count,\
          contract_read_count,contract_created_count,contract_updated_count,contract_skipped_count,contract_failed_count,\
          order_read_count,order_created_count,order_updated_count,order_skipped_count,order_failed_count,start_time,end_time\
     FROM mls_sync_run WHERE organization_id='100001' ORDER BY start_time DESC LIMIT 5; \
   SELECT stage,source_table,source_id,error_code,error_message,create_time\
     FROM mls_sync_run_error WHERE run_id='<RUN_ID>' ORDER BY create_time DESC LIMIT 50;"
```

把 `<RUN_ID>` 替换为第一条查询返回的本次运行 ID。只有 `status=SUCCESS` 且三个 `failed_count` 都为 `0` 才算验证通过；`PARTIAL` 或 `FAILED` 必须处理失败行后重新执行。

## 调度和数据安全

同步由 CRM 内置 Quartz 按 `Asia/Shanghai` 时区在每天 `00:00` 执行，固定顺序为客户、合同、订单；三张源表均为全量分页扫描，并使用行哈希和目标更新时间跳过未变化记录。Redis 锁 `crm:mls-sync:{organizationId}` 防止定时和手动任务并发。同步只新增或更新外部映射行，不会因外部删除而删除 CRM 客户、合同或订单。

订单源表没有更新时间字段，因此同样每天按 `id` 全量分页读取。首次全量约 219,433 行，需要为旧数据建立映射，可能持续数十分钟或更久；等待 `mls_sync_run.status` 为 `SUCCESS` 后再核对业务数据，`PARTIAL` 或 `FAILED` 先按失败审计处理并重跑。

部署后确认服务器和调度配置：

```bash
timedatectl show -p Timezone
grep '^quartz.time-zone=Asia/Shanghai$' /data/CORDYSCRM/conf/cordys-crm.properties
```

业务时间转换和 Quartz 调度均固定为 `Asia/Shanghai`；服务器系统时区可以不同，但日志排障时必须留意显示差异。

## 审计保留和直接 SQL 约束

当前程序不会自动清理审计。建议由运维任务保留 90 天运行及失败审计，确认备份后定期执行，禁止删除映射和检查点：

```sql
DELETE FROM mls_sync_run_error
WHERE create_time < UNIX_TIMESTAMP(DATE_SUB(NOW(), INTERVAL 90 DAY)) * 1000;

DELETE FROM mls_sync_run
WHERE status <> 'RUNNING'
  AND start_time < UNIX_TIMESTAMP(DATE_SUB(NOW(), INTERVAL 90 DAY)) * 1000;
```

目标漂移判断依赖 `customer`、`contract`、`sales_order` 的正常写入路径更新 `update_time`。任何额外脚本直接修改这三张表时，也必须同时更新该列。
