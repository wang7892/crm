# Cordys CRM 生产环境部署与数据库运维手册

> 最后核验日期：2026-07-15  
> 适用范围：当前公司 Linux 生产服务器、GitHub `main` 分支、源码/JAR 部署方式。  
> 本文是当前环境的优先操作手册；若其他历史文档与本文冲突，以本文和服务器现场状态为准。

## 1. 安全规则

本仓库是公开仓库。本文和 Git 中禁止出现以下内容：

- Windows 跳板机、Linux 服务器、VPN、MySQL、Redis 的真实密码。
- VPN IPSec 预共享密钥、邮箱授权码、企业微信 Secret、AI API Key。
- 私钥、访问令牌以及带密钥的完整配置文件。

所有凭据只能保存在公司密码管理器或服务器私有配置中。命令使用密码时统一采用交互式 `-p`，不要把密码直接写在命令行。

曾经出现在聊天、截图或公开仓库中的密码，应当视为已泄露并尽快轮换。

## 2. 当前生产环境

| 项目 | 当前值 |
| --- | --- |
| 部署方式 | Linux 源码构建 + Spring Boot JAR，不使用 Docker Compose 部署 CRM |
| Git 远端分支 | `main` |
| CRM 服务器内网 IP | `192.168.30.114` |
| SSH 用户 | `admin`（密码从公司密码管理器获取） |
| 服务器项目目录 | `/data/CORDYSCRM` |
| 生产配置 | `/data/CORDYSCRM/conf/cordys-crm.properties` |
| 后端 JAR | `/data/CORDYSCRM/backend/app/target/app-main.jar` |
| systemd 服务 | `cordys-crm.service` |
| Java 后端端口 | `8081` |
| Nginx CRM 入口 | `8093` |
| 生产数据库 | `cordys-crm`，服务器本机 `127.0.0.1:3306` |
| Flyway 历史表 | `cordys_crm_version` |
| 数据库备份目录 | `/data/backup` |

当前请求链路：

```text
浏览器 -> Nginx :8093 -> Java CRM :8081 -> MySQL :3306
```

Nginx 配置位于 `/etc/nginx/sites-enabled/mls-crm`。`8093` 的 `/` 请求转发到 `127.0.0.1:8081`，附件请求可转发到独立附件服务。

生产 Java 进程明确读取：

```text
/data/CORDYSCRM/conf/cordys-crm.properties
```

不要假设该目录一定是指向 `/data/CORDYSCRM-shared` 的软链接。部署前应以以下命令核对现场状态：

```bash
readlink -f /data/CORDYSCRM/conf
ls -l /data/CORDYSCRM/conf/cordys-crm.properties
```

## 3. 正常代码发布流程

### 3.1 本地提交并推送

在本地项目根目录先确认改动范围：

```powershell
git status --short --branch
git diff --check
git diff --stat
```

只暂存需要发布的业务文件，不提交缓存、构建产物或真实配置：

```powershell
git add <本次需要发布的文件或目录>
git diff --cached --check
git diff --cached --stat
git commit -m "feat: 本次更新说明"
```

当前本地分支可能名为 `master`，远端部署分支为 `main`，应明确推送目标：

```powershell
git push origin HEAD:main
```

推送后记录提交号：

```powershell
git log -1 --oneline
```

### 3.2 服务器拉取代码

登录服务器后：

```bash
cd /data/CORDYSCRM
git status
```

必须先确认服务器工作区干净。若存在服务器本地修改，不要直接覆盖、不要执行 `git reset --hard`，应先判断修改归属。

拉取最新 `main`：

```bash
git fetch origin
git log --oneline HEAD..origin/main
git pull --ff-only origin main
git log -1 --oneline
```

如果访问 GitHub 出现 GnuTLS/HTTP2 中断，可先执行：

```bash
git config --global http.version HTTP/1.1
git fetch origin
```

### 3.3 发布前备份数据库

每次涉及后端、迁移脚本或数据库数据的发布都要先备份：

```bash
mkdir -p /data/backup
mysqldump -h 127.0.0.1 -P 3306 -u <DB_ADMIN_USER> -p \
  --single-transaction --routines --events --triggers \
  --databases cordys-crm \
  > /data/backup/cordys-crm-before-deploy-$(date +%Y%m%d_%H%M%S).sql
```

确认备份不是空文件：

```bash
ls -lh /data/backup/cordys-crm-before-deploy-*.sql
```

### 3.4 完整构建前端和后端

在项目根目录执行：

```bash
cd /data/CORDYSCRM
mvn clean package -DskipTests
```

该命令会：

1. 构建 Web 和 Mobile 前端。
2. 将前端 `dist` 复制到 Spring Boot 静态资源。
3. 编译后端。
4. 生成 `backend/app/target/app-main.jar`。

必须看到：

```text
BUILD SUCCESS
```

正常整包发布不要添加：

```text
-DskipAntRunForJenkins
```

该参数会跳过前端资源复制，可能导致服务器仍显示旧前端。

服务器当前可直接使用系统 Maven。若 `./mvnw` 提示缺少 `.mvn/wrapper/maven-wrapper.properties`，使用 `mvn`，不要反复执行损坏的 Wrapper。

### 3.5 重启 CRM

```bash
sudo systemctl restart cordys-crm
sleep 30
sudo systemctl status cordys-crm --no-pager
```

如果 `systemctl stop` 后状态显示 `failed`，不等于进程仍在运行。用进程和端口确认：

```bash
pgrep -a -f '/data/CORDYSCRM/backend/app/target/app-main.jar'
sudo ss -ltnp | grep ':8081'
```

查看启动日志：

```bash
sudo journalctl -u cordys-crm -n 200 --no-pager
```

### 3.6 发布验证

入口检查：

```bash
curl -I --max-time 15 http://127.0.0.1:8093/
```

预期返回：

```text
HTTP/1.1 200
```

服务端口检查：

```bash
sudo ss -ltnp | grep -E ':8081|:8093'
```

Flyway 迁移检查：

```bash
mysql -h 127.0.0.1 -P 3306 -u <DB_ADMIN_USER> -p -D cordys-crm -e \
  "SELECT version, description, success FROM cordys_crm_version ORDER BY installed_rank DESC LIMIT 10;"
```

所有新版本的 `success` 必须为 `1`。

最后进行浏览器验收：

- 使用 `Ctrl+F5` 强制刷新。
- 登录、客户、合同、订单、附件等基本功能正常。
- 验证本次新增或修改的功能。
- 查看服务日志中没有持续出现 `ERROR`、数据库连接失败或迁移失败。

## 4. 数据库结构和数据如何更新

### 4.1 表结构变化

表、字段、索引和初始化权限的变化必须写成 Flyway 迁移脚本：

```text
backend/crm/src/main/resources/migration/1.6.0/ddl/
backend/crm/src/main/resources/migration/1.6.0/dml/
```

发布流程：

```text
本地新增迁移 SQL
-> Git 提交并推送 main
-> 服务器拉取并完整构建
-> 重启 cordys-crm.service
-> Flyway 在生产数据库执行一次
-> 查询 cordys_crm_version 验证
```

不要只在本地或 Navicat 手工修改生产表结构，否则代码、迁移历史和数据库结构会失去一致性。

### 4.2 业务数据

- 真实客户、订单、跟进等数据应通过线上 CRM 操作。
- 必须批量修正生产数据时，先备份，再使用经过审核的 SQL，并尽量放在事务中执行。
- 本地测试数据不会自动同步到生产库，除非本地项目明确连接的是生产数据库隧道。

### 4.3 本地运行时禁止执行生产迁移

本地项目通过 SSH 隧道连接生产库时，本地配置必须包含：

```properties
spring.flyway.enabled=false
```

生产服务器配置不能关闭 Flyway。

## 5. 本地 Navicat/本地项目直连生产数据库

这不是数据库复制或双向同步，而是本地和线上 CRM 共用同一个生产数据库。

```text
本地电脑
-> 公司 VPN
-> SSH 连接 CRM Linux 服务器 :22
-> 本地端口 13306
-> 服务器 127.0.0.1:3306
-> cordys-crm
```

### 5.1 建立 VPN 后检查 SSH

```powershell
Test-NetConnection 192.168.30.114 -Port 22
```

必须看到：

```text
TcpTestSucceeded : True
```

### 5.2 建立数据库隧道

```powershell
ssh -N -L 13306:127.0.0.1:3306 admin@192.168.30.114
```

隧道窗口无输出是正常现象，使用期间必须保持 VPN 和 SSH 进程运行。

另开 PowerShell 验证：

```powershell
Test-NetConnection 127.0.0.1 -Port 13306
```

### 5.3 Navicat 生产连接

```text
连接名：CRM-PROD（建议使用红色标识）
主机：127.0.0.1
端口：13306
数据库：cordys-crm
用户名/密码：从公司密码管理器读取
```

### 5.4 本地项目配置

本地 `cordys-crm.properties` 被 `.gitignore` 忽略，配置示例：

```properties
spring.datasource.url=jdbc:mysql://127.0.0.1:13306/cordys-crm?useUnicode=true&characterEncoding=UTF-8&allowPublicKeyRetrieval=true&useSSL=false
spring.datasource.username=<DB_USER>
spring.datasource.password=<DB_PASSWORD>
spring.flyway.enabled=false
```

关闭 VPN 或 SSH 隧道只会让本地 Navicat/本地项目断开，不会影响服务器上的线上 CRM。

本地项目连接生产库后，任何新增、修改、删除都会立即影响真实用户。不要用生产库做破坏性测试。

## 6. VPN 与 Docker 路由冲突

当前服务器曾出现 VPN 客户端网段与 Docker 默认网段重叠的问题：

```text
Docker：172.17.0.0/16
VPN 客户端：172.17.30.0/24
```

错误表现：

```bash
ip route get <VPN_CLIENT_IP>
```

返回流量错误地走 `docker0`，导致 VPN 客户端无法 SSH 到服务器。

当前环境的临时修复方式是为 VPN 子网添加更具体的路由：

```bash
sudo ip route replace 172.17.30.0/24 via 192.168.30.1 dev enp128s31f6
```

验证：

```bash
ip route get <VPN_CLIENT_IP>
```

预期结果应经物理网卡和公司网关返回，不再走 `docker0`。

该 `ip route` 命令在服务器重启后会消失。持久化时，应在服务器实际 Netplan 配置中为 `enp128s31f6` 添加：

```yaml
routes:
  - to: 172.17.30.0/24
    via: 192.168.30.1
```

修改前先备份 `/etc/netplan/*.yaml`，使用：

```bash
sudo netplan try
```

确认网络和 SSH 正常后再执行：

```bash
sudo netplan apply
```

不要为了修复该问题直接重建 Docker 网络或开放 MySQL 公网端口。

## 7. 全量替换生产数据库（非常规操作）

只有明确要求“本地数据库完全替换生产数据库”时才能执行。本操作会删除生产数据，必须安排停机窗口并保留两份备份。

### 7.1 检查导入文件

```bash
ls -lh /data/backup/<IMPORT_FILE>.sql
grep -n -m 6 -E '^(CREATE DATABASE|USE |DROP TABLE|CREATE TABLE|INSERT INTO)' /data/backup/<IMPORT_FILE>.sql
```

### 7.2 停止 CRM 并确认无写入

```bash
sudo systemctl stop cordys-crm
pgrep -a -f '/data/CORDYSCRM/backend/app/target/app-main.jar'
sudo ss -ltnp | grep ':8081'
```

后两条应无输出。还应确认邮件、企微或其他集成服务不会在导入期间写入 CRM 数据库。

### 7.3 再次备份服务器数据库

```bash
mysqldump -h 127.0.0.1 -P 3306 -u <DB_ADMIN_USER> -p \
  --routines --events --triggers --databases cordys-crm \
  > /data/backup/cordys-crm-server-before-replace-$(date +%Y%m%d_%H%M%S).sql
```

### 7.4 删除旧库并导入

```bash
mysql -h 127.0.0.1 -P 3306 -u <DB_ADMIN_USER> -p -e \
  'DROP DATABASE IF EXISTS `cordys-crm`; CREATE DATABASE `cordys-crm` CHARACTER SET utf8mb4;'
```

```bash
mysql -h 127.0.0.1 -P 3306 -u <DB_ADMIN_USER> -p -D cordys-crm \
  < /data/backup/<IMPORT_FILE>.sql
```

导入大文件时终端可能长时间无输出。只要没有返回 Shell 提示符，就不要按 `Ctrl+C`。

### 7.5 启动并补跑迁移

```bash
sudo systemctl start cordys-crm
sleep 30
sudo systemctl status cordys-crm --no-pager
curl -I --max-time 15 http://127.0.0.1:8093/
```

最后查询 `cordys_crm_version`，确认迁移均为 `success=1`。

## 8. 回滚原则

### 8.1 代码回滚

优先对有问题的提交执行 `git revert`，再重新构建和重启：

```bash
git log --oneline -10
git revert <BAD_COMMIT>
mvn clean package -DskipTests
sudo systemctl restart cordys-crm
```

不要使用 `git reset --hard` 覆盖服务器现场。

### 8.2 数据库回滚

Flyway 迁移原则上只向前修复。生产迁移失败时，优先新增补救迁移，不要随意修改已经执行过的迁移文件。

只有数据不可恢复时，才在停机、再次备份当前失败现场后，使用发布前完整备份恢复数据库。

## 9. 常见问题

### 9.1 `docker compose ps` 提示没有配置文件

当前 CRM 不是 Docker Compose 部署，属于正常现象。使用 `systemctl status cordys-crm` 管理 CRM。

### 9.2 Maven Wrapper 缺少文件

若出现 `.mvn/wrapper/maven-wrapper.properties` 不存在，使用服务器系统 Maven：

```bash
mvn clean package -DskipTests
```

### 9.3 pnpm/npm 超时

这是依赖仓库网络问题，不是代码编译错误。等待自动重试；最终失败时再使用公司批准的 npm 镜像重新构建。

### 9.4 本地后端报 MySQL `Access denied`

隧道正常但本地配置密码与生产数据库不一致。使用 Navicat `CRM-PROD` 所用的同一数据库账号，禁止把密码发到聊天或提交 Git。

### 9.5 本地能连 `13306`，但服务器重启后失效

检查 VPN 路由是否仍错误地走 `docker0`，并确认 Netplan 中已持久化 `172.17.30.0/24` 的回程路由。

## 10. MLS 外部数据库每日同步

CRM 已实现从只读外部库 `mls_agent_data` 到生产库 `cordys-crm` 的单向同步。Quartz 按 `Asia/Shanghai` 时区每天 `00:00` 按固定顺序执行：

```text
customer_info -> contract_info -> order_info
customer      -> contract      -> sales_order
```

三张源表每天都执行全量分页扫描，并使用外部 ID 映射、行哈希和目标更新时间保证幂等：源内容及 CRM 目标均未变化时跳过，源内容变化或 CRM 外部受控字段被正常编辑时会恢复为外部值，新记录会新增。同步不会因为外部记录删除而删除 CRM 中的数据，也不会删除 CRM 手工创建的展会客户。

`crm.mls-sync.page-size` 只控制每次从外部库读取的单页条数，不限制本次同步总行数；手动接口传入 `pageSize` 时仍会扫描三张源表的全部数据。

生产环境只在以下服务器私有配置中保存外部数据库连接信息：

```text
/data/CORDYSCRM/conf/cordys-crm.properties
```

必须保留以下同步配置：

```properties
quartz.time-zone=Asia/Shanghai
crm.ai-agent.external-order.enabled=true
crm.ai-agent.external-order.connection-timeout=5000
crm.ai-agent.external-order.socket-timeout=120000
crm.ai-agent.external-order.query-timeout-seconds=120
crm.mls-sync.enabled=true
crm.mls-sync.organization-id=100001
crm.mls-sync.page-size=2000
```

公网 MySQL 连接禁止长期使用明文链路。优先采用服务端证书校验的 TLS（`sslMode=VERIFY_IDENTITY`，证书主机名必须匹配），并将 CA 信任库放在服务器私有目录；无法提供合规 TLS 时，应先建设 VPN、专线或等效的加密私网链路，不要直接通过公网配置 `useSSL=false`。完整配置和手动验证方法见 `docs/MLS外部数据库同步部署验证.md`。

同步运行、失败行和检查点分别记录在 `mls_sync_run`、`mls_sync_run_error`、`mls_sync_checkpoint`；Redis 锁 `crm:mls-sync:{organizationId}` 防止手动任务和定时任务并发。日常运维应核对最近一次运行状态和失败行，而不是通过整库覆盖实现同步。其它脚本直接修改 `customer`、`contract`、`sales_order` 时必须同时更新 `update_time`，否则目标漂移检测无法识别该修改。

当前程序不会自动清理失败审计，建议由运维任务保留 90 天。确认备份可用后，可定期删除 90 天前的 `mls_sync_run_error`，再删除非 `RUNNING` 的旧 `mls_sync_run`；不要清理 `mls_sync_mapping` 和 `mls_sync_checkpoint`。

## 11. 发布检查清单

发布前：

- [ ] 本地改动已审核、构建通过并推送到远端 `main`。
- [ ] 服务器 `git status` 干净。
- [ ] 生产数据库已生成带时间戳的完整备份。
- [ ] 服务器私有配置和附件目录未被 Git 覆盖。

发布中：

- [ ] `git pull --ff-only origin main` 成功。
- [ ] 根目录 `mvn clean package -DskipTests` 显示 `BUILD SUCCESS`。
- [ ] `cordys-crm.service` 已重启。

发布后：

- [ ] `8093` 返回 HTTP 200。
- [ ] Java 正在监听 `8081`。
- [ ] 新 Flyway 迁移全部 `success=1`。
- [ ] 浏览器强制刷新后功能正常。
- [ ] 日志无持续错误。
- [ ] 最近一次 MLS 同步为 `SUCCESS` 或已复核 `PARTIAL` 的失败行。
- [ ] VPN 回程路由仍可用。
