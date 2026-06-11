# Cordys CRM 服务器部署交付说明

本文档用于把当前代码包交给部署人员后，在公司服务器上部署 Cordys CRM、邮件监控和企业微信监控。部署人员可以按本文从上到下执行；如果已有本机数据需要保留，务必先阅读“数据迁移”部分。

## 1. 部署结论

本项目不是只复制代码就能运行。服务器至少需要同时准备：

- CRM 后端服务：Spring Boot，默认端口 `8081`。
- CRM 前端静态资源：打包后会内置到后端 JAR 或 Docker 镜像里。
- MySQL：保存 CRM 业务数据、邮件事件、企业微信缓冲数据。
- Redis：保存登录 Session、缓存等运行状态。
- 邮件监控服务：独立 Java 程序，轮询邮箱已发送邮件，并推送到 CRM。
- 企业微信监控服务：独立 Java 程序，拉取会话存档，并写入 CRM 缓冲表。
- 附件目录：CRM 上传文件、企业微信媒体文件、邮件附件都要有持久化目录。

如果是全新部署，可以使用空 MySQL 库，CRM 首次启动会通过 Flyway 自动创建 CRM 表结构。如果是把本机已经产生的数据迁移到服务器，必须迁移 MySQL 数据和附件目录；只迁移代码会导致客户、合同、跟进记录、邮件记录、企微聊天记录、附件引用丢失。

## 2. 交付前打包注意事项

发给部署人员前，建议只交付源代码、构建脚本、配置样例和必要的 SQL。不要把本地运行数据、日志、真实密钥一起打包。

建议保留：

- `backend/`
- `frontend/`
- `installer/`
- `mail monitoring/src/`
- `mail monitoring/docs/`
- `mail monitoring/config.properties.example`
- `mail monitoring/sources.txt`
- `mail monitoring/lib/mysql-connector-j-8.0.33.jar`
- `wecom monitoring/src/`
- `wecom monitoring/docs/`
- `wecom monitoring/config.properties.example`
- `wecom monitoring/sources.txt`
- `wecom monitoring/BUILD.txt`
- `wecom monitoring/wecom-monitor.jar`，如果确认这个 JAR 是最新构建结果
- `pom.xml`、`mvnw`、`mvnw.cmd`、`.mvn/`
- `README.md`、`BUILD.md`、本文档

建议不要交付：

- `.git/`，除非对方需要完整 Git 历史。
- `.idea/`、`.vscode/`、`*.iml`。
- `frontend/node_modules/`、各子项目 `node_modules/`。
- `frontend/.node/`。
- `backend/**/target/`，除非你明确要交付已经构建好的 JAR。
- `mail monitoring/out/`、`wecom monitoring/out/`，除非你明确要交付已编译 class。
- `runtime/`、`tmp/`、`outputs/`、`.codex_tmp/`、`.tmp_verify_mapper/`。
- `crm-run.log`、`crm-run.err.log`。
- `mail monitoring/attachments/`，除非这是要迁移的生产邮件附件数据。
- `mail monitoring/config.properties`、`wecom monitoring/config.properties`、根目录 `cordys-crm.properties` 中包含真实密码或密钥时，不要原样交付。

发包前请把所有真实密码、数据库地址、API Key、企业微信 Secret、邮箱授权码替换为占位符，让部署人员在服务器上重新填写。

## 3. 服务器环境要求

推荐 Linux 服务器，例如 Ubuntu 22.04、Debian 12、CentOS Stream、Rocky Linux。Windows Server 也可以部署，但生产环境更推荐 Linux。

最低建议配置：

- CPU：4 核及以上。
- 内存：8 GB 起步，客户和聊天数据多时建议 16 GB 及以上。
- 磁盘：100 GB 起步，附件、邮件和企微媒体多时按实际容量扩容。
- JDK：Java 21。
- MySQL：8.0 或兼容版本，字符集 `utf8mb4`。
- Redis：6.x 或 7.x。
- Node.js：22.x，仅源码构建前端时需要。
- pnpm：10.x，仅源码构建前端时需要。
- Maven：可使用项目自带 `./mvnw`。
- Docker：如果走 Docker 部署路线，需要 Docker 20+。

需要开放或确认的端口：

- `8081`：CRM Web/API 访问端口。
- `8082`：MCP 服务端口，如果启用。
- `3306`：MySQL，仅建议内网开放。
- `6379`：Redis，仅建议本机或内网开放。
- `8090`：邮件附件 HTTP 下载服务，如果启用 `ATTACHMENT_HTTP_ENABLED=true`。

安全建议：

- MySQL、Redis 不要暴露到公网。
- CRM 对公网开放时，建议通过 Nginx 配置 HTTPS。
- 邮箱授权码、企业微信 Secret、AI 模型 API Key 不要提交到代码仓库。
- 部署前更换当前本地配置中出现过的所有密码和 Key。

## 4. 推荐部署方式 A：Docker 单容器部署

仓库中 `installer/` 已提供容器化配置。该方式会把 CRM、内置 MySQL、内置 Redis、可选 MCP 放在同一个容器里，并把数据持久化到宿主机目录。

适用场景：

- 服务器上可以使用 Docker。
- 希望快速部署，减少手工安装 MySQL/Redis 的工作。
- 业务规模中小，单机即可承载。

### 4.1 构建镜像

在项目根目录执行：

```bash
docker build -t cordys-crm-custom:1.6.0 -f installer/Dockerfile .
```

如果服务器不能联网拉依赖，建议先在能联网的机器构建镜像，再通过 `docker save` / `docker load` 交付。

```bash
docker save cordys-crm-custom:1.6.0 -o cordys-crm-custom-1.6.0.tar
docker load -i cordys-crm-custom-1.6.0.tar
```

### 4.2 启动容器

```bash
mkdir -p /opt/cordys

docker run -d \
  --name cordys-crm \
  --restart unless-stopped \
  -p 8081:8081 \
  -p 8082:8082 \
  -v /opt/cordys:/opt/cordys \
  cordys-crm-custom:1.6.0
```

首次启动后，容器会在 `/opt/cordys` 下创建配置、数据、日志、附件目录。

关键目录：

- `/opt/cordys/conf/cordys-crm.properties`：CRM 运行配置。
- `/opt/cordys/data/mysql`：内置 MySQL 数据。
- `/opt/cordys/data/redis`：内置 Redis 数据。
- `/opt/cordys/data/files`：CRM 附件和企业微信媒体文件建议落盘目录。
- `/opt/cordys/logs/cordys-crm`：CRM 日志。

### 4.3 修改容器配置

启动后先查看并修改：

```bash
vi /opt/cordys/conf/cordys-crm.properties
```

重点检查：

```properties
mysql.embedded.enabled=true
spring.datasource.url=jdbc:mysql://127.0.0.1:3306/cordys-crm?useUnicode=true&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true
spring.datasource.username=root
spring.datasource.password=<替换为强密码>

redis.embedded.enabled=true
spring.data.redis.host=127.0.0.1
spring.data.redis.port=6379
spring.data.redis.password=<替换为强密码>

logging.file.path=/opt/cordys/logs/cordys-crm
```

修改后重启：

```bash
docker restart cordys-crm
docker logs -f cordys-crm
```

### 4.4 外部 MySQL/Redis

如果公司服务器已有独立 MySQL/Redis，建议关闭内置服务：

```properties
mysql.embedded.enabled=false
spring.datasource.url=jdbc:mysql://<mysql内网IP>:3306/cordys-crm?useUnicode=true&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true
spring.datasource.username=cordys_crm
spring.datasource.password=<数据库密码>

redis.embedded.enabled=false
spring.data.redis.host=<redis内网IP>
spring.data.redis.port=6379
spring.data.redis.password=<redis密码>
```

外部库需提前创建：

```sql
CREATE DATABASE `cordys-crm` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE USER 'cordys_crm'@'%' IDENTIFIED BY '<强密码>';
GRANT ALL PRIVILEGES ON `cordys-crm`.* TO 'cordys_crm'@'%';
FLUSH PRIVILEGES;
```

CRM 首次连接空库时会自动执行 `backend/crm/src/main/resources/migration` 下的迁移脚本。

## 5. 部署方式 B：源码/JAR 手工部署

适用场景：

- 公司要求 MySQL、Redis 独立安装。
- 不使用 Docker。
- 需要更细控制 systemd、Nginx、目录权限。

### 5.1 安装基础软件

Ubuntu 示例：

```bash
sudo apt update
sudo apt install -y openjdk-21-jdk mysql-server redis-server nginx unzip
```

确认版本：

```bash
java -version
mysql --version
redis-server --version
```

### 5.2 创建运行目录

```bash
sudo mkdir -p /opt/cordys/app
sudo mkdir -p /opt/cordys/conf
sudo mkdir -p /opt/cordys/logs/cordys-crm
sudo mkdir -p /opt/cordys/data/files
sudo mkdir -p /opt/cordys/tmp/upload
sudo chown -R cordys:cordys /opt/cordys
```

如果系统没有 `cordys` 用户：

```bash
sudo useradd -r -m -s /bin/bash cordys
```

### 5.3 构建前端和后端

项目根目录执行：

```bash
./mvnw clean package -DskipTests
```

该命令会：

- 安装并执行前端构建。
- 生成 `frontend/packages/web/dist` 和 `frontend/packages/mobile/dist`。
- 后端打包时把前端产物复制到 Spring Boot 静态资源目录。
- 生成 CRM 后端 JAR。

如果只构建后端并使用已有前端 dist：

```bash
./mvnw clean package -DskipTests -pl '!frontend'
```

当前后端 JAR 通常位于：

```text
backend/app/target/app-main.jar
```

复制到服务器：

```bash
sudo cp backend/app/target/app-main.jar /opt/cordys/app/app-main.jar
sudo chown cordys:cordys /opt/cordys/app/app-main.jar
```

### 5.4 准备 CRM 配置文件

在服务器创建：

```bash
sudo vi /opt/cordys/conf/cordys-crm.properties
```

参考配置：

```properties
server.port=8081
logging.file.path=/opt/cordys/logs/cordys-crm

spring.datasource.url=jdbc:mysql://127.0.0.1:3306/cordys-crm?autoReconnect=false&useUnicode=true&characterEncoding=UTF-8&characterSetResults=UTF-8&zeroDateTimeBehavior=convertToNull&allowPublicKeyRetrieval=true&useSSL=false
spring.datasource.username=cordys_crm
spring.datasource.password=<数据库密码>

spring.data.redis.host=127.0.0.1
spring.data.redis.port=6379
spring.data.redis.password=<Redis密码>
spring.session.redis.repository-type=indexed
spring.session.timeout=43200s

spring.servlet.multipart.location=/opt/cordys/tmp/upload

crm.webhook.create-follow=true
crm.wecom.auto-create-follow=true
crm.wecom.auto-follow-poll-ms=60000
crm.wecom.auto-follow-current-day=true
```

如果启用 AI Agent，请另行配置：

```properties
crm.ai-agent.llm.enabled=true
crm.ai-agent.llm.base-url=<OpenAI兼容接口地址>
crm.ai-agent.llm.api-key=<API Key>
crm.ai-agent.llm.model=<模型名>
```

注意：不要直接使用开发机中的 AI API Key、数据库密码、Redis 密码。生产环境必须重新生成。

### 5.5 启动 CRM

手工启动测试：

```bash
sudo -u cordys java \
  -Dfile.encoding=UTF-8 \
  -Djava.io.tmpdir=/opt/cordys/tmp \
  -Dlogging.file.path=/opt/cordys/logs/cordys-crm \
  -jar /opt/cordys/app/app-main.jar \
  --spring.config.additional-location=file:/opt/cordys/conf/cordys-crm.properties
```

看到端口 `8081` 正常监听后访问：

```text
http://<服务器IP>:8081/
```

### 5.6 配置 systemd

创建 `/etc/systemd/system/cordys-crm.service`：

```ini
[Unit]
Description=Cordys CRM
After=network.target mysql.service redis-server.service

[Service]
User=cordys
Group=cordys
WorkingDirectory=/opt/cordys/app
ExecStart=/usr/bin/java -Dfile.encoding=UTF-8 -Djava.io.tmpdir=/opt/cordys/tmp -Dlogging.file.path=/opt/cordys/logs/cordys-crm -jar /opt/cordys/app/app-main.jar --spring.config.additional-location=file:/opt/cordys/conf/cordys-crm.properties
Restart=always
RestartSec=10
SuccessExitStatus=143

[Install]
WantedBy=multi-user.target
```

启用：

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now cordys-crm
sudo systemctl status cordys-crm
sudo journalctl -u cordys-crm -f
```

## 6. MySQL 数据库部署和迁移

### 6.1 全新部署

如果服务器是全新部署，创建 CRM 数据库即可：

```sql
CREATE DATABASE `cordys-crm` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE USER 'cordys_crm'@'%' IDENTIFIED BY '<强密码>';
GRANT ALL PRIVILEGES ON `cordys-crm`.* TO 'cordys_crm'@'%';
FLUSH PRIVILEGES;
```

CRM 启动后会自动执行：

```text
backend/crm/src/main/resources/migration
```

迁移记录表为：

```text
cordys_crm_version
```

如果启动失败，先看 `logs/cordys-crm/error.log`，再检查 MySQL 权限、字符集、连接地址。

### 6.2 从本机迁移已有 CRM 数据

如果本机已经录入客户、联系人、合同、跟进记录、用户、权限、邮件/企微记录，请迁移数据库。

导出本机 CRM 库：

```bash
mysqldump -h 127.0.0.1 -P 3306 -u root -p \
  --single-transaction \
  --routines \
  --triggers \
  --default-character-set=utf8mb4 \
  cordys-crm > cordys-crm.sql
```

在服务器导入：

```bash
mysql -h 127.0.0.1 -P 3306 -u root -p -e "CREATE DATABASE IF NOT EXISTS \`cordys-crm\` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"
mysql -h 127.0.0.1 -P 3306 -u root -p cordys-crm < cordys-crm.sql
```

导入后再启动 CRM。不要先让新空库启动并产生一批新数据后，再覆盖导入旧库，这样容易造成状态混乱。

### 6.3 需要额外迁移的库

邮件监控有自己的事件库，默认示例库名：

```text
mail_monitoring_db
```

企业微信监控有自己的监控库，默认示例库名：

```text
wecom_monitoring_db
```

如果已有历史邮件/企微监控状态，也要分别导出：

```bash
mysqldump -u root -p --single-transaction --default-character-set=utf8mb4 mail_monitoring_db > mail_monitoring_db.sql
mysqldump -u root -p --single-transaction --default-character-set=utf8mb4 wecom_monitoring_db > wecom_monitoring_db.sql
```

服务器导入：

```bash
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS mail_monitoring_db CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"
mysql -u root -p mail_monitoring_db < mail_monitoring_db.sql

mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS wecom_monitoring_db CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"
mysql -u root -p wecom_monitoring_db < wecom_monitoring_db.sql
```

### 6.4 附件目录迁移

数据库只保存附件元数据和路径引用，不一定保存文件本体。因此必须同步附件目录。

需要迁移：

- CRM 附件目录：推荐 `/opt/cordys/data/files`。
- 邮件附件目录：例如 `mail monitoring/attachments`，生产建议改为 `/opt/cordys/mail-monitoring/attachments`。
- 企业微信媒体目录：必须和 `CRM_ATTACHMENT_BASE_DIR` 指向一致，推荐 `/opt/cordys/data/files`。

Linux 示例：

```bash
rsync -av /旧服务器/opt/cordys/data/files/ /opt/cordys/data/files/
rsync -av /旧服务器/opt/cordys/mail-monitoring/attachments/ /opt/cordys/mail-monitoring/attachments/
sudo chown -R cordys:cordys /opt/cordys/data/files /opt/cordys/mail-monitoring
```

如果只迁移数据库不迁移文件，CRM 页面上可能能看到附件记录，但下载会 404 或文件不存在。

## 7. 邮件监控部署

邮件监控目录为：

```text
mail monitoring/
```

它是独立 Java 程序，会：

- 通过 IMAP 轮询发件箱。
- 将邮件事件写入邮件监控库 `mail_event`、`mail_attachment`。
- 调用 CRM `/api/webhook/email-log`。
- 可直接向 CRM 库 `email_webhook_attachment` 写附件元数据。
- 可启动附件 HTTP 服务供 CRM 下载邮件附件。
- 可动态从 CRM 数据库读取销售人员邮箱授权码和客户邮箱。

### 7.1 创建邮件监控库

```sql
CREATE DATABASE mail_monitoring_db CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
```

执行脚本：

```text
mail monitoring/docs/mysql建表_邮件监测.sql
```

命令示例：

```bash
mysql -u root -p mail_monitoring_db < "mail monitoring/docs/mysql建表_邮件监测.sql"
```

CRM 自身的邮件表由 CRM Flyway 脚本创建，包括：

- `email_webhook_event`
- `email_webhook_attachment`

如果是老库，请确认已经执行 1.6.0 相关迁移。

### 7.2 编译邮件监控

进入目录：

```bash
cd "mail monitoring"
mkdir -p out
sed 's#\\#/#g' sources.txt > sources-linux.txt
javac -encoding UTF-8 -d out -cp "lib/mysql-connector-j-8.0.33.jar" @sources-linux.txt
```

运行：

```bash
java -cp "out:lib/mysql-connector-j-8.0.33.jar" Main
```

Windows 下 classpath 分隔符用分号：

```powershell
java -cp "out;lib/mysql-connector-j-8.0.33.jar" Main
```

### 7.3 配置邮件监控

复制配置样例：

```bash
cp config.properties.example config.properties
```

生产配置建议：

```properties
ORGANIZATION_ID=<CRM组织ID>
POLL_SECONDS=10

USE_IMAP=true
IMAP_HOST=imap.163.com
IMAP_PORT=993
IMAP_FOLDER=已发送邮件
ATTACHMENT_SAVE_DIR=/opt/cordys/mail-monitoring/attachments

ATTACHMENT_HTTP_ENABLED=true
ATTACHMENT_HTTP_PORT=8090
ATTACHMENT_PUBLIC_BASE_URL=http://<服务器内网或公网地址>:8090
ATTACHMENT_DOWNLOAD_PATH=/api/attachments

DB_ENABLED=true
DB_JDBC_URL=jdbc:mysql://127.0.0.1:3306/mail_monitoring_db?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8&allowPublicKeyRetrieval=true
DB_USERNAME=mail_monitor
DB_PASSWORD=<邮件监控库密码>

CRM_ATTACHMENT_DB_ENABLED=true
CRM_ATTACHMENT_DB_JDBC_URL=jdbc:mysql://127.0.0.1:3306/cordys-crm?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8&allowPublicKeyRetrieval=true
CRM_ATTACHMENT_DB_USERNAME=cordys_crm
CRM_ATTACHMENT_DB_PASSWORD=<CRM库密码>

CRM_BASE_URL=http://127.0.0.1:8081
CRM_ORGANIZATION_ID=<CRM组织ID>
CRM_ACCESS_KEY=<CRM个人中心生成的AccessKey>
CRM_SECRET_KEY=<CRM个人中心生成的SecretKey>
CRM_WEBHOOK_PATH=/api/webhook/email-log
CRM_TIMEOUT_CONNECT_MS=3000
CRM_TIMEOUT_READ_MS=10000
CRM_RETRY_MAX_ATTEMPTS=3
CRM_RETRY_BACKOFF_MS=1000

MAILBOX_DYNAMIC_LOAD_ENABLED=true
MAILBOX_REFRESH_SECONDS=60
```

如果关闭动态邮箱加载，需要手工配置：

```properties
MAILBOX_DYNAMIC_LOAD_ENABLED=false
SOURCE_MAILBOX=<销售邮箱>
TARGET_MAILBOXES=<客户邮箱1>,<客户邮箱2>
IMAP_USER=<销售邮箱登录账号>
IMAP_AUTH_CODE=<邮箱IMAP授权码>
```

注意事项：

- `ORGANIZATION_ID` 必须和 CRM 中客户、用户所属组织一致。
- `CRM_BASE_URL` 应指向后端服务，不要写前端开发端口。
- `CRM_ACCESS_KEY` / `CRM_SECRET_KEY` 在 CRM 个人中心生成，生产环境不要共用开发机 Key。
- `IMAP_AUTH_CODE` 通常不是邮箱登录密码，而是邮箱后台生成的客户端授权码。
- `ATTACHMENT_PUBLIC_BASE_URL` 必须是 CRM 后端能访问到的地址。
- 动态邮箱加载依赖 CRM 用户邮箱授权码和客户邮箱字段已经维护完整。

### 7.4 配置 systemd

目录建议：

```bash
sudo mkdir -p /opt/cordys/mail-monitoring
sudo cp -r "mail monitoring/src" "mail monitoring/docs" "mail monitoring/lib" "mail monitoring/sources.txt" /opt/cordys/mail-monitoring/
sudo cp "mail monitoring/config.properties" /opt/cordys/mail-monitoring/config.properties
sudo chown -R cordys:cordys /opt/cordys/mail-monitoring
```

先在服务器上编译一次：

```bash
cd /opt/cordys/mail-monitoring
sed 's#\\#/#g' sources.txt > sources-linux.txt
mkdir -p out
javac -encoding UTF-8 -d out -cp /opt/cordys/mail-monitoring/lib/mysql-connector-j-8.0.33.jar @sources-linux.txt
```

创建 `/etc/systemd/system/mail-monitoring.service`：

```ini
[Unit]
Description=Cordys Mail Monitoring
After=network.target cordys-crm.service

[Service]
User=cordys
Group=cordys
WorkingDirectory=/opt/cordys/mail-monitoring
ExecStart=/usr/bin/java -cp /opt/cordys/mail-monitoring/out:/opt/cordys/mail-monitoring/lib/mysql-connector-j-8.0.33.jar Main
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

启动：

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now mail-monitoring
sudo journalctl -u mail-monitoring -f
```

## 8. 企业微信监控部署

企业微信监控目录为：

```text
wecom monitoring/
```

它是独立 Java 程序，会：

- 拉取企业微信会话存档。
- 保存拉取游标和原始消息到 `wecom_monitoring_db`。
- 根据 CRM `sys_user.wecom_id` 判断需要监控的员工。
- 根据 CRM `customer.wecom_external_id` 匹配客户。
- 写入 CRM 的 `wecom_ingestion_session_day`、`wecom_ingestion_message`、`wecom_ingestion_media`。
- CRM 后端再定时把待处理企微会话生成跟进记录。

### 8.1 企业微信后台准备

需要在企业微信后台确认：

- 已开通“会话内容存档”。
- 存档范围包含要监控的联系专员。
- 记录企业 ID，即 `WECOM_CORP_ID`。
- 记录会话存档 Secret，即 `WECOM_CORP_SECRET`。
- 生成或上传 RSA 公钥，并把对应私钥保存到服务器，例如 `/opt/cordys/secure/wecom-msgaudit-private.pem`。
- 下载企业微信会话存档 Finance SDK。

企微 SDK 注意：

- Java classpath 需要包含 SDK Java 绑定或项目内 `src/com/tencent/wework/Finance.java` 对应编译产物。
- Linux 运行时需要把 SDK native 动态库目录加入 `LD_LIBRARY_PATH`。
- Windows 运行时需要把 `WeWorkFinanceSdk.dll` 所在目录加入 `PATH`。

### 8.2 CRM 主数据准备

CRM 用户表：

- `sys_user.wecom_id` 必须等于企业微信成员 `userid`。
- 只有维护了 `wecom_id` 的用户才会被监控程序加载。

CRM 客户表：

- `customer.wecom_external_id` 必须等于外部联系人的 `external_userid`。
- 如果客户企业微信 ID 存在自定义字段，需要在 CRM 配置中设置对应字段：

```properties
crm.wecom.customer-external-field-internal-key=<客户企微字段internalKey>
# 或
crm.wecom.customer-external-field-id=<字段ID>
```

### 8.3 创建企业微信监控库

```sql
CREATE DATABASE wecom_monitoring_db CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
```

执行监控库脚本：

```bash
mysql -u root -p wecom_monitoring_db < "wecom monitoring/docs/mysql_企业微信监测库.sql"
```

CRM 库中企微表一般已由 1.6.0 Flyway 创建。如果老库缺少表，可以执行：

```bash
mysql -u root -p cordys-crm < "wecom monitoring/docs/mysql_cordys_crm_企业微信表.sql"
```

执行前先备份 CRM 库。

### 8.4 配置企业微信监控

复制配置：

```bash
cp config.properties.example config.properties
```

生产配置建议：

```properties
ORGANIZATION_ID=<CRM组织ID>
WECOM_CORP_ID=<企业微信企业ID>
WECOM_CORP_SECRET=<会话存档Secret>
POLL_SECONDS=60

DB_ENABLED=true
DB_JDBC_URL=jdbc:mysql://127.0.0.1:3306/wecom_monitoring_db?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8&allowPublicKeyRetrieval=true
DB_USERNAME=wecom_monitor
DB_PASSWORD=<企微监控库密码>

CRM_INGESTION_DB_ENABLED=true
CRM_INGESTION_DB_JDBC_URL=jdbc:mysql://127.0.0.1:3306/cordys-crm?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8&allowPublicKeyRetrieval=true
CRM_INGESTION_DB_USERNAME=cordys_crm
CRM_INGESTION_DB_PASSWORD=<CRM库密码>

WECOM_PRIVATE_KEY_PATH=/opt/cordys/secure/wecom-msgaudit-private.pem

WECOM_API_HOST=https://qyapi.weixin.qq.com
WECOM_CHATDATA_PATH=/cgi-bin/msgaudit/get_chatdata
WECOM_HTTP_CONNECT_TIMEOUT_MS=5000
WECOM_HTTP_READ_TIMEOUT_MS=30000
WECOM_PULL_LIMIT=200
WECOM_SEQ_OVERLAP=200
WECOM_MAX_PAGES_PER_POLL=5
WECOM_ARCHIVE_TIMEOUT_SECONDS=5

WECOM_MEDIA_FETCH_ENABLED=true
WECOM_MEDIA_FETCH_BATCH_SIZE=20
CRM_ATTACHMENT_BASE_DIR=/opt/cordys/data/files
```

注意事项：

- `CRM_ATTACHMENT_BASE_DIR` 必须指向 CRM 后端使用的同一个附件根目录。
- `WECOM_SEQ_OVERLAP` 建议保留一定回拉范围，避免消息乱序或延迟导致漏拉。
- `WECOM_CORP_SECRET` 建议通过环境变量或 systemd 注入，不要写入仓库。
- 如果日志提示 `sys_user.wecom_id list is empty`，说明 CRM 中还没有维护用户企微 userid。
- 如果消息入库但没有生成跟进记录，检查 CRM 配置 `crm.wecom.auto-create-follow=true`。

### 8.5 编译和运行

源码编译：

```bash
cd "wecom monitoring"
mkdir -p out
sed 's#\\#/#g' sources.txt > sources-linux.txt
javac -encoding UTF-8 -d out -cp "<mysql-connector-j路径>:<企微SDK jar路径>" @sources-linux.txt
```

运行：

```bash
export LD_LIBRARY_PATH=/opt/wecom-finance-sdk/lib:$LD_LIBRARY_PATH
java -cp "out:<mysql-connector-j路径>:<企微SDK jar路径>" Main
```

如果使用已有 `wecom-monitor.jar`，仍然要保证 MySQL 驱动和企微 SDK 在 classpath 中：

```bash
export LD_LIBRARY_PATH=/opt/wecom-finance-sdk/lib:$LD_LIBRARY_PATH
java -cp "wecom-monitor.jar:<mysql-connector-j路径>:<企微SDK jar路径>" Main
```

### 8.6 配置 systemd

创建 `/etc/systemd/system/wecom-monitoring.service`：

```ini
[Unit]
Description=Cordys WeCom Monitoring
After=network.target cordys-crm.service

[Service]
User=cordys
Group=cordys
WorkingDirectory=/opt/cordys/wecom-monitoring
Environment="LD_LIBRARY_PATH=/opt/wecom-finance-sdk/lib"
Environment="WECOM_CORP_SECRET=<会话存档Secret>"
ExecStart=/usr/bin/java -cp /opt/cordys/wecom-monitoring/wecom-monitor.jar:/opt/cordys/wecom-monitoring/lib/mysql-connector-j.jar:/opt/wecom-finance-sdk/java/finance-sdk.jar Main
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

如果不用 JAR 而是源码编译，把 `ExecStart` 改为：

```ini
ExecStart=/usr/bin/java -cp /opt/cordys/wecom-monitoring/out:/opt/cordys/wecom-monitoring/lib/mysql-connector-j.jar:/opt/wecom-finance-sdk/java/finance-sdk.jar Main
```

启动：

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now wecom-monitoring
sudo journalctl -u wecom-monitoring -f
```

## 9. Nginx 和 HTTPS

如果 CRM 对外提供访问，建议使用 Nginx 反向代理：

```nginx
server {
    listen 80;
    server_name crm.example.com;

    client_max_body_size 1024m;

    location / {
        proxy_pass http://127.0.0.1:8081;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 300s;
        proxy_send_timeout 300s;
    }
}
```

建议用公司证书或 Let's Encrypt 配置 HTTPS。配置 HTTPS 后，邮件监控 `CRM_BASE_URL` 也可以改为 `https://crm.example.com`。

## 10. 启动顺序

首次部署建议顺序：

1. 启动 MySQL。
2. 启动 Redis。
3. 导入旧数据，或创建空库。
4. 启动 CRM。
5. 登录 CRM，确认组织、用户、客户数据。
6. 维护用户邮箱授权码、用户企微 ID、客户邮箱、客户企微 external_userid。
7. 启动邮件监控。
8. 启动企业微信监控。
9. 做邮件和企微测试，确认自动生成跟进记录。

## 11. 验收检查

CRM 检查：

```bash
curl -I http://127.0.0.1:8081/
systemctl status cordys-crm
tail -f /opt/cordys/logs/cordys-crm/error.log
```

数据库检查：

```sql
SHOW TABLES;
SELECT * FROM cordys_crm_version ORDER BY installed_rank DESC LIMIT 5;
```

邮件监控检查：

```sql
SELECT * FROM mail_monitoring_db.mail_event ORDER BY created_at DESC LIMIT 5;
SELECT * FROM `cordys-crm`.email_webhook_event ORDER BY create_time DESC LIMIT 5;
```

企业微信检查：

```sql
SELECT * FROM wecom_monitoring_db.wecom_sync_checkpoint;
SELECT * FROM `cordys-crm`.wecom_ingestion_session_day ORDER BY update_time DESC LIMIT 5;
SELECT * FROM `cordys-crm`.wecom_ingestion_message ORDER BY create_time DESC LIMIT 5;
```

业务验收：

- 能访问 CRM 登录页。
- 管理员能登录。
- 客户、合同、跟进记录能正常查询。
- 上传附件后能下载。
- 发送一封被监控销售到客户的邮件，CRM 能看到邮件跟进或邮件事件。
- 企业微信被监控成员和已匹配客户发消息后，CRM 能看到企微跟进记录。

## 12. 常见问题

### 12.1 CRM 启动失败：数据库连接失败

检查：

- `spring.datasource.url` 中数据库名是否正确，当前示例是 `cordys-crm`。
- MySQL 是否允许该用户从服务器连接。
- 密码是否包含特殊字符，配置是否需要转义。
- MySQL 是否开启 `utf8mb4`。
- 是否需要 `allowPublicKeyRetrieval=true`。

### 12.2 CRM 启动失败：Redis 连接失败

检查：

- Redis 是否启动。
- `spring.data.redis.host`、`port`、`password` 是否正确。
- Redis 只监听 `127.0.0.1` 时，CRM 是否也在同一主机。

### 12.3 页面打开空白或静态资源 404

检查：

- 是否执行过前端构建。
- `frontend/packages/web/dist` 和 `frontend/packages/mobile/dist` 是否存在。
- 后端 JAR 是否在前端构建之后重新打包。
- Docker 镜像是否重新构建。

### 12.4 邮件监控没有数据

检查：

- 邮箱是否开启 IMAP。
- `IMAP_FOLDER` 是否是正确的“已发送邮件”目录名称。
- 邮箱授权码是否正确。
- `MAILBOX_DYNAMIC_LOAD_ENABLED=true` 时，CRM 用户邮箱授权码和客户邮箱是否维护。
- `mail_event` 是否有记录，错误信息在 `error_message` 字段。

### 12.5 邮件附件无法下载

检查：

- `ATTACHMENT_HTTP_ENABLED=true`。
- `ATTACHMENT_PUBLIC_BASE_URL` 是 CRM 能访问到的地址，不一定是浏览器能访问到的地址。
- `ATTACHMENT_SAVE_DIR` 目录存在，运行用户有读写权限。
- 防火墙是否放行 `ATTACHMENT_HTTP_PORT`。

### 12.6 企业微信监控启动失败

检查：

- `WECOM_CORP_ID` 是否还是占位符。
- `WECOM_CORP_SECRET` 是否为空。
- `WECOM_PRIVATE_KEY_PATH` 是否存在，私钥格式是否正确。
- Finance SDK native 库目录是否加入 `LD_LIBRARY_PATH` 或 `PATH`。
- MySQL 驱动是否在 classpath。

### 12.7 企业微信消息入库但不生成跟进

检查：

- CRM 配置 `crm.wecom.auto-create-follow=true`。
- `crm.wecom.auto-follow-poll-ms` 是否合理。
- `sys_user.wecom_id` 是否等于企业微信成员 `userid`。
- `customer.wecom_external_id` 是否等于客户 `external_userid`。
- `wecom_ingestion_session_day.status` 和 `error_message`。

## 13. 备份建议

生产环境每天至少备份一次：

- CRM 数据库 `cordys-crm`。
- 邮件监控库 `mail_monitoring_db`。
- 企业微信监控库 `wecom_monitoring_db`。
- `/opt/cordys/data/files`。
- `/opt/cordys/conf`。
- 邮件附件目录 `/opt/cordys/mail-monitoring/attachments`。

示例：

```bash
mkdir -p /backup/cordys/$(date +%F)
mysqldump -u root -p --single-transaction --default-character-set=utf8mb4 cordys-crm > /backup/cordys/$(date +%F)/cordys-crm.sql
mysqldump -u root -p --single-transaction --default-character-set=utf8mb4 mail_monitoring_db > /backup/cordys/$(date +%F)/mail_monitoring_db.sql
mysqldump -u root -p --single-transaction --default-character-set=utf8mb4 wecom_monitoring_db > /backup/cordys/$(date +%F)/wecom_monitoring_db.sql
rsync -a /opt/cordys/data/files/ /backup/cordys/$(date +%F)/files/
rsync -a /opt/cordys/conf/ /backup/cordys/$(date +%F)/conf/
```

备份文件中包含客户资料、聊天内容、邮件内容和密钥配置，必须加密保存并限制访问。

## 14. 交付给部署人员的信息清单

交付时请补齐以下信息：

- 服务器 IP、域名、SSH 账号。
- CRM 访问域名，例如 `https://crm.example.com`。
- MySQL 部署方式：内置或外部。
- Redis 部署方式：内置或外部。
- CRM 数据库名、账号、密码。
- 邮件监控数据库名、账号、密码。
- 企业微信监控数据库名、账号、密码。
- CRM 初始管理员账号或已有管理员账号。
- CRM 组织 ID。
- 邮件监控用 CRM Access Key / Secret Key。
- 需要监控的销售邮箱列表和 IMAP 授权码，或确认由 CRM 动态读取。
- 企业微信 `corpId`、会话存档 Secret、RSA 私钥文件。
- 企业微信 Finance SDK 文件和 native 动态库路径。
- 是否启用 AI Agent，以及对应模型服务地址和 API Key。

部署完成后，建议由部署人员输出一份最终运行配置表，但不要把密码明文发到公共群或代码仓库。
