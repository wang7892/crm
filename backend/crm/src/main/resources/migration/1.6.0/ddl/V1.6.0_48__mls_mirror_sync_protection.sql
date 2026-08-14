-- State required by the protected MLS mirror synchronization.
-- previous_source_id allows mappings to survive source id reassignment after
-- contract_info/order_info are cleared and imported again. missing_count is
-- advanced only after a complete, deletion-eligible synchronization.

SET @column_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mls_sync_mapping'
      AND COLUMN_NAME = 'previous_source_id'
);
SET @ddl_sql := IF(@column_exists = 0,
                   'ALTER TABLE `mls_sync_mapping` ADD COLUMN `previous_source_id` VARCHAR(191) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT ''source id before the current mirror pass'' AFTER `source_id`',
                   'SELECT ''mls_sync_mapping.previous_source_id already exists''');
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

SET @column_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mls_sync_mapping'
      AND COLUMN_NAME = 'missing_count'
);
SET @ddl_sql := IF(@column_exists = 0,
                   'ALTER TABLE `mls_sync_mapping` ADD COLUMN `missing_count` INT NOT NULL DEFAULT 0 COMMENT ''consecutive complete mirror passes where the source row was absent'' AFTER `status`',
                   'SELECT ''mls_sync_mapping.missing_count already exists''');
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

SET @column_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mls_sync_run'
      AND COLUMN_NAME = 'contract_deleted_count'
);
SET @ddl_sql := IF(@column_exists = 0,
                   'ALTER TABLE `mls_sync_run` ADD COLUMN `contract_deleted_count` BIGINT NOT NULL DEFAULT 0 COMMENT ''MLS contracts deleted by mirror cleanup'' AFTER `contract_failed_count`',
                   'SELECT ''mls_sync_run.contract_deleted_count already exists''');
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

SET @column_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mls_sync_run'
      AND COLUMN_NAME = 'contract_conflict_count'
);
SET @ddl_sql := IF(@column_exists = 0,
                   'ALTER TABLE `mls_sync_run` ADD COLUMN `contract_conflict_count` BIGINT NOT NULL DEFAULT 0 COMMENT ''missing MLS contracts retained because CRM data is related'' AFTER `contract_deleted_count`',
                   'SELECT ''mls_sync_run.contract_conflict_count already exists''');
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

SET @column_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mls_sync_run'
      AND COLUMN_NAME = 'order_deleted_count'
);
SET @ddl_sql := IF(@column_exists = 0,
                   'ALTER TABLE `mls_sync_run` ADD COLUMN `order_deleted_count` BIGINT NOT NULL DEFAULT 0 COMMENT ''MLS orders deleted by mirror cleanup'' AFTER `order_failed_count`',
                   'SELECT ''mls_sync_run.order_deleted_count already exists''');
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

SET @column_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mls_sync_run'
      AND COLUMN_NAME = 'mirror_protection_triggered'
);
SET @ddl_sql := IF(@column_exists = 0,
                   'ALTER TABLE `mls_sync_run` ADD COLUMN `mirror_protection_triggered` BIT(1) NOT NULL DEFAULT 0 COMMENT ''deletion or mirror processing was blocked by a safety guard'' AFTER `error_summary`',
                   'SELECT ''mls_sync_run.mirror_protection_triggered already exists''');
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

UPDATE `mls_sync_mapping`
SET `previous_source_id` = `source_id`
WHERE `previous_source_id` IS NULL;

SET @idx_exists := (
    SELECT COUNT(*)
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mls_sync_mapping'
      AND INDEX_NAME = 'idx_mls_sync_mapping_previous_source'
);
SET @ddl_sql := IF(@idx_exists = 0,
                   'CREATE INDEX `idx_mls_sync_mapping_previous_source` ON `mls_sync_mapping` (`organization_id`, `source_table`, `previous_source_id`)',
                   'SELECT ''idx_mls_sync_mapping_previous_source already exists''');
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

SET @idx_exists := (
    SELECT COUNT(*)
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mls_sync_mapping'
      AND INDEX_NAME = 'idx_mls_sync_mapping_missing'
);
SET @ddl_sql := IF(@idx_exists = 0,
                   'CREATE INDEX `idx_mls_sync_mapping_missing` ON `mls_sync_mapping` (`organization_id`, `source_table`, `status`, `missing_count`)',
                   'SELECT ''idx_mls_sync_mapping_missing already exists''');
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;
