CREATE TABLE IF NOT EXISTS mail_event (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
  organization_id VARCHAR(64) NOT NULL COMMENT '组织ID',
  message_id VARCHAR(255) NOT NULL COMMENT '邮件唯一标识(Message-ID)',
  thread_id VARCHAR(255) DEFAULT '' COMMENT '邮件会话ID',
  from_address VARCHAR(255) NOT NULL COMMENT '发件人邮箱',
  to_addresses TEXT COMMENT '收件人邮箱列表',
  cc_addresses TEXT COMMENT '抄送邮箱列表',
  subject VARCHAR(500) DEFAULT '' COMMENT '邮件主题',
  content_text LONGTEXT COMMENT '邮件正文文本',
  send_time DATETIME NOT NULL COMMENT '邮件发送时间',
  process_status VARCHAR(32) NOT NULL DEFAULT 'NEW' COMMENT '处理状态(NEW/PROCESSED/FAILED/DEAD)',
  follow_record_id VARCHAR(128) DEFAULT NULL COMMENT 'CRM回执记录ID',
  error_message VARCHAR(1000) DEFAULT NULL COMMENT '处理失败错误信息',
  retry_count INT NOT NULL DEFAULT 0 COMMENT '重试次数',
  next_retry_at DATETIME DEFAULT NULL COMMENT '下一次重试时间',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  UNIQUE KEY uk_mail_dedupe (organization_id, message_id),
  INDEX idx_status_retry (process_status, next_retry_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='邮件事件主表';

CREATE TABLE IF NOT EXISTS mail_attachment (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
  event_id BIGINT NOT NULL COMMENT '关联mail_event.id',
  file_name VARCHAR(500) NOT NULL COMMENT '附件文件名',
  content_type VARCHAR(200) DEFAULT 'application/octet-stream' COMMENT '附件MIME类型',
  size_bytes BIGINT NOT NULL DEFAULT 0 COMMENT '附件字节大小',
  saved_path VARCHAR(1000) NOT NULL COMMENT '附件保存路径',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  INDEX idx_event_id (event_id),
  CONSTRAINT fk_attachment_event FOREIGN KEY (event_id) REFERENCES mail_event(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='邮件附件表';
