# CRM Git部署与服务器配置保留说明（AI部署前必读）

> **当前环境更新（2026-07-15）**：执行任何生产发布、数据库迁移、恢复或本地生产库连接前，必须先阅读 [CRM生产环境部署与数据库运维手册](./CRM生产环境部署与数据库运维手册.md)。当前 CRM 使用 `cordys-crm.service` 管理，正常发布必须在项目根目录执行完整前后端构建。若本文后续历史内容与新手册冲突，以新手册及服务器现场状态为准。

## 0. 给 AI 的使用说明

当用户下次说“我本地改完代码了，帮我部署到服务器”或“服务器怎么更新代码”时，请先阅读本文。

本项目的部署方式不是重新上传整个文件夹，也不是重新 clone 覆盖 `/data/CORDYSCRM`。正确方式是：

1. 本地修改代码。
2. 本地提交并推送到 GitHub。
3. 服务器进入 `/data/CORDYSCRM` 执行 `git pull origin main`。
4. 根据本次修改范围，重新打包或重新编译对应服务。
5. 重启对应服务。

必须牢记：

```text
GitHub 只保存代码。
服务器配置、密钥、Linux SDK、邮件附件、企微媒体落盘文件都只保存在服务器。
不要把服务器私有文件提交到 GitHub。
不要用新 clone 的目录直接覆盖服务器已有目录，除非已经确认软链接和共享目录都恢复好了。
```

服务器上已经做过软链接改造：

```text
/data/CORDYSCRM/conf/cordys-crm.properties
/data/CORDYSCRM/mail monitoring/config.properties
/data/CORDYSCRM/mail monitoring/attachments
/data/CORDYSCRM/wecom monitoring/config.properties
/data/CORDYSCRM/wecom monitoring/secure
/data/CORDYSCRM/wecom monitoring/sdk
```

这些都应指向 `/data/CORDYSCRM-shared` 下的真实服务器文件。

企微文件、图片、表情落盘目录是：

```text
/opt/cordys/data/files
```

这个目录也不属于 Git，不能删除，不能覆盖。

## 0.1 下次部署时 AI 应该先确认什么

在本地确认：

```bash
git status --short
git log --oneline -3
```

如果用户希望部署本地新改动，应确认改动已经提交并推送到远端 `main`。本地当前分支可能是 `master`，远端部署分支是 `main`，推送命令通常是：

```bash
git push origin master:main
```

在服务器确认：

```bash
cd /data/CORDYSCRM
git status --short
git log --oneline -3
ls -l /data/CORDYSCRM/conf
ls -l "/data/CORDYSCRM/mail monitoring"
ls -l "/data/CORDYSCRM/wecom monitoring"
```

如果看到配置、附件、secure、sdk 是软链接，说明服务器私有文件保留方式正常。

## 0.2 下次部署时 AI 应该避免什么

不要执行：

```bash
rm -rf /data/CORDYSCRM
git clone ...
```

不要覆盖：

```bash
/data/CORDYSCRM-shared
/opt/cordys/data/files
```

不要把这些文件提交到 GitHub：

```text
cordys-crm.properties
mail monitoring/config.properties
mail monitoring/attachments/
wecom monitoring/config.properties
wecom monitoring/secure/
wecom monitoring/sdk/
```

如果 GitHub 拉取失败，先判断是服务器访问 GitHub 网络问题，不要误判为代码问题。常见处理：

```bash
git config --global http.version HTTP/1.1
git pull origin main
```

如果仍失败，需要服务器代理，或手动上传本次变更文件。

本文说明以后如何更新服务器代码，以及哪些内容只保留在服务器上，不能提交到 GitHub。

## 一、核心原则

GitHub 只管理代码和示例配置。

服务器自己的配置、密钥、Linux SDK、邮件附件、企微媒体文件都保留在服务器固定目录，不跟随 Git 更新。

服务器目录分三类：

```bash
/data/CORDYSCRM
/data/CORDYSCRM-shared
/opt/cordys/data/files
```

含义：

```text
/data/CORDYSCRM              Git 拉下来的项目代码
/data/CORDYSCRM-shared       服务器私有配置、密钥、SDK、邮件附件
/opt/cordys/data/files       CRM/企微媒体落盘实体文件
```

以后更新代码时，不要删除或覆盖 `/data/CORDYSCRM-shared` 和 `/opt/cordys/data/files`。

## 二、服务器私有文件

这些文件或目录只放服务器，不上传 GitHub：

```bash
/data/CORDYSCRM-shared/conf/cordys-crm.properties
/data/CORDYSCRM-shared/mail/config.properties
/data/CORDYSCRM-shared/mail/attachments
/data/CORDYSCRM-shared/wecom/config.properties
/data/CORDYSCRM-shared/wecom/secure
/data/CORDYSCRM-shared/wecom/sdk
/opt/cordys/data/files
```

当前项目目录中对应位置应使用软链接：

```bash
/data/CORDYSCRM/conf/cordys-crm.properties -> /data/CORDYSCRM-shared/conf/cordys-crm.properties
/data/CORDYSCRM/mail monitoring/config.properties -> /data/CORDYSCRM-shared/mail/config.properties
/data/CORDYSCRM/mail monitoring/attachments -> /data/CORDYSCRM-shared/mail/attachments
/data/CORDYSCRM/wecom monitoring/config.properties -> /data/CORDYSCRM-shared/wecom/config.properties
/data/CORDYSCRM/wecom monitoring/secure -> /data/CORDYSCRM-shared/wecom/secure
/data/CORDYSCRM/wecom monitoring/sdk -> /data/CORDYSCRM-shared/wecom/sdk
```

检查软链接：

```bash
ls -l /data/CORDYSCRM/conf
ls -l "/data/CORDYSCRM/mail monitoring"
ls -l "/data/CORDYSCRM/wecom monitoring"
```

## 三、本地修改代码后的更新流程

本地修改代码后：

```bash
git add .
git commit -m "说明本次修改"
git push origin master:main
```

服务器更新代码：

```bash
cd /data/CORDYSCRM
git pull origin main
```

如果 GitHub 网络失败，可以重试：

```bash
git config --global http.version HTTP/1.1
git pull origin main
```

如果服务器访问 GitHub 仍然不稳定，需要给服务器配置代理，或手动上传本次修改的文件。

## 四、更新后端服务

代码拉取后，如果修改了后端代码，重新打包：

```bash
cd /data/CORDYSCRM/backend
mvn -pl app -am -DskipTests clean package
```

重启后端：

```bash
pid=$(ss -lntp | awk '$4 ~ /:8081$/ && $0 ~ /java/ {print $0; exit}' | sed -n 's/.*pid=\([0-9][0-9]*\).*/\1/p')
[ -n "$pid" ] && kill "$pid"

nohup java -Dfile.encoding=UTF-8 -Xms512m -Xmx2g \
  -jar /data/CORDYSCRM/backend/app/target/app-main.jar \
  --spring.config.additional-location=file:///data/CORDYSCRM/conf/cordys-crm.properties \
  --server.port=8081 \
  > /data/logs/cordys-crm/cordys-crm.out 2>&1 &
```

检查：

```bash
ss -lntp | grep -E ':8081|:8093'
tail -n 100 /data/logs/cordys-crm/cordys-crm.out
```

正常端口：

```text
8081 -> Java 后端
8093 -> Nginx 前端入口
```

## 五、更新邮件监控

如果修改了 `mail monitoring` 代码，拉取代码后重新编译：

```bash
cd "/data/CORDYSCRM/mail monitoring"

mkdir -p out
find src -name "*.java" > sources-linux.txt

javac -encoding UTF-8 \
  -cp "lib/mysql-connector-j-8.0.33.jar" \
  -d out \
  @sources-linux.txt
```

重启邮件监控：

```bash
ps -ef | grep 'mysql-connector-j-8.0.33.jar' | grep Main | grep -v grep
```

如果有多个邮件监控进程，全部停掉，只保留重新启动的一个：

```bash
kill PID1 PID2
```

启动：

```bash
cd "/data/CORDYSCRM/mail monitoring"

nohup java -Dfile.encoding=UTF-8 \
  -cp "out:lib/mysql-connector-j-8.0.33.jar" \
  Main > mail-monitor.out 2>&1 &
```

检查：

```bash
ss -lntp | grep ':18190'
tail -n 100 "/data/CORDYSCRM/mail monitoring/mail-monitor.out"
```

`18190` 是邮件附件下载服务端口，只监听服务器本机，由 Nginx 代理出去。

## 六、更新企微监控

如果修改了 `wecom monitoring` 代码，拉取代码后重新编译：

```bash
cd "/data/CORDYSCRM/wecom monitoring"

mkdir -p out
find src -name "*.java" > sources-linux.txt

javac -encoding UTF-8 \
  -cp "/data/CORDYSCRM/mail monitoring/lib/mysql-connector-j-8.0.33.jar" \
  -d out \
  @sources-linux.txt
```

重启企微监控：

```bash
pid=$(ps -ef | awk '/wecom monitoring/ && /Main/ && !/awk/ {print $2}')
[ -n "$pid" ] && kill "$pid"

cd "/data/CORDYSCRM/wecom monitoring"

nohup java -Dfile.encoding=UTF-8 \
  -Djava.library.path="/data/CORDYSCRM/wecom monitoring/sdk" \
  -cp "out:/data/CORDYSCRM/mail monitoring/lib/mysql-connector-j-8.0.33.jar" \
  Main > wecom-monitor.out 2>&1 &
```

检查：

```bash
ps -ef | grep 'wecom monitoring' | grep -v grep
tail -n 100 "/data/CORDYSCRM/wecom monitoring/wecom-monitor.out"
```

## 七、企微图片、表情、文件落盘

企微媒体落盘依赖服务器私有配置：

```properties
WECOM_MEDIA_FETCH_ENABLED=true
CRM_ATTACHMENT_BASE_DIR=/opt/cordys/data/files
```

目录必须存在并且当前运行用户可写：

```bash
sudo mkdir -p /opt/cordys/data/files
sudo chown -R admin:admin /opt/cordys/data/files
```

测试写入：

```bash
touch /opt/cordys/data/files/test-write
rm -f /opt/cordys/data/files/test-write
```

如果页面显示“文件暂未落盘”或“表情暂未落盘”，查看日志：

```bash
tail -n 300 "/data/CORDYSCRM/wecom monitoring/wecom-monitor.out" | grep -E "WECOM_MEDIA|media landing|Finance|GetMediaData|ERROR|failed|processed"
```

查看媒体状态：

```bash
mysql -u root -p -e "SELECT fetch_status, COUNT(*) FROM \`cordys-crm\`.wecom_ingestion_media GROUP BY fetch_status;"
```

需要重试失败媒体时：

```bash
mysql -u root -p -e "UPDATE \`cordys-crm\`.wecom_ingestion_media SET fetch_status='PENDING' WHERE fetch_status='FAIL';"
```

## 八、Nginx 附件代理

`/etc/nginx/sites-enabled/mls-crm` 中应包含：

```nginx
server {
    listen 8093;
    server_name _;
    client_max_body_size 1024m;

    location ^~ /api/attachments/ {
        proxy_pass http://127.0.0.1:18190/api/attachments/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 300s;
        proxy_send_timeout 300s;
    }

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

修改后检查并重启：

```bash
sudo nginx -t
sudo systemctl restart nginx
```

## 九、必须备份的服务器数据

代码可以从 GitHub 拉回来，下面两个目录必须备份：

```bash
/data/CORDYSCRM-shared
/opt/cordys/data/files
```

建议也保留日志：

```bash
/data/logs/cordys-crm
```

## 十、不要提交到 GitHub 的内容

不要提交：

```text
cordys-crm.properties
mail monitoring/config.properties
mail monitoring/attachments/
wecom monitoring/config.properties
wecom monitoring/secure/
wecom monitoring/sdk/
```

如果这些内容曾经提交到公开仓库，需要及时更换数据库密码、CRM Key、企微密钥和私钥。
