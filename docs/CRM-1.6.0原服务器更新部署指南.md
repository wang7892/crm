# Cordys CRM 1.6.0 原服务器更新部署指南

本次源码核对日期：2026-10-12。用户已确认更新原服务器。本文根据当前代码和历史部署记录整理，尚未连接服务器核验，不代表服务器现场状态已确认，也不代表本次构建已通过。

流程：本地提交并推送 GitHub `main` → 服务器核对配置和数据 → 拉取代码 → 完整构建 Web、Mobile、后端 → 备份并切换 JAR → 验证数据库迁移和业务。

## 1. 历史部署记录与本次采用的方式

| 项目 | 历史记录，需要在服务器核实 |
| --- | --- |
| 服务器 | `192.168.30.114`，SSH 用户 `admin` |
| Git 仓库 | `https://github.com/wang7892/crm.git`，部署分支 `main` |
| 源码目录 | `/data/CORDYSCRM` |
| 运行 JAR | `/data/CORDYSCRM/backend/app/target/app-main.jar` |
| 私有配置 | `/data/CORDYSCRM/conf/cordys-crm.properties` |
| 服务 | `cordys-crm.service` |
| 入口 | Nginx `8093` → Java `8081` |
| 数据库 | 本机 MySQL `3306`，库名 `cordys-crm` |
| 迁移历史表 | `cordys_crm_version` |

服务器沿用源码/JAR 与 systemd 部署。历史资料参考 [生产环境部署与数据库运维手册](./CRM生产环境部署与数据库运维手册.md) 和 [Git部署与服务器配置保留说明](./CRM%20Git部署与服务器配置保留说明.md)。原 Git 部署说明中的后端单独打包、`kill` 和 `nohup` 启动属于历史流程。

当前根 POM 要求 JDK 21，前端 Maven 插件安装 Node `v22.16.0` 和 pnpm `10.4.1`。根目录 `mvn clean package -DskipTests` 会构建两套前端，并将其复制进后端 JAR；前端构建仍会执行 TypeScript 类型检查，`-DskipTests` 只跳过 Java 测试执行。

## 2. 上传 GitHub 前

在本地 `E:\CRM\CordysCRM-1.6.0` 检查：

```powershell
git status --short --branch
git diff --check
git diff --stat
```

本次新增 Java 类、共享前端组件和迁移 SQL 目前包含未跟踪文件。提交时必须包含这些业务文件，同时包含有意删除的旧文件。真实配置、`runtime/`、构建产物、`connection.key` 和 `.mv.db` 文件不提交。

选择需要发布的文件或目录暂存，然后检查并推送：

```powershell
git diff --cached --check
git diff --cached --stat
git commit -m "feat: 更新智能体数据源、问数知识与分析能力"
git push origin HEAD:main
git log -1 --oneline
```

本地分支当前名为 `master`，上面明确推送到远端 `main`。记录提交号，服务器拉取后必须对应同一提交。

## 3. 服务器先做只读核对

```bash
cd /data/CORDYSCRM
git status --short --branch
git log -1 --oneline
java -version
mvn -version
systemctl show cordys-crm -p User -p Group -p WorkingDirectory -p FragmentPath
ls -l /data/CORDYSCRM/conf/cordys-crm.properties
readlink -f /data/CORDYSCRM/conf/cordys-crm.properties
sudo ss -ltnp | grep -E ':8081|:8093'
```

在服务器本机查看 service 的实际启动命令，确认运行 JAR 和配置路径与上表一致。输出含凭据时不要粘贴到公开渠道。检查邮件与企微监控实际如何启动，以及它们的配置、SDK、附件目录是否仍指向服务器私有文件。

查询当前迁移历史，将 `CRM_DB_ADMIN` 换成实际备份/运维账号；`-p` 会交互询问密码：

```bash
mysql -h 127.0.0.1 -P 3306 -u CRM_DB_ADMIN -p -D cordys-crm -e \
  "SELECT version,description,success FROM cordys_crm_version ORDER BY installed_rank DESC LIMIT 15;"
```

工作区存在本地修改、配置链接失效、实际 JAR 路径不同或磁盘空间不足时，先解决现场问题。不要覆盖生产配置或用 `git reset --hard` 清理服务器。

## 4. 本次代码额外需要核对的内容

### 4.1 新增数据库迁移

相对于当前本地已提交版本，本次工作区新增：

| 迁移版本 | 内容 |
| --- | --- |
| `1.6.0.51.1` | 联系专员的知识库管理权限 |
| `1.6.0.52` | 公司知识文档正文版本与编辑字段 |
| `1.6.0.53` | 智能体数据源配置表 |
| `1.6.0.54` | 问数术语、SQL 示例及并发锁表 |
| `1.6.0.55` | 问数知识设置表 |

服务器可能还有更早的待执行迁移，以实际历史表为准。生产启动需启用 Flyway，由新 JAR 执行未应用迁移。特别核对 `1.6.0.51.1`：如果现场数据库已执行更高版本而尚未应用该脚本，需要先评估补迁移方式，不能仅因最新版本为 `1.6.0.55` 就认定所有迁移均已完成。

### 4.2 AI 数据源持久化

在服务器实际加载的私有配置中，为新数据源存储选择固定绝对路径，例如：

```properties
crm.ai-agent.datasource.storage-path=/data/CORDYSCRM-shared/ai-agent/datasources
```

该目录需允许 CRM 服务运行用户读写，包含数据库密码加密密钥 `connection.key` 和 Excel/CSV 导入形成的 H2 `.mv.db` 文件。数据库中的数据源配置记录、密钥和文件库应一起备份。已经存在数据源时，先核对旧存储目录并保留原密钥和文件，再调整路径。

旧知识文档实际使用服务工作目录下的 `runtime/uploads/knowledge`，任务附件默认使用 `./data/task-attachments`。升级时保留原 service 的 `WorkingDirectory`，并核对配置是否覆盖任务附件路径。需要保留的数据包括这些实际运行目录、`/data/CORDYSCRM-shared` 和 `/opt/cordys/data/files`。

### 4.3 问数知识与模型服务

当前问数知识使用术语和 SQL 示例。旧公司文档上传、自动解析流程已退出当前问数链路；旧表和文件作为历史资料保留，有用内容需要整理为术语或 SQL 示例，升级不会自动转换。参见 [问数知识说明](./AI_Agent_Query_Knowledge.md)。

现有模型 API 地址、模型名、密钥继续在服务器私有配置管理。新增 `crm.ai-agent.llm.api-type` 默认 `chat-completions`；仅在实际供应商支持且需要时配置 `responses`。本地模型地址不会通过 Git 自动带到服务器。

问数向量检索是可选能力；不配置时使用关键词检索。启用时核对 `crm.ai-agent.query-knowledge.embedding-*` 的实际服务器地址、模型和密钥，不能直接使用开发电脑的 `127.0.0.1:11434`。

### 4.4 流式响应

新聊天使用 `/ai-agent/chat/stream`，后端已返回 `X-Accel-Buffering: no`。上线后通过 `8093` 验证处理过程和回答能逐步显示。如果等待结束才一次显示，检查 Nginx 是否忽略该响应头，以及实际匹配的代理规则是否缓存或缓冲流式响应；按需关闭该接口的代理缓冲，检查配置后 reload Nginx。

## 5. 拉取并在独立目录完整构建

推荐在独立 Git worktree 构建，避免 `mvn clean` 删除正在运行的 JAR，也缩短正式切换的停机时间。以下命令使用 Bash；分块执行并检查结果，不要在失败后继续发布。

先确认 GitHub 已包含本次目标提交，服务器工作区已完成核对。下面几个步骤在同一个 Bash 会话执行，以保留发布变量：

```bash
set -euo pipefail
cd /data/CORDYSCRM
deploy_previous_commit="$(git rev-parse HEAD)"
git fetch origin
git log --oneline HEAD..origin/main
git pull --ff-only origin main
git log -1 --oneline
```

核对提交号与本地推送的提交一致，然后构建：

```bash
deploy_commit="$(git rev-parse HEAD)"
deploy_build_dir="/data/CORDYSCRM-build/$deploy_commit"
mkdir -p /data/CORDYSCRM-build
git worktree add --detach "$deploy_build_dir" "$deploy_commit"
cd "$deploy_build_dir"
mvn clean package -DskipTests
test -s backend/app/target/app-main.jar
jar tf backend/app/target/app-main.jar > /tmp/cordys-crm-jar-contents.txt
grep '^BOOT-INF/classes/static/index.html$' /tmp/cordys-crm-jar-contents.txt
grep '^BOOT-INF/classes/static/mobile/index.html$' /tmp/cordys-crm-jar-contents.txt
```

必须看到 `BUILD SUCCESS`，并找到 Web 和 Mobile 的 `index.html`。构建目录已存在时，先检查是否为同一提交的构建目录，再决定复用；不要直接覆盖其他发布目录。依赖下载需要服务器访问 Maven、Node 和 pnpm 下载源。

独立构建目录只用于生成 JAR，不在其中启动 CRM。正式服务继续从原路径运行，使用原配置和工作目录。

## 6. 备份、切换和启动

切换前安排短暂停机窗口，确认独立邮件/企微监控或其他写入进程是否需要一并暂停。记录其实际启停方式；不能假设它们都已改成 systemd。

建立权限受限的发布备份目录，保留当前运行 JAR 和实际配置：

```bash
cd /data/CORDYSCRM
umask 077
deploy_backup_dir="/data/backup/cordys-crm-deploy-$(date +%Y%m%d_%H%M%S)"
mkdir -p "$deploy_backup_dir"
printf '%s\n' "$deploy_previous_commit" > "$deploy_backup_dir/source-before-pull.txt"
printf '%s\n' "$deploy_commit" > "$deploy_backup_dir/source-to-deploy.txt"
cp backend/app/target/app-main.jar "$deploy_backup_dir/app-before-deploy.jar"
sha256sum "$deploy_backup_dir/app-before-deploy.jar" > "$deploy_backup_dir/app-before-deploy.sha256"
cp -L conf/cordys-crm.properties "$deploy_backup_dir/cordys-crm.properties"
sudo systemctl stop cordys-crm
```

备份同时记录拉取前的源码提交、新提交和旧 JAR 校验值。源码提交与正在运行的 JAR 是否对应，仍需现场核对。确认 `8081` 已无旧 CRM 进程监听，并在暂停必要写入后备份数据库：

```bash
mysqldump -h 127.0.0.1 -P 3306 -u CRM_DB_ADMIN -p \
  --single-transaction --routines --events --triggers \
  --databases cordys-crm > "$deploy_backup_dir/cordys-crm.sql"
test -s "$deploy_backup_dir/cordys-crm.sql"
ls -lh "$deploy_backup_dir/cordys-crm.sql"
```

需同时备份前文确认的持久化目录，尤其是已存在的数据源密钥和 H2 文件库。H2 文件库应在使用它的 CRM 进程停止后复制。备份失败时不要切换 JAR。

```bash
cp "$deploy_build_dir/backend/app/target/app-main.jar" backend/app/target/app-main.jar
sudo systemctl start cordys-crm
sudo systemctl status cordys-crm --no-pager
sudo journalctl -u cordys-crm -n 200 --no-pager
```

覆盖 JAR 前确认运行用户、目标文件权限和实际路径正确。当前构建包含新增 JDBC 驱动、SQL 解析和 CSV 依赖，无需在服务器手工逐个安装这些 JAR。

## 7. 发布验收与失败处理

```bash
curl --fail --show-error --max-time 15 -I http://127.0.0.1:8081/
curl --fail --show-error --max-time 15 -I http://127.0.0.1:8093/
mysql -h 127.0.0.1 -P 3306 -u CRM_DB_ADMIN -p -D cordys-crm -e \
  "SELECT version,description,success FROM cordys_crm_version ORDER BY installed_rank DESC LIMIT 15;"
```

等待启动完成后核对入口 HTTP 200、迁移记录和日志。对本次五个新增迁移逐项确认已成功应用，随后强制刷新浏览器，测试登录、客户/合同/订单、附件下载、智能体聊天与流式过程、数据源配置和问数知识。恢复先前暂停的监控服务，并检查邮件、企微及 MLS 同步状态。

构建失败时原 CRM 可继续运行；切换失败时先保留日志和数据库现场。尚未执行数据库迁移时可以恢复备份 JAR；已经迁移时应先评估旧代码与新结构的兼容性，不能认为仅换回旧 JAR 就完成数据库回滚。数据库以向前修复为优先，整库恢复需要单独确认恢复范围和期间新数据。

本次准备阶段仅核对源码与文档。服务器预检、完整构建、生产备份、迁移与业务验收仍需在上传 GitHub 后实际完成。
