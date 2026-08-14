-- Support idempotent shipment notification tasks which may wait for manager assignment.
SET @assignee_nullable := (
    SELECT IS_NULLABLE
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'crm_task'
      AND COLUMN_NAME = 'assignee_id'
);
SET @ddl_sql := IF(@assignee_nullable = 'NO',
                   'ALTER TABLE `crm_task` MODIFY COLUMN `assignee_id` VARCHAR(32) NULL COMMENT ''contact specialist ID; NULL means pending manager assignment''',
                   'SELECT ''crm_task.assignee_id is already nullable''');
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

SET @business_key_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'crm_task'
      AND COLUMN_NAME = 'business_key'
);
SET @ddl_sql := IF(@business_key_exists = 0,
                   'ALTER TABLE `crm_task` ADD COLUMN `business_key` VARCHAR(255) NULL COMMENT ''unique key for automatically generated business events'' AFTER `source`',
                   'SELECT ''crm_task.business_key already exists''');
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

SET @business_key_index_exists := (
    SELECT COUNT(*)
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'crm_task'
      AND INDEX_NAME = 'uk_crm_task_org_business_key'
);
SET @ddl_sql := IF(@business_key_index_exists = 0,
                   'ALTER TABLE `crm_task` ADD UNIQUE KEY `uk_crm_task_org_business_key` (`organization_id`, `business_key`)',
                   'SELECT ''uk_crm_task_org_business_key already exists''');
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;
