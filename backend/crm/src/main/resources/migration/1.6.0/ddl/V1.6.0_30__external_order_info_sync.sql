SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'sales_order'
      AND COLUMN_NAME = 'external_order_info_id'
);
SET @ddl_sql := IF(@col_exists = 0, 'ALTER TABLE sales_order ADD COLUMN external_order_info_id VARCHAR(50) NULL COMMENT ''External order_info id''', 'SELECT ''external_order_info_id already exists''');
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

SET @idx_exists := (
    SELECT COUNT(*)
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'sales_order'
      AND INDEX_NAME = 'uk_sales_order_external_info_org'
);
SET @ddl_sql := IF(@idx_exists = 0, 'CREATE UNIQUE INDEX uk_sales_order_external_info_org ON sales_order (organization_id, external_order_info_id)', 'SELECT ''uk_sales_order_external_info_org already exists''');
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;
