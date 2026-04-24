CREATE TABLE IF NOT EXISTS email_webhook_attachment
(
    `id`              VARCHAR(32)   NOT NULL COMMENT 'id',
    `event_id`        VARCHAR(32)   NOT NULL COMMENT '关联 email_webhook_event.id',
    `file_name`       VARCHAR(512)  NOT NULL COMMENT '文件名',
    `content_type`    VARCHAR(255)  COMMENT '内容类型',
    `size_bytes`      BIGINT        NOT NULL COMMENT '字节大小',
    `download_url`    VARCHAR(2000) COMMENT '下载链接',
    `organization_id` VARCHAR(32)   NOT NULL COMMENT '组织id',
    `create_time`     BIGINT        NOT NULL COMMENT '创建时间',
    `update_time`     BIGINT        NOT NULL COMMENT '更新时间',
    `create_user`     VARCHAR(32)   COMMENT '创建人',
    `update_user`     VARCHAR(32)   COMMENT '更新人',
    PRIMARY KEY (id)
) COMMENT='邮件Webhook附件记录'
ENGINE=InnoDB
DEFAULT CHARSET=utf8mb4
COLLATE=utf8mb4_general_ci;

SET @idx_attachment_event_id_exists := (
    SELECT COUNT(1)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'email_webhook_attachment'
      AND index_name = 'idx_email_webhook_attachment_event_id'
);
SET @ddl_attachment_event_id := IF(
    @idx_attachment_event_id_exists = 0,
    'CREATE INDEX idx_email_webhook_attachment_event_id ON email_webhook_attachment (event_id ASC)',
    'SELECT 1'
);
PREPARE stmt_attachment_event_id FROM @ddl_attachment_event_id;
EXECUTE stmt_attachment_event_id;
DEALLOCATE PREPARE stmt_attachment_event_id;

SET @idx_attachment_org_id_exists := (
    SELECT COUNT(1)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'email_webhook_attachment'
      AND index_name = 'idx_email_webhook_attachment_org_id'
);
SET @ddl_attachment_org_id := IF(
    @idx_attachment_org_id_exists = 0,
    'CREATE INDEX idx_email_webhook_attachment_org_id ON email_webhook_attachment (organization_id ASC)',
    'SELECT 1'
);
PREPARE stmt_attachment_org_id FROM @ddl_attachment_org_id;
EXECUTE stmt_attachment_org_id;
DEALLOCATE PREPARE stmt_attachment_org_id;

