SET @sql := IF(
  (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wecom_ingestion_message' AND COLUMN_NAME = 'status') = 0,
  'ALTER TABLE wecom_ingestion_message ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT ''PENDING'' COMMENT ''PENDING=待 CRM 消费；SUCCESS=已处理；FAIL=失败可重试或人工处理'' AFTER extra_json',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wecom_ingestion_message' AND COLUMN_NAME = 'follow_record_id') = 0,
  'ALTER TABLE wecom_ingestion_message ADD COLUMN follow_record_id VARCHAR(50) DEFAULT NULL COMMENT ''CRM 消费后生成的跟进记录 ID'' AFTER status',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wecom_ingestion_message' AND COLUMN_NAME = 'error_message') = 0,
  'ALTER TABLE wecom_ingestion_message ADD COLUMN error_message VARCHAR(1024) DEFAULT NULL COMMENT ''失败原因'' AFTER follow_record_id',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wecom_ingestion_message' AND COLUMN_NAME = 'update_user') = 0,
  'ALTER TABLE wecom_ingestion_message ADD COLUMN update_user VARCHAR(50) DEFAULT NULL COMMENT ''最后修改人'' AFTER create_time',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wecom_ingestion_message' AND COLUMN_NAME = 'update_time') = 0,
  'ALTER TABLE wecom_ingestion_message ADD COLUMN update_time BIGINT DEFAULT NULL COMMENT ''最后修改时间（毫秒时间戳）'' AFTER update_user',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(1) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wecom_ingestion_message' AND INDEX_NAME = 'idx_session_status') = 0,
  'ALTER TABLE wecom_ingestion_message ADD KEY idx_session_status (session_day_id, status)',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
