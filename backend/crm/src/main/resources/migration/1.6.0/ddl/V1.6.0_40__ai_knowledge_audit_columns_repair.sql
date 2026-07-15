SET @sql := IF(
  (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_knowledge_document' AND COLUMN_NAME = 'create_user') = 0,
  'ALTER TABLE ai_knowledge_document ADD COLUMN create_user VARCHAR(64) DEFAULT NULL COMMENT ''创建人'' AFTER remark',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_knowledge_document' AND COLUMN_NAME = 'update_user') = 0,
  'ALTER TABLE ai_knowledge_document ADD COLUMN update_user VARCHAR(64) DEFAULT NULL COMMENT ''更新人'' AFTER create_user',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_knowledge_document' AND COLUMN_NAME = 'create_time') = 0,
  'ALTER TABLE ai_knowledge_document ADD COLUMN create_time BIGINT DEFAULT NULL COMMENT ''创建时间'' AFTER update_user',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_knowledge_document' AND COLUMN_NAME = 'update_time') = 0,
  'ALTER TABLE ai_knowledge_document ADD COLUMN update_time BIGINT DEFAULT NULL COMMENT ''更新时间'' AFTER create_time',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_knowledge_chunk' AND COLUMN_NAME = 'create_user') = 0,
  'ALTER TABLE ai_knowledge_chunk ADD COLUMN create_user VARCHAR(64) DEFAULT NULL COMMENT ''创建人'' AFTER enabled',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_knowledge_chunk' AND COLUMN_NAME = 'update_user') = 0,
  'ALTER TABLE ai_knowledge_chunk ADD COLUMN update_user VARCHAR(64) DEFAULT NULL COMMENT ''更新人'' AFTER create_user',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_knowledge_chunk' AND COLUMN_NAME = 'create_time') = 0,
  'ALTER TABLE ai_knowledge_chunk ADD COLUMN create_time BIGINT DEFAULT NULL COMMENT ''创建时间'' AFTER update_user',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_knowledge_chunk' AND COLUMN_NAME = 'update_time') = 0,
  'ALTER TABLE ai_knowledge_chunk ADD COLUMN update_time BIGINT DEFAULT NULL COMMENT ''更新时间'' AFTER create_time',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_knowledge_parse_job' AND COLUMN_NAME = 'create_user') = 0,
  'ALTER TABLE ai_knowledge_parse_job ADD COLUMN create_user VARCHAR(64) DEFAULT NULL COMMENT ''创建人'' AFTER finish_time',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_knowledge_parse_job' AND COLUMN_NAME = 'update_user') = 0,
  'ALTER TABLE ai_knowledge_parse_job ADD COLUMN update_user VARCHAR(64) DEFAULT NULL COMMENT ''更新人'' AFTER create_user',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_knowledge_parse_job' AND COLUMN_NAME = 'create_time') = 0,
  'ALTER TABLE ai_knowledge_parse_job ADD COLUMN create_time BIGINT DEFAULT NULL COMMENT ''创建时间'' AFTER update_user',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_knowledge_parse_job' AND COLUMN_NAME = 'update_time') = 0,
  'ALTER TABLE ai_knowledge_parse_job ADD COLUMN update_time BIGINT DEFAULT NULL COMMENT ''更新时间'' AFTER create_time',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_knowledge_query_log' AND COLUMN_NAME = 'create_user') = 0,
  'ALTER TABLE ai_knowledge_query_log ADD COLUMN create_user VARCHAR(64) DEFAULT NULL COMMENT ''创建人'' AFTER answer_mode',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_knowledge_query_log' AND COLUMN_NAME = 'update_user') = 0,
  'ALTER TABLE ai_knowledge_query_log ADD COLUMN update_user VARCHAR(64) DEFAULT NULL COMMENT ''更新人'' AFTER create_user',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_knowledge_query_log' AND COLUMN_NAME = 'create_time') = 0,
  'ALTER TABLE ai_knowledge_query_log ADD COLUMN create_time BIGINT DEFAULT NULL COMMENT ''创建时间'' AFTER update_user',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_knowledge_query_log' AND COLUMN_NAME = 'update_time') = 0,
  'ALTER TABLE ai_knowledge_query_log ADD COLUMN update_time BIGINT DEFAULT NULL COMMENT ''更新时间'' AFTER create_time',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
