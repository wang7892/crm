SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'customer'
      AND COLUMN_NAME = 'customer_available'
);
SET @ddl_sql := IF(@col_exists = 0, 'ALTER TABLE customer ADD COLUMN customer_available VARCHAR(255) NULL COMMENT ''Customer available'' AFTER remark', 'SELECT ''customer_available already exists''');
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'customer'
      AND COLUMN_NAME = 'customer_source'
);
SET @ddl_sql := IF(@col_exists = 0, 'ALTER TABLE customer ADD COLUMN customer_source VARCHAR(255) NULL COMMENT ''Customer source'' AFTER customer_available', 'SELECT ''customer_source already exists''');
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;
