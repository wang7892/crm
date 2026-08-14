-- One external source row must own one CRM target within an organization.
-- Detach only duplicate mappings before adding the invariant; target CRM rows
-- are deliberately preserved and will be remapped by the next synchronization.
UPDATE `mls_sync_mapping` mapping
JOIN (
    SELECT duplicate_targets.organization_id,
           duplicate_targets.source_table,
           duplicate_targets.target_table,
           duplicate_targets.target_id,
           duplicate_targets.keep_mapping_id
    FROM (
        SELECT organization_id, source_table, target_table, target_id,
               MIN(id) AS keep_mapping_id
        FROM `mls_sync_mapping`
        WHERE target_id IS NOT NULL
        GROUP BY organization_id, source_table, target_table, target_id
        HAVING COUNT(*) > 1
    ) duplicate_targets
) duplicates
  ON duplicates.organization_id = mapping.organization_id
 AND duplicates.source_table = mapping.source_table
 AND duplicates.target_table = mapping.target_table
 AND duplicates.target_id = mapping.target_id
SET mapping.target_id = NULL,
    mapping.status = 'FAILED',
    mapping.last_error = 'Duplicate target mapping detached by V1.6.0_45',
    mapping.update_time = UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000
WHERE mapping.id <> duplicates.keep_mapping_id;

SET @idx_exists := (
    SELECT COUNT(*)
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mls_sync_mapping'
      AND INDEX_NAME = 'uk_mls_sync_mapping_target'
);
SET @ddl_sql := IF(@idx_exists = 0,
                   'CREATE UNIQUE INDEX `uk_mls_sync_mapping_target` ON `mls_sync_mapping` (`organization_id`, `source_table`, `target_table`, `target_id`)',
                   'SELECT ''uk_mls_sync_mapping_target already exists''');
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;
