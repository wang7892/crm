SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'sys_user'
      AND COLUMN_NAME = 'wecom_id'
);

SET @ddl_sql := IF(
    @col_exists = 0,
    'ALTER TABLE sys_user ADD COLUMN wecom_id VARCHAR(255) NULL COMMENT ''企微id'' AFTER email_auth_code',
    'SELECT ''wecom_id already exists'''
);

PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;
