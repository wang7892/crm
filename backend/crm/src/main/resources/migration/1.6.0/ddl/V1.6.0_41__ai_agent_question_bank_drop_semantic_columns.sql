SET @sql := IF(
  (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_agent_answerable_question' AND COLUMN_NAME = 'intent_template_id') > 0,
  'ALTER TABLE ai_agent_answerable_question DROP COLUMN intent_template_id',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_agent_answerable_question' AND COLUMN_NAME = 'answer_entity') > 0,
  'ALTER TABLE ai_agent_answerable_question DROP COLUMN answer_entity',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_agent_answerable_question' AND COLUMN_NAME = 'query_entity') > 0,
  'ALTER TABLE ai_agent_answerable_question DROP COLUMN query_entity',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_agent_answerable_question' AND COLUMN_NAME = 'rewrite_question') > 0,
  'ALTER TABLE ai_agent_answerable_question DROP COLUMN rewrite_question',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_agent_answerable_question' AND COLUMN_NAME = 'semantic_json') > 0,
  'ALTER TABLE ai_agent_answerable_question DROP COLUMN semantic_json',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_agent_answerable_question' AND COLUMN_NAME = 'deduplicate_by') > 0,
  'ALTER TABLE ai_agent_answerable_question DROP COLUMN deduplicate_by',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_agent_answerable_question' AND COLUMN_NAME = 'result_shape') > 0,
  'ALTER TABLE ai_agent_answerable_question DROP COLUMN result_shape',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_agent_unanswered_question' AND COLUMN_NAME = 'error_type') > 0,
  'ALTER TABLE ai_agent_unanswered_question DROP COLUMN error_type',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_agent_unanswered_question' AND COLUMN_NAME = 'actual_intent') > 0,
  'ALTER TABLE ai_agent_unanswered_question DROP COLUMN actual_intent',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_agent_unanswered_question' AND COLUMN_NAME = 'actual_answer_entity') > 0,
  'ALTER TABLE ai_agent_unanswered_question DROP COLUMN actual_answer_entity',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_agent_unanswered_question' AND COLUMN_NAME = 'expected_intent') > 0,
  'ALTER TABLE ai_agent_unanswered_question DROP COLUMN expected_intent',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_agent_unanswered_question' AND COLUMN_NAME = 'expected_answer_entity') > 0,
  'ALTER TABLE ai_agent_unanswered_question DROP COLUMN expected_answer_entity',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_agent_unanswered_question' AND COLUMN_NAME = 'expected_query_entity') > 0,
  'ALTER TABLE ai_agent_unanswered_question DROP COLUMN expected_query_entity',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_agent_unanswered_question' AND COLUMN_NAME = 'corrected_semantic_json') > 0,
  'ALTER TABLE ai_agent_unanswered_question DROP COLUMN corrected_semantic_json',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := IF(
  (SELECT COUNT(1) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_agent_unanswered_question' AND COLUMN_NAME = 'promote_status') > 0,
  'ALTER TABLE ai_agent_unanswered_question DROP COLUMN promote_status',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
