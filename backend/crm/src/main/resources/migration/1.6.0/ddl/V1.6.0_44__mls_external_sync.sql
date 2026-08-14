-- MLS external CRM synchronization metadata.
-- The source database is read-only; these tables keep the local mapping,
-- checkpoints, run statistics and row-level errors needed for idempotent sync.

-- External creator/updater values can be longer than a local user id.  Keep
-- the existing NOT NULL contract while widening the columns to 50 characters.
SET @ddl_sql := (
    SELECT IF(COUNT(*) = 0,
              'SELECT ''customer.create_user not found''',
              'ALTER TABLE `customer` MODIFY COLUMN `create_user` VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT ''creator (external text)''')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'customer'
      AND COLUMN_NAME = 'create_user'
);
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

SET @ddl_sql := (
    SELECT IF(COUNT(*) = 0,
              'SELECT ''customer.update_user not found''',
              'ALTER TABLE `customer` MODIFY COLUMN `update_user` VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT ''updater (external text)''')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'customer'
      AND COLUMN_NAME = 'update_user'
);
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

SET @ddl_sql := (
    SELECT IF(COUNT(*) = 0,
              'SELECT ''contract.create_user not found''',
              'ALTER TABLE `contract` MODIFY COLUMN `create_user` VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT ''creator (external text)''')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'contract'
      AND COLUMN_NAME = 'create_user'
);
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

SET @ddl_sql := (
    SELECT IF(COUNT(*) = 0,
              'SELECT ''contract.update_user not found''',
              'ALTER TABLE `contract` MODIFY COLUMN `update_user` VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT ''updater (external text)''')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'contract'
      AND COLUMN_NAME = 'update_user'
);
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

CREATE TABLE IF NOT EXISTS `mls_sync_mapping`
(
    `id`                VARCHAR(32)  NOT NULL COMMENT 'mapping id',
    `organization_id`   VARCHAR(32)  NOT NULL COMMENT 'CRM organization id',
    `source_table`      VARCHAR(64)  NOT NULL COMMENT 'external source table',
    `source_id`         VARCHAR(191) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT 'external row id',
    `target_table`      VARCHAR(64)  NOT NULL COMMENT 'CRM target table',
    `target_id`         VARCHAR(32)  NULL COMMENT 'CRM target id',
    `source_updated_at` BIGINT       NULL COMMENT 'source row update time (epoch milliseconds)',
    `source_hash`       VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'source row hash (SHA-256 preferred)',
    `status`            VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'mapping status',
    `last_error`        TEXT         NULL COMMENT 'last synchronization error',
    `last_run_id`       VARCHAR(32)  NULL COMMENT 'last synchronization run id',
    `create_time`       BIGINT       NOT NULL COMMENT 'created time (epoch milliseconds)',
    `update_time`       BIGINT       NOT NULL COMMENT 'updated time (epoch milliseconds)',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_mls_sync_mapping_source` (`organization_id`, `source_table`, `source_id`),
    KEY `idx_mls_sync_mapping_target` (`organization_id`, `target_table`, `target_id`),
    KEY `idx_mls_sync_mapping_source_updated` (`organization_id`, `source_table`, `source_updated_at`),
    KEY `idx_mls_sync_mapping_status` (`organization_id`, `source_table`, `status`),
    KEY `idx_mls_sync_mapping_run` (`last_run_id`)
) COMMENT = 'MLS external-to-CRM resource mapping'
ENGINE = InnoDB
DEFAULT CHARSET = utf8mb4
COLLATE = utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS `mls_sync_run`
(
    `run_id`                   VARCHAR(32) NOT NULL COMMENT 'synchronization run id',
    `organization_id`          VARCHAR(32) NOT NULL COMMENT 'CRM organization id',
    `trigger_type`             VARCHAR(32) NOT NULL DEFAULT 'SCHEDULED' COMMENT 'SCHEDULED or MANUAL',
    `status`                   VARCHAR(20) NOT NULL DEFAULT 'RUNNING' COMMENT 'run status',
    `stage`                    VARCHAR(32) NULL COMMENT 'current stage: customer, contract or order',
    `start_time`               BIGINT      NOT NULL COMMENT 'run start time (epoch milliseconds)',
    `end_time`                 BIGINT      NULL COMMENT 'run end time (epoch milliseconds)',
    `customer_start_time`      BIGINT      NULL COMMENT 'customer stage start time',
    `customer_end_time`        BIGINT      NULL COMMENT 'customer stage end time',
    `customer_read_count`      BIGINT      NOT NULL DEFAULT 0 COMMENT 'customer rows read',
    `customer_created_count`   BIGINT      NOT NULL DEFAULT 0 COMMENT 'customer rows created',
    `customer_updated_count`   BIGINT      NOT NULL DEFAULT 0 COMMENT 'customer rows updated',
    `customer_skipped_count`   BIGINT      NOT NULL DEFAULT 0 COMMENT 'customer rows skipped',
    `customer_failed_count`    BIGINT      NOT NULL DEFAULT 0 COMMENT 'customer rows failed',
    `customer_error_summary`   TEXT        NULL COMMENT 'customer stage error summary',
    `contract_start_time`      BIGINT      NULL COMMENT 'contract stage start time',
    `contract_end_time`        BIGINT      NULL COMMENT 'contract stage end time',
    `contract_read_count`      BIGINT      NOT NULL DEFAULT 0 COMMENT 'contract rows read',
    `contract_created_count`   BIGINT      NOT NULL DEFAULT 0 COMMENT 'contract rows created',
    `contract_updated_count`   BIGINT      NOT NULL DEFAULT 0 COMMENT 'contract rows updated',
    `contract_skipped_count`   BIGINT      NOT NULL DEFAULT 0 COMMENT 'contract rows skipped',
    `contract_failed_count`    BIGINT      NOT NULL DEFAULT 0 COMMENT 'contract rows failed',
    `contract_error_summary`   TEXT        NULL COMMENT 'contract stage error summary',
    `order_start_time`         BIGINT      NULL COMMENT 'order stage start time',
    `order_end_time`           BIGINT      NULL COMMENT 'order stage end time',
    `order_read_count`         BIGINT      NOT NULL DEFAULT 0 COMMENT 'order rows read',
    `order_created_count`      BIGINT      NOT NULL DEFAULT 0 COMMENT 'order rows created',
    `order_updated_count`      BIGINT      NOT NULL DEFAULT 0 COMMENT 'order rows updated',
    `order_skipped_count`      BIGINT      NOT NULL DEFAULT 0 COMMENT 'order rows skipped',
    `order_failed_count`       BIGINT      NOT NULL DEFAULT 0 COMMENT 'order rows failed',
    `order_error_summary`      TEXT        NULL COMMENT 'order stage error summary',
    `error_summary`            TEXT        NULL COMMENT 'run error summary',
    `create_time`              BIGINT      NOT NULL COMMENT 'created time (epoch milliseconds)',
    `update_time`              BIGINT      NOT NULL COMMENT 'updated time (epoch milliseconds)',
    PRIMARY KEY (`run_id`),
    KEY `idx_mls_sync_run_org_start` (`organization_id`, `start_time`),
    KEY `idx_mls_sync_run_org_status` (`organization_id`, `status`, `start_time`)
) COMMENT = 'MLS synchronization run audit'
ENGINE = InnoDB
DEFAULT CHARSET = utf8mb4
COLLATE = utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS `mls_sync_run_error`
(
    `id`                VARCHAR(32)  NOT NULL COMMENT 'error id',
    `run_id`            VARCHAR(32)  NOT NULL COMMENT 'synchronization run id',
    `organization_id`   VARCHAR(32)  NOT NULL COMMENT 'CRM organization id',
    `stage`             VARCHAR(32)  NOT NULL COMMENT 'customer, contract or order',
    `source_table`      VARCHAR(64)  NOT NULL COMMENT 'external source table',
    `source_id`         VARCHAR(191) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT 'external row id',
    `target_table`      VARCHAR(64)  NULL COMMENT 'CRM target table',
    `target_id`         VARCHAR(32)  NULL COMMENT 'CRM target id',
    `source_updated_at` BIGINT       NULL COMMENT 'source row update time (epoch milliseconds)',
    `status`            VARCHAR(20)  NOT NULL DEFAULT 'FAILED' COMMENT 'error status',
    `error_code`        VARCHAR(64)  NULL COMMENT 'stable error code',
    `error_message`     TEXT         NOT NULL COMMENT 'error message',
    `row_payload`       LONGTEXT     NULL COMMENT 'sanitized source row payload',
    `retryable`         BIT(1)       NOT NULL DEFAULT 0 COMMENT 'whether retry may succeed',
    `create_time`       BIGINT       NOT NULL COMMENT 'created time (epoch milliseconds)',
    `update_time`       BIGINT       NOT NULL COMMENT 'updated time (epoch milliseconds)',
    PRIMARY KEY (`id`),
    KEY `idx_mls_sync_run_error_run` (`run_id`),
    KEY `idx_mls_sync_run_error_source` (`organization_id`, `source_table`, `source_id`),
    KEY `idx_mls_sync_run_error_status` (`organization_id`, `status`, `create_time`)
) COMMENT = 'MLS synchronization row-level errors'
ENGINE = InnoDB
DEFAULT CHARSET = utf8mb4
COLLATE = utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS `mls_sync_checkpoint`
(
    `id`                  VARCHAR(32)  NOT NULL COMMENT 'checkpoint id',
    `organization_id`     VARCHAR(32)  NOT NULL COMMENT 'CRM organization id',
    `source_table`        VARCHAR(64)  NOT NULL COMMENT 'external source table',
    `cursor_updated_at`   BIGINT       NULL COMMENT 'last committed source update time',
    `cursor_id`           VARCHAR(191) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT 'last committed source id',
    `last_success_run_id` VARCHAR(32)  NULL COMMENT 'last successful synchronization run id',
    `last_success_time`   BIGINT       NULL COMMENT 'last successful synchronization time',
    `status`              VARCHAR(20)  NOT NULL DEFAULT 'READY' COMMENT 'checkpoint status',
    `last_error`          TEXT         NULL COMMENT 'last checkpoint error',
    `create_time`         BIGINT       NOT NULL COMMENT 'created time (epoch milliseconds)',
    `update_time`         BIGINT       NOT NULL COMMENT 'updated time (epoch milliseconds)',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_mls_sync_checkpoint_source` (`organization_id`, `source_table`),
    KEY `idx_mls_sync_checkpoint_status` (`organization_id`, `status`)
) COMMENT = 'MLS synchronization checkpoints'
ENGINE = InnoDB
DEFAULT CHARSET = utf8mb4
COLLATE = utf8mb4_general_ci;

-- Repair the indexes if a previous, interrupted deployment created a table
-- without all of the indexes above.  Every check is scoped to this schema.
SET @idx_exists := (
    SELECT COUNT(*)
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mls_sync_mapping'
      AND INDEX_NAME = 'uk_mls_sync_mapping_source'
);
SET @ddl_sql := IF(@idx_exists = 0,
                   'CREATE UNIQUE INDEX `uk_mls_sync_mapping_source` ON `mls_sync_mapping` (`organization_id`, `source_table`, `source_id`)',
                   'SELECT ''uk_mls_sync_mapping_source already exists''');
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

SET @idx_exists := (
    SELECT COUNT(*)
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mls_sync_checkpoint'
      AND INDEX_NAME = 'uk_mls_sync_checkpoint_source'
);
SET @ddl_sql := IF(@idx_exists = 0,
                   'CREATE UNIQUE INDEX `uk_mls_sync_checkpoint_source` ON `mls_sync_checkpoint` (`organization_id`, `source_table`)',
                   'SELECT ''uk_mls_sync_checkpoint_source already exists''');
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;
