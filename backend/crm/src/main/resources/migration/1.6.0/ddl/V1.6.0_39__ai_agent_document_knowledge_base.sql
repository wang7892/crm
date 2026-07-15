DROP TABLE IF EXISTS ai_agent_business_semantic;
DROP TABLE IF EXISTS ai_agent_intent_template;

CREATE TABLE IF NOT EXISTS ai_knowledge_document (
  id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '主键',
  organization_id VARCHAR(64) NOT NULL COMMENT '组织 ID',
  name VARCHAR(255) NOT NULL COMMENT '文档名称',
  original_name VARCHAR(255) NOT NULL COMMENT '原始文件名',
  file_type VARCHAR(32) NOT NULL COMMENT '文件类型：pdf/doc/docx/txt/md',
  file_size BIGINT NOT NULL DEFAULT 0 COMMENT '文件大小，单位字节',
  storage_path VARCHAR(1024) NOT NULL COMMENT '文件存储路径',
  category VARCHAR(64) NULL COMMENT '知识分类',
  parse_status VARCHAR(32) NOT NULL DEFAULT 'UPLOADED' COMMENT '解析状态：UPLOADED/PARSING/PARSED/FAILED',
  parse_error TEXT NULL COMMENT '解析失败原因',
  chunk_count INT NOT NULL DEFAULT 0 COMMENT '知识切片数量',
  enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用：1 启用，0 停用',
  remark VARCHAR(1024) NULL COMMENT '备注',
  create_user VARCHAR(64) NULL COMMENT '创建人',
  update_user VARCHAR(64) NULL COMMENT '更新人',
  create_time BIGINT NOT NULL COMMENT '创建时间',
  update_time BIGINT NOT NULL COMMENT '更新时间',
  INDEX idx_ai_knowledge_document_org_status (organization_id, parse_status, enabled),
  INDEX idx_ai_knowledge_document_org_category (organization_id, category),
  INDEX idx_ai_knowledge_document_create_time (organization_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='AI Agent 公司知识库文档表';

CREATE TABLE IF NOT EXISTS ai_knowledge_chunk (
  id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '主键',
  organization_id VARCHAR(64) NOT NULL COMMENT '组织 ID',
  document_id VARCHAR(64) NOT NULL COMMENT '文档 ID',
  chunk_index INT NOT NULL COMMENT '切片序号',
  title VARCHAR(512) NULL COMMENT '片段标题',
  content MEDIUMTEXT NOT NULL COMMENT '片段文本',
  content_hash VARCHAR(64) NOT NULL COMMENT '内容哈希',
  page_no INT NULL COMMENT 'PDF 页码',
  section_path VARCHAR(1024) NULL COMMENT 'Word 标题路径',
  token_count INT NOT NULL DEFAULT 0 COMMENT '估算 token 数',
  embedding_status VARCHAR(32) NOT NULL DEFAULT 'NONE' COMMENT '向量状态：NONE/PENDING/DONE/FAILED',
  embedding_id VARCHAR(128) NULL COMMENT '向量库 ID',
  enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用：1 启用，0 停用',
  create_user VARCHAR(64) NULL COMMENT '创建人',
  update_user VARCHAR(64) NULL COMMENT '更新人',
  create_time BIGINT NOT NULL COMMENT '创建时间',
  update_time BIGINT NOT NULL COMMENT '更新时间',
  INDEX idx_ai_knowledge_chunk_doc (document_id, chunk_index),
  INDEX idx_ai_knowledge_chunk_org_enabled (organization_id, enabled),
  INDEX idx_ai_knowledge_chunk_embedding_status (embedding_status),
  INDEX idx_ai_knowledge_chunk_hash (organization_id, content_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='AI Agent 公司知识库切片表';

CREATE TABLE IF NOT EXISTS ai_knowledge_parse_job (
  id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '主键',
  organization_id VARCHAR(64) NOT NULL COMMENT '组织 ID',
  document_id VARCHAR(64) NOT NULL COMMENT '文档 ID',
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '任务状态：PENDING/RUNNING/SUCCESS/FAILED',
  step VARCHAR(64) NULL COMMENT '当前步骤',
  message VARCHAR(1024) NULL COMMENT '状态说明',
  error_stack MEDIUMTEXT NULL COMMENT '失败堆栈',
  start_time BIGINT NULL COMMENT '开始时间',
  finish_time BIGINT NULL COMMENT '完成时间',
  create_user VARCHAR(64) NULL COMMENT '创建人',
  update_user VARCHAR(64) NULL COMMENT '更新人',
  create_time BIGINT NOT NULL COMMENT '创建时间',
  update_time BIGINT NOT NULL COMMENT '更新时间',
  INDEX idx_ai_knowledge_parse_job_doc (document_id, create_time),
  INDEX idx_ai_knowledge_parse_job_org_status (organization_id, status),
  INDEX idx_ai_knowledge_parse_job_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='AI Agent 公司知识库解析任务表';

CREATE TABLE IF NOT EXISTS ai_knowledge_query_log (
  id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '主键',
  organization_id VARCHAR(64) NOT NULL COMMENT '组织 ID',
  session_id VARCHAR(64) NULL COMMENT '会话 ID',
  message_id VARCHAR(64) NULL COMMENT '消息 ID',
  question VARCHAR(2048) NOT NULL COMMENT '原问题',
  rewrite_question VARCHAR(2048) NULL COMMENT '重写后问题',
  retrieval_mode VARCHAR(32) NOT NULL DEFAULT 'KEYWORD' COMMENT '检索模式：KEYWORD/VECTOR/HYBRID',
  matched_chunks JSON NULL COMMENT '命中的切片 ID、分数、文档名',
  answer_mode VARCHAR(32) NULL COMMENT '回答模式：DOC/SQL/HYBRID/CLARIFY',
  create_user VARCHAR(64) NULL COMMENT '创建人',
  update_user VARCHAR(64) NULL COMMENT '更新人',
  create_time BIGINT NOT NULL COMMENT '创建时间',
  INDEX idx_ai_knowledge_query_log_org_time (organization_id, create_time),
  INDEX idx_ai_knowledge_query_log_session (session_id, message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='AI Agent 公司知识库检索日志表';
