CREATE TABLE IF NOT EXISTS email_webhook_event
(
    `id`                    VARCHAR(32)  NOT NULL COMMENT 'id',
    `source_mailbox`        VARCHAR(255) NOT NULL COMMENT '被监控发件邮箱',
    `message_id`            VARCHAR(255) NOT NULL COMMENT '邮件message-id',
    `thread_id`             VARCHAR(255) COMMENT '邮件线程id',
    `from_address`          VARCHAR(255) COMMENT '发件地址',
    `matched_target_mailbox` VARCHAR(255) COMMENT '命中的目标邮箱',
    `follow_record_id`      VARCHAR(32)  COMMENT '创建的跟进记录ID',
    `status`                VARCHAR(20)  NOT NULL COMMENT '处理状态',
    `error_message`         VARCHAR(1000) COMMENT '失败原因',
    `organization_id`       VARCHAR(32)  NOT NULL COMMENT '组织id',
    `create_time`           BIGINT       NOT NULL COMMENT '创建时间',
    `update_time`           BIGINT       NOT NULL COMMENT '更新时间',
    `create_user`           VARCHAR(32) COMMENT '创建人',
    `update_user`           VARCHAR(32) COMMENT '更新人',
    PRIMARY KEY (id)
) COMMENT='邮件Webhook事件记录'
ENGINE=InnoDB
DEFAULT CHARSET=utf8mb4
COLLATE=utf8mb4_general_ci;

SET @idx_uk_org_source_message_exists := (
    SELECT COUNT(1)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'email_webhook_event'
      AND index_name = 'uk_org_source_message'
);
SET @ddl_uk_org_source_message := IF(
    @idx_uk_org_source_message_exists = 0,
    'CREATE UNIQUE INDEX uk_org_source_message ON email_webhook_event (organization_id ASC, source_mailbox ASC, message_id ASC)',
    'SELECT 1'
);
PREPARE stmt_uk_org_source_message FROM @ddl_uk_org_source_message;
EXECUTE stmt_uk_org_source_message;
DEALLOCATE PREPARE stmt_uk_org_source_message;

SET @idx_status_exists := (
    SELECT COUNT(1)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'email_webhook_event'
      AND index_name = 'idx_status'
);
SET @ddl_idx_status := IF(
    @idx_status_exists = 0,
    'CREATE INDEX idx_status ON email_webhook_event (status ASC)',
    'SELECT 1'
);
PREPARE stmt_idx_status FROM @ddl_idx_status;
EXECUTE stmt_idx_status;
DEALLOCATE PREPARE stmt_idx_status;

