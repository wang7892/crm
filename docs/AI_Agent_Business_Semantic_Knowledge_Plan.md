# AI Agent 公司文档知识库开发方案

## 背景

当前 AI Agent 已具备聊天、LLM 问题解析、数据库查询、SQL 白名单和问题记录能力。之前规划过“业务语义表”和“意图模板表”，由业务人员手工维护同义词、问法样例和意图规则。

这条路线可以解决一部分问题，但维护成本较高：

- 公司业务词很多，靠人工一条条维护很容易遗漏。
- 同一个业务含义会出现在制度、产品资料、报价规则、订单规则、售后政策等文档中，单独抽成表会丢上下文。
- 每次遇到新问法都补配置，长期仍然像“换一种方式改代码”。
- 智能体更需要理解完整业务资料，而不是只知道几个同义词。

因此公司知识库改为“文档知识库”方案：用户在前端上传 Word、PDF 等公司资料，系统解析文档、切分知识片段、建立检索索引，智能体回答问题前先检索公司知识，再结合数据库查询或直接回答。

## 目标

建设一个可维护、可检索、可接入智能体问答流程的公司文档知识库。

目标能力：

1. 用户可以在“公司知识库”页面上传 Word、PDF 等公司资料。
2. 系统自动解析文档文本，记录解析状态和失败原因。
3. 系统将文档切分成知识片段，保留文件名、页码、段落、标题等来源信息。
4. 智能体回答前先检索公司知识库，获得相关业务上下文。
5. 智能体可以基于公司文档理解同义词、业务规则、字段口径、流程规则。
6. 回答时尽量引用知识来源，便于用户判断答案依据。
7. 后续提升正确率主要通过上传或更新文档完成，而不是频繁改代码。

目标链路：

```text
上传 Word/PDF
  ↓
保存文件和文档记录
  ↓
解析文本
  ↓
文本清洗和切分
  ↓
生成检索索引
  ↓
用户提问
  ↓
检索公司知识片段
  ↓
问题重写和语义增强
  ↓
判断回答方式：文档回答 / 数据库查询 / 澄清问题
  ↓
生成答案并返回来源
```

## 本次调整范围

### 删除旧逻辑

废弃以下旧方案：

- 业务语义表管理。
- 意图模板表管理。
- 前端“业务语义 / 意图模板 / 知识测试”旧页面逻辑。
- 智能体直接依赖业务语义表、意图模板表做规则匹配的设计。

旧表后续通过新迁移清理：

```text
ai_agent_business_semantic
ai_agent_intent_template
```

旧迁移文件不要回改已经发布过的版本，新增更高版本迁移处理，例如：

```text
V1.6.0_39__ai_agent_document_knowledge_base.sql
```

如果某个环境曾经手动给问题表新增过语义字段，确认没有其他代码使用后再单独清理：

```text
ai_agent_answerable_question
ai_agent_unanswered_question
```

需要删除的旧字段包括：

```text
intent_template_id
answer_entity
query_entity
rewrite_question
semantic_json
deduplicate_by
result_shape
error_type
actual_intent
actual_answer_entity
expected_intent
expected_answer_entity
expected_query_entity
corrected_semantic_json
promote_status
```

删除字段必须谨慎：如果当前数据库已经手动执行过旧 SQL，迁移要使用兼容写法；如果代码和历史迁移中没有这些字段，本次不额外处理。

### 新增新逻辑

公司知识库改为：

```text
文档上传
文档解析
知识切片
知识检索
智能体问答增强
检索日志
```

## 前端页面设计

入口位置保持不变：

```text
左侧菜单：仪表板下面，智能体上面
菜单名称：公司知识库
```

页面不再展示“业务语义”和“意图模板”两个 Tab，改为文档知识库页面。

### 页面结构

```text
公司知识库
维护公司的制度、产品、报价、订单、售后等资料，让智能体回答前先理解公司业务。

[上传文档]

筛选区：
关键词
文件类型
解析状态
启用状态

文档列表：
文件名
文件类型
文件大小
解析状态
切片数量
启用状态
上传人
上传时间
操作

操作：
查看
重新解析
启用/停用
删除
下载原文件
```

### 上传弹窗

字段：

```text
文件：支持 doc/docx/pdf/txt/md，第一阶段优先 docx/pdf
知识分类：产品资料 / 业务规则 / 报价规则 / 订单规则 / 客户规则 / 售后规则 / 其他
备注：可选
启用状态：默认启用
```

上传后状态：

```text
UPLOADED    已上传
PARSING     解析中
PARSED      解析成功
FAILED      解析失败
```

### 文档详情页或抽屉

展示：

```text
基础信息
解析状态
失败原因
文本预览
知识切片列表
关联问答测试
```

### 知识测试

页面保留一个“知识测试”功能，但逻辑改为检索测试。

输入问题后返回：

```text
命中的文档片段
来源文件
页码或段落
匹配分数
智能体基于知识片段生成的理解结果
```

## 后端接口设计

建议新增 Controller：

```text
AiAgentKnowledgeDocumentController
```

接口前缀：

```text
/ai-agent/knowledge/document
```

### 文档管理接口

```text
POST   /page              分页查询文档
POST   /upload            上传文档
GET    /detail/{id}       文档详情
POST   /reparse/{id}      重新解析
POST   /enable/{id}       启用
POST   /disable/{id}      停用
POST   /delete/{id}       删除
GET    /download/{id}     下载原文件
```

### 知识切片接口

```text
POST   /chunk/page        查询文档切片
GET    /chunk/{id}        查看切片详情
```

### 检索测试接口

```text
POST   /search-test       测试问题检索命中文档
```

请求示例：

```json
{
  "question": "品种是什么意思",
  "topK": 8
}
```

响应示例：

```json
{
  "question": "品种是什么意思",
  "rewriteQuestion": "品名是什么意思",
  "matches": [
    {
      "documentId": "doc_001",
      "documentName": "订单业务规则.pdf",
      "chunkId": "chunk_001",
      "pageNo": 3,
      "score": 0.86,
      "content": "公司业务中，品种、品名、物料名称均指订单中的产品名称。"
    }
  ],
  "answerPreview": "根据公司订单业务规则，品种通常等同于品名，也就是订单中的产品名称。"
}
```

## 数据库设计

### 文档主表

表名：

```text
ai_knowledge_document
```

用途：记录上传的知识文档。

字段：

```text
id                  VARCHAR(64)    主键
organization_id     VARCHAR(64)    组织 ID
name                VARCHAR(255)   文档名称
original_name       VARCHAR(255)   原始文件名
file_type           VARCHAR(32)    pdf/doc/docx/txt/md
file_size           BIGINT         文件大小
storage_path        VARCHAR(1024)  文件存储路径
category            VARCHAR(64)    知识分类
parse_status        VARCHAR(32)    UPLOADED/PARSING/PARSED/FAILED
parse_error         TEXT           解析失败原因
chunk_count         INT            切片数量
enabled             TINYINT        是否启用
remark              VARCHAR(1024)  备注
create_user         VARCHAR(64)    创建人
update_user         VARCHAR(64)    更新人
create_time         BIGINT         创建时间
update_time         BIGINT         更新时间
```

索引：

```text
idx_ai_knowledge_document_org_status
idx_ai_knowledge_document_org_category
idx_ai_knowledge_document_create_time
```

### 文档切片表

表名：

```text
ai_knowledge_chunk
```

用途：保存解析后的知识片段。

字段：

```text
id                  VARCHAR(64)    主键
organization_id     VARCHAR(64)    组织 ID
document_id         VARCHAR(64)    文档 ID
chunk_index         INT            切片序号
title               VARCHAR(512)   片段标题
content             MEDIUMTEXT     片段文本
content_hash        VARCHAR(64)    内容哈希
page_no             INT            PDF 页码，可空
section_path        VARCHAR(1024)  Word 标题路径，可空
token_count         INT            估算 token 数
embedding_status    VARCHAR(32)    NONE/PENDING/DONE/FAILED
embedding_id        VARCHAR(128)   向量库 ID，可空
enabled             TINYINT        是否启用
create_time         BIGINT         创建时间
update_time         BIGINT         更新时间
```

索引：

```text
idx_ai_knowledge_chunk_doc
idx_ai_knowledge_chunk_org_enabled
idx_ai_knowledge_chunk_embedding_status
idx_ai_knowledge_chunk_hash
```

### 解析任务表

表名：

```text
ai_knowledge_parse_job
```

用途：记录解析任务，便于前端查看进度和失败原因。

字段：

```text
id                  VARCHAR(64)    主键
organization_id     VARCHAR(64)    组织 ID
document_id         VARCHAR(64)    文档 ID
status              VARCHAR(32)    PENDING/RUNNING/SUCCESS/FAILED
step                VARCHAR(64)    当前步骤
message             VARCHAR(1024)  状态说明
error_stack         MEDIUMTEXT     失败堆栈
start_time          BIGINT         开始时间
finish_time         BIGINT         完成时间
create_time         BIGINT         创建时间
update_time         BIGINT         更新时间
```

### 检索日志表

表名：

```text
ai_knowledge_query_log
```

用途：记录智能体每次检索到了哪些知识，便于排查回答错误。

字段：

```text
id                  VARCHAR(64)    主键
organization_id     VARCHAR(64)    组织 ID
session_id          VARCHAR(64)    会话 ID
message_id          VARCHAR(64)    消息 ID
question            VARCHAR(2048)  原问题
rewrite_question    VARCHAR(2048)  重写后问题
retrieval_mode      VARCHAR(32)    KEYWORD/VECTOR/HYBRID
matched_chunks      JSON           命中的切片 ID、分数、文档名
answer_mode         VARCHAR(32)    DOC/SQL/HYBRID/CLARIFY
create_time         BIGINT         创建时间
```

## 文件存储设计

本地开发环境先使用本地目录存储：

```text
runtime/uploads/knowledge
```

文件路径示例：

```text
runtime/uploads/knowledge/{organizationId}/{documentId}/original.pdf
```

后续如需生产环境对象存储，可以扩展到 BOS、OSS、S3。

## 文档解析方案

### Word 解析

支持：

```text
.docx 第一阶段优先
.doc 后续视情况支持
```

建议实现：

```text
Apache POI 解析 docx 段落和表格
保留标题层级
保留表格文本
```

解析输出统一结构：

```text
title
sectionPath
content
pageNo
metadata
```

### PDF 解析

支持：

```text
文本型 PDF
```

建议实现：

```text
Apache PDFBox 提取文本
按页提取
保留 pageNo
```

注意：

- 扫描件 PDF 可能没有文本，第一阶段可以提示“无法解析扫描件，请上传可复制文本的 PDF”。
- 第二阶段再接 OCR。

### 文本清洗

清洗规则：

```text
去掉连续空白
去掉页眉页脚噪音
合并过短段落
保留编号、标题、表格字段
过滤空内容
```

### 文本切分

切分目标：

```text
每个切片 500-1000 中文字左右
切片之间保留 100-200 字重叠
不要把一个业务规则切断
尽量按标题、段落、页码切分
```

切片示例：

```text
文件：订单业务规则.pdf
页码：3
标题：订单字段解释
内容：公司业务中，品种、品名、物料名称均指订单中的产品名称。
```

## 检索方案

### 第一阶段：关键词检索

为了尽快跑通，可以先做 MySQL 关键词检索。

逻辑：

```text
用户问题
  ↓
提取关键词
  ↓
在 ai_knowledge_chunk.content 中 LIKE 查询
  ↓
按命中次数、文档启用状态、更新时间排序
  ↓
返回 topK 片段
```

优点：

- 开发快。
- 不依赖额外向量库。
- 本地调试简单。

缺点：

- 同义表达能力较弱。
- 召回质量不如向量检索。

### 第二阶段：向量检索

接入 embedding 后使用向量检索。

流程：

```text
文档切片生成 embedding
用户问题生成 embedding
向量库召回 topK
关键词召回补充
LLM 重排
返回最终知识片段
```

推荐检索模式：

```text
HYBRID = 关键词检索 + 向量检索 + 重排
```

向量库选择：

```text
Qdrant
Milvus
pgvector
Elasticsearch dense_vector
```

如果希望减少组件，第一版先不引入向量库，等文档上传和问答链路跑通后再接。

## 智能体接入方案

新增服务：

```text
AiAgentKnowledgeRetrievalService
```

职责：

```text
根据用户问题检索知识片段
生成知识上下文
提供问题重写辅助信息
记录检索日志
```

智能体回答前增加一步：

```text
用户问题
  ↓
检索公司知识库
  ↓
获得相关业务规则、同义词、字段口径
  ↓
问题重写
  ↓
判断是否需要查 CRM 数据库
```

### 文档回答

适用问题：

```text
公司制度是什么？
报价规则是什么？
品种是什么意思？
售后流程怎么走？
某个业务词在公司内部怎么定义？
```

回答方式：

```text
只基于知识库片段回答
带来源文件和页码
如果没有命中知识，明确说明知识库中没有找到
```

### 数据库查询增强

适用问题：

```text
去年下过订单今年没下订单的客户有哪些？
某客户买过哪些品名？
最近一个月某产品的订单金额是多少？
```

处理方式：

```text
先从知识库理解业务词
再进入已有 SQL 查询链路
最后结合查询结果回答
```

例如：

```text
用户问：这个客户买过哪些品种？
知识库命中：品种、品名、物料名称均指订单产品名称
问题重写：查询该客户购买过哪些品名
SQL 查询：订单表产品名称字段
回答：返回产品名称列表
```

### 澄清问题

如果知识库没有命中，或者命中内容冲突，智能体不要强行回答。

示例：

```text
我在公司知识库里没有找到“品类等级”的定义。你是想按产品分类、订单品名，还是客户等级查询？
```

## Prompt 设计

智能体回答时注入知识上下文：

```text
你是 CordysCRM 的企业智能体。
回答前必须优先参考公司知识库片段。
如果知识库片段与通用知识冲突，以公司知识库为准。
如果知识库没有提供依据，不要编造公司规则。

用户问题：
{question}

公司知识库片段：
{retrieved_chunks}

请判断：
1. 这个问题是否可以直接由文档回答。
2. 是否需要查询 CRM 数据。
3. 是否需要先重写用户问题。
4. 如果信息不足，应该反问什么。
```

## 旧数据和迁移处理

### 迁移原则

不要修改已经执行过或可能已经发布的旧迁移文件。

新增更高版本迁移，例如：

```text
V1.6.0_39__ai_agent_document_knowledge_base.sql
```

原因：

当前数据库已经存在比 `1.6.0.38` 更高的版本，例如：

```text
1.6.0.38.1
```

如果再使用：

```text
V1.6.0_38__xxx.sql
```

Flyway 会认为这是低版本迁移，不会自动执行。

### 新迁移内容

新迁移需要做三件事：

1. 创建文档知识库新表。
2. 删除旧业务语义和意图模板表。
3. 可选删除旧问题表扩展字段。

建议 SQL：

```sql
DROP TABLE IF EXISTS ai_agent_business_semantic;
DROP TABLE IF EXISTS ai_agent_intent_template;

CREATE TABLE IF NOT EXISTS ai_knowledge_document (...);
CREATE TABLE IF NOT EXISTS ai_knowledge_chunk (...);
CREATE TABLE IF NOT EXISTS ai_knowledge_parse_job (...);
CREATE TABLE IF NOT EXISTS ai_knowledge_query_log (...);
```

旧字段删除要确认 MySQL 版本是否支持：

```sql
ALTER TABLE table_name DROP COLUMN IF EXISTS column_name;
```

如果不支持，需要通过 `information_schema.columns` 做兼容 SQL，避免不同环境启动失败。

## 后端开发任务

### 第一阶段：基础文档库

交付：

```text
新数据库表
文档上传接口
文档分页接口
文档删除接口
文档启用/停用接口
本地文件存储
```

验收：

```text
前端可以上传 PDF/docx
数据库有文档记录
文件保存到 runtime/uploads/knowledge
可以删除、启用、停用
```

### 第二阶段：解析和切片

交付：

```text
PDF 文本解析
docx 文本解析
文档解析任务
文本清洗
知识切片入库
解析失败提示
```

验收：

```text
上传文档后状态从 UPLOADED 变为 PARSED
ai_knowledge_chunk 有切片数据
PDF 切片保留页码
docx 切片保留标题路径
解析失败能看到失败原因
```

### 第三阶段：检索测试

交付：

```text
知识检索接口
知识测试页面
检索日志
关键词召回
LLM 回答预览
```

验收：

```text
输入“品种是什么意思”
能命中文档里“品种=品名”的片段
页面显示来源文件和片段内容
```

### 第四阶段：智能体接入

交付：

```text
AI Agent 聊天前检索知识库
问题重写使用知识片段
文档问题直接回答
数据库问题增强 SQL 理解
回答中显示知识来源
```

验收：

```text
用户问同义词问题时，智能体能正确理解
用户问制度规则时，智能体基于文档回答
用户问数据问题时，智能体能用知识库解释业务词后再查库
```

### 第五阶段：向量检索增强

交付：

```text
Embedding 生成
向量库接入
向量召回
混合检索
重排
```

验收：

```text
不同表达但含义相同的问题，能命中相同知识片段
比关键词检索召回更稳定
```

## 前端开发任务

需要删除旧页面逻辑：

```text
业务语义 Tab
意图模板 Tab
旧知识测试逻辑
新增语义弹窗
新增意图模板弹窗
```

新增页面能力：

```text
文档列表
上传文档弹窗
解析状态标签
文档详情抽屉
知识切片列表
重新解析按钮
启用/停用按钮
删除按钮
知识检索测试
```

页面文案：

```text
公司知识库
上传公司的业务资料、规则制度、产品说明和常见问题，让智能体回答前优先参考公司知识。
```

## 权限和安全

权限建议：

```text
管理员：上传、删除、重新解析、启用/停用
普通用户：查看和检索
智能体：只读取启用状态的知识
```

安全要求：

```text
限制文件大小
限制文件类型
文件名去危险字符
禁止直接暴露真实磁盘路径
删除文档时同步删除切片和索引
不同 organization_id 数据隔离
```

建议限制：

```text
单文件最大 50MB
第一阶段支持 pdf/docx/txt/md
不支持可执行文件
```

## 配置项

新增配置：

```properties
crm.ai-agent.knowledge.enabled=true
crm.ai-agent.knowledge.storage-path=E:/CordysCRM-1.6.0/runtime/uploads/knowledge
crm.ai-agent.knowledge.max-file-size-mb=50
crm.ai-agent.knowledge.allowed-types=pdf,docx,txt,md
crm.ai-agent.knowledge.chunk-size=800
crm.ai-agent.knowledge.chunk-overlap=150
crm.ai-agent.knowledge.search-top-k=8
crm.ai-agent.knowledge.retrieval-mode=KEYWORD
```

第二阶段向量配置：

```properties
crm.ai-agent.knowledge.embedding.enabled=false
crm.ai-agent.knowledge.embedding.provider=qwen
crm.ai-agent.knowledge.vector-store=qdrant
crm.ai-agent.knowledge.vector-collection=cordys_knowledge
```

## 测试用例

### 上传测试

```text
上传 docx 成功
上传 pdf 成功
上传不支持文件失败
超过大小限制失败
删除文档成功
停用文档后检索不到
```

### 解析测试

```text
docx 段落可以解析
docx 表格可以解析
PDF 文本可以按页解析
扫描 PDF 提示无法解析
空文档提示解析失败
```

### 检索测试

```text
问题命中文档标题
问题命中文档正文
问题命中同义词说明
停用文档不参与检索
不同组织不能检索彼此文档
```

### 智能体测试

```text
问“品种是什么”能根据文档解释为品名
问公司报价规则能引用报价文档回答
问客户订单数据时能先理解业务词再查询数据库
知识库无内容时不编造答案
命中多个冲突规则时能提示不确定或反问
```

## 关键验收标准

第一版完成后，至少满足：

1. 公司知识库页面不再出现业务语义和意图模板。
2. 用户可以上传 docx/pdf 文件。
3. 上传后能解析出文本并生成知识切片。
4. 用户可以输入问题测试命中文档片段。
5. 智能体正式聊天时会先检索公司知识库。
6. 智能体能用文档知识理解“品种=品名”这类业务表达。
7. 数据库迁移版本高于当前线上版本，重新打包启动后能自动执行。

## 推荐实施顺序

```text
1. 替换前端公司知识库页面为文档库页面
2. 新增数据库迁移 V1.6.0_39
3. 新增文档上传和管理接口
4. 新增 PDF/docx 解析和切片
5. 新增知识检索测试
6. 接入 AI Agent 聊天流程
7. 增加向量检索
8. 增加 OCR 和更复杂的文档格式
```

## 注意事项

1. 不要再使用 `V1.6.0_38` 作为新迁移版本，因为当前数据库已经有 `1.6.0.38.1`。
2. 新迁移建议从 `V1.6.0_39` 开始，确保 Flyway 重新打包启动后会自动执行。
3. 旧业务语义和意图模板表可以删除，但需要先确认旧页面和旧服务代码同步移除。
4. 文档知识库第一版可以先用关键词检索，等链路跑通后再接 embedding 和向量库。
5. 智能体不能因为知识库命中为空就编造公司规则，必须明确说没有找到依据或发起澄清。
