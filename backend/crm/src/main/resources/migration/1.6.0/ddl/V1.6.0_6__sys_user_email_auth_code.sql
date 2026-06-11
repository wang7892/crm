SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'sys_user'
      AND COLUMN_NAME = 'email_auth_code'
);

SET @ddl_sql := IF(
    @col_exists = 0,
    'ALTER TABLE sys_user ADD COLUMN email_auth_code VARCHAR(255) NULL COMMENT ''邮箱授权码'' AFTER email',
    'SELECT ''email_auth_code already exists'''
);

PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;
