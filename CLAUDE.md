# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

Cordys CRM 是飞致云（1Panel-dev）出品的开源 AI CRM 系统，覆盖从线索到回款（L2C）的全流程。本仓库使用 FIT2CLOUD Open Source License（基于 GPLv3，附加限制）：**不得替换或修改 Logo 与版权信息**。

## 技术栈速览

- 后端：Spring Boot 3.5.11 + **Java 21**（`maven.compiler.release=21`，低于 JDK 21 无法编译）
- 持久层：MyBatis（PageHelper 6.1.1 分页）、MySQL
- 缓存/会话：Redis（Spring Session `indexed` 模式，默认 12h 过期）、Redisson 3.52
- 安全：Shiro 2.1、JWT
- 调度：自研 `quartz-starter` 1.0.0（注意：`Application.java` 显式排除 `QuartzAutoConfiguration`）
- 其他：fastexcel 1.3、springdoc-openapi 2.8、JaCoCo
- 前端：pnpm monorepo，Vue 3 + Vite + TypeScript；Web 端用 Naive-UI，Mobile 端用 Vant
- 构建要求：Node `v22.16.0`、pnpm `10.4.1`（Maven 构建时由 `frontend-maven-plugin` 自动下载）

## 仓库布局

```
backend/
├── framework/   # 基础框架：AOP、MyBatis 增强、安全、文件、公共工具（详见 backend/framework/README.md）
├── crm/         # 业务模块：clue/contract/customer/dashboard/follow/opportunity/order/product/search/system
│                # 集成模块：integration/{agent,dataease,dingtalk,lark,qcc,sqlbot,sso,sync,tender,webhook,wecom}
└── app/         # 可执行 Spring Boot 应用，主类 cn.cordys.Application；仅此模块有 main

frontend/
└── packages/
    ├── lib-shared/  # 共享 API/enums/hooks/locale/model/types（workspace 别名 @lib/shared）
    ├── web/         # PC 端（@cordys/web），Naive-UI
    └── mobile/      # 移动端（@cordys/mobile），Vant，企微登录

docs/                # 功能设计文档（企微监测、邮件 Webhook、邮箱账号动态加载等中文方案）
installer/           # Dockerfile、MCP 服务、一键脚本

mail monitoring/     # 独立 Java 服务：IMAP 轮询 + 附件下载，通过 Webhook 推送 CRM（不在 Maven 聚合内）
wecom monitoring/    # 独立 Java 服务：企微会话存档轮询 + 归一化落库（不在 Maven 聚合内）
```

Maven 使用 CI-Friendly `${revision}=main`（父 POM 中定义），通过 `flatten-maven-plugin` 生成 `.flattened-pom.xml`。

### mail monitoring / wecom monitoring（独立服务）

这两个目录**不是** Maven 模块，也**不参与主打包**。它们是纯 Java + JDBC + `HttpURLConnection` 实现的独立轮询服务，用 `javac` + `sources.txt` 手动编译：

```bash
# 编译（以 wecom monitoring 为例；mail monitoring 流程相同）
cd "wecom monitoring"
javac -encoding UTF-8 -d out -cp <mysql-connector-j.jar> @sources.txt

# 运行（classpath 含 out 目录与 MySQL 驱动；企微真实模式还需加入企业微信 Finance SDK JAR 和对应 native 动态库）
java -cp "out;<mysql-connector-j.jar>" Main
```

运行前把 `config.properties.example` 复制为 `config.properties`。关键协作点：

- **mail monitoring**：从 IMAP 拉发件箱，附件落 `ATTACHMENT_SAVE_DIR`，通过 `CRM_WEBHOOK_PATH=/api/webhook/email-log` 调用 CRM。支持从 CRM 库动态加载监测邮箱（`MAILBOX_DYNAMIC_LOAD_ENABLED`）；建表脚本见 `mail monitoring/docs/mysql建表_邮件监测.sql`
- **wecom monitoring**：轮询真实企业微信会话存档，写入自有库 `wecom_monitor` 与 CRM 缓冲表；被监测专员从 CRM 库 `sys_user.wecom_id` 自动读取，客户匹配使用 `customer.wecom_external_id`；两份建表脚本在 `wecom monitoring/docs/`
- 对应的 **后端接收侧** 在 `backend/crm/.../integration/webhook/`（邮件）与 `integration/wecom/`（企微）；CRM 侧运行时开关 `crm.webhook.create-follow`、`crm.wecom.auto-create-follow` 控制是否自动建跟进

## 常用命令

### Windows 路径注意
- 脚本使用 **`.\mvnw.cmd`**（不要用 `./mvnw`，除非在 Git Bash）
- `--spring.config.additional-location` 必须使用 `file:///E:/...` 三斜杠形式

### 本地开发（最常用）

```bash
# 1. 启动后端（Spring Boot，默认 8081）
#    注意：必须指定 -f backend/app/pom.xml，不能用 -pl backend/app，否则聚合模块会报 "找不到 main class"
./mvnw.cmd -f backend/app/pom.xml spring-boot:run \
  "-Dspring-boot.run.arguments=--spring.config.additional-location=file:///E:/CordysCRM-1.6.0/cordys-crm.properties"

# 2. 启动 Web 前端（Vite，端口通常 5173）
cd frontend/packages/web && npm run dev
#   等价于：pnpm --filter @cordys/web dev

# 3. 启动 Mobile 前端（Vite，端口 3000）
cd frontend/packages/mobile && npm run dev
#   企微模拟登录：从 web 端 localStorage 复制 sessionId + csrfToken 到 mobile

# 前端依赖（首次或依赖变更）
cd frontend && pnpm install
```

### 构建与打包

```bash
# 安装父 POM（多模块项目必需，修改 properties 后需重跑）
./mvnw install -N

# 仅构建后端（跳测跳前端复制）
./mvnw clean install -DskipTests -DskipAntRunForJenkins --file backend/pom.xml

# 完整构建（前端 + 后端 → 产出 backend/app/target/app-main.jar）
./mvnw clean package -DskipTests

# 运行已打包 jar
java -jar backend/app/target/app-main.jar --spring.config.additional-location=file:///E:/CordysCRM-1.6.0/cordys-crm.properties

# 预构建脚本（Windows PowerShell）
./start-backend.ps1
```

### 测试与代码质量

```bash
# 后端单模块跑测试（根 pom 不建议全量跑）
./mvnw test -pl backend/crm

# 单个测试类/方法
./mvnw -pl backend/crm -Dtest=ContractPaymentPlanServiceTest test
./mvnw -pl backend/crm -Dtest=ContractPaymentPlanServiceTest#testXxx test

# JaCoCo 报告（prepare-package 阶段自动生成，排除 mapper/domain/common 等基础设施）
./mvnw verify

# 前端 lint（在 packages/web 或 packages/mobile 下）
npm run lint           # ESLint --fix
npm run lint:styles    # Stylelint --fix
npm run type:check     # vue-tsc --noEmit
```

## 架构关键点

### 后端模块依赖
`app → crm → framework`（单向，严格分层）。新业务代码默认落到 `backend/crm`；通用能力下沉到 `backend/framework`。`backend/app` 只放启动类、监听器和最终打包配置。

### 前端构建如何落到后端
`backend/app/pom.xml` 中的 `maven-antrun-plugin` 在 `generate-resources` 阶段把前端产物复制到 Spring Boot 静态资源：
- `frontend/packages/web/dist` → `backend/app/src/main/resources/static/`
- `frontend/packages/mobile/dist` → `backend/app/src/main/resources/static/mobile/`

想跳过前端复制（后端独立调试时），加 `-DskipAntRunForJenkins`。

### 配置加载顺序
`Application.java` 通过 `@PropertySource` 按顺序加载：
1. `classpath:commons.properties`（模块内置默认值）
2. `file:/opt/cordys/conf/cordys-crm.properties`（生产路径）
3. 运行时可再通过 `--spring.config.additional-location=file:///...` 追加本地配置（开发常用）

仓库根目录的 `cordys-crm.properties` 只用于本地运行；不要把含真实密码的文件提交到仓库。

### Framework 能力（写业务前先看）
- **操作日志**：`@OperationLog` 注解 + SpEL（`{{#x}}`），多条日志可叠加；异常自动注入 `#_errorMsg`
- **Lambda 查询**：`userMapper.selectListByLambda(new LambdaQueryWrapper<User>()...)`
- **统一响应/分页/异常体系**：`common.response`、`common.pager`、`common.exception`
- 详见 `backend/framework/README.md`

### 集成/监测子系统
`backend/crm/.../integration/` 下每个厂商一个子包（企微、钉钉、飞书、DataEase、SQLBot、MCP agent、SSO 等）。监测/Webhook 的对接协议和规则放在 `docs/` 目录的中文设计文档中，修改相关模块前先读：
- `docs/CRM邮件事件Webhook接口对接说明.md`
- `docs/企业微信监测与CRM匹配规则.md`
- `docs/邮箱账号动态加载改造方案.md`

### 运行时关键配置标志
- `crm.webhook.create-follow`：Webhook 是否自动建跟进（稳定期建议先只落库）
- `crm.wecom.auto-create-follow` / `crm.wecom.auto-follow-poll-ms`：企微缓冲自动写跟进

## 贡献规范（来自 CONTRIBUTING.md）

- PR 目标分支是 `master`，每个 PR 必须可独立安全合并，不得留半成品
- 大特性先开 Issue 讨论，再拆成小 PR 提交
- 仓库使用 `.typos.toml` 做拼写检查，`frontend/public`、`region.json`、`iconfont.json` 等二进制/自动生成文件在排除列表
