ALTER TABLE ai_knowledge_document
  ADD COLUMN active_version_id VARCHAR(64) NULL COMMENT '当前生效的知识正文版本 ID' AFTER remark,
  ADD COLUMN active_version INT NOT NULL DEFAULT 0 COMMENT '当前生效的知识正文版本号' AFTER active_version_id,
  ADD COLUMN latest_version INT NOT NULL DEFAULT 0 COMMENT '最新知识正文版本号' AFTER active_version;

ALTER TABLE ai_knowledge_parse_job
  ADD COLUMN document_version_id VARCHAR(64) NULL COMMENT '本次解析使用的知识正文版本 ID' AFTER document_id,
  ADD INDEX idx_ai_knowledge_parse_job_version (document_version_id);

CREATE TABLE IF NOT EXISTS ai_knowledge_document_version (
  id VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '主键',
  organization_id VARCHAR(64) NOT NULL COMMENT '组织 ID',
  document_id VARCHAR(64) NOT NULL COMMENT '文档 ID',
  version INT NOT NULL COMMENT '版本号',
  name VARCHAR(255) NOT NULL COMMENT '该版本的知识文档名称',
  remark VARCHAR(1024) NULL COMMENT '该版本的备注',
  content LONGTEXT NOT NULL COMMENT '统一 Markdown 知识正文',
  status VARCHAR(32) NOT NULL COMMENT '状态：PENDING/PARSING/ACTIVE/ARCHIVED/FAILED',
  source VARCHAR(32) NOT NULL COMMENT '来源：ORIGINAL/EDIT/ROLLBACK',
  source_version INT NULL COMMENT '回退所基于的历史版本号',
  error_message VARCHAR(2000) NULL COMMENT '解析失败原因',
  create_user VARCHAR(64) NULL COMMENT '创建人',
  update_user VARCHAR(64) NULL COMMENT '更新人',
  create_time BIGINT NOT NULL COMMENT '创建时间',
  update_time BIGINT NOT NULL COMMENT '更新时间',
  UNIQUE KEY uk_ai_knowledge_document_version (organization_id, document_id, version),
  INDEX idx_ai_knowledge_document_version_doc_status (document_id, status, version),
  INDEX idx_ai_knowledge_document_version_org_time (organization_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='AI Agent 公司知识库可编辑正文版本表';
