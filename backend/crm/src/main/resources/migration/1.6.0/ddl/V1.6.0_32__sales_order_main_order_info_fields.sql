SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'sales_order'
      AND COLUMN_NAME = 'order_no'
);
SET @ddl_sql := IF(@col_exists = 0, 'ALTER TABLE sales_order ADD COLUMN order_no VARCHAR(50) NULL COMMENT ''订单号''', 'SELECT ''order_no already exists''');
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'sales_order'
      AND COLUMN_NAME = 'process_order_no'
);
SET @ddl_sql := IF(@col_exists = 0, 'ALTER TABLE sales_order ADD COLUMN process_order_no VARCHAR(50) NULL COMMENT ''加工单号''', 'SELECT ''process_order_no already exists''');
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'sales_order'
      AND COLUMN_NAME = 'processor'
);
SET @ddl_sql := IF(@col_exists = 0, 'ALTER TABLE sales_order ADD COLUMN processor VARCHAR(100) NULL COMMENT ''加工商''', 'SELECT ''processor already exists''');
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'sales_order'
      AND COLUMN_NAME = 'merchandiser'
);
SET @ddl_sql := IF(@col_exists = 0, 'ALTER TABLE sales_order ADD COLUMN merchandiser VARCHAR(50) NULL COMMENT ''跟单员''', 'SELECT ''merchandiser already exists''');
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'sales_order'
      AND COLUMN_NAME = 'status'
);
SET @ddl_sql := IF(@col_exists = 0, 'ALTER TABLE sales_order ADD COLUMN status VARCHAR(50) NULL COMMENT ''状态''', 'SELECT ''status already exists''');
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'sales_order'
      AND COLUMN_NAME = 'color'
);
SET @ddl_sql := IF(@col_exists = 0, 'ALTER TABLE sales_order ADD COLUMN color VARCHAR(50) NULL COMMENT ''颜色''', 'SELECT ''color already exists''');
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'sales_order'
      AND COLUMN_NAME = 'color_code'
);
SET @ddl_sql := IF(@col_exists = 0, 'ALTER TABLE sales_order ADD COLUMN color_code VARCHAR(50) NULL COMMENT ''色号''', 'SELECT ''color_code already exists''');
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'sales_order'
      AND COLUMN_NAME = 'composition'
);
SET @ddl_sql := IF(@col_exists = 0, 'ALTER TABLE sales_order ADD COLUMN composition VARCHAR(200) NULL COMMENT ''成分''', 'SELECT ''composition already exists''');
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'sales_order'
      AND COLUMN_NAME = 'material_name'
);
SET @ddl_sql := IF(@col_exists = 0, 'ALTER TABLE sales_order ADD COLUMN material_name VARCHAR(100) NULL COMMENT ''原料名称''', 'SELECT ''material_name already exists''');
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'sales_order'
      AND COLUMN_NAME = 'material_type'
);
SET @ddl_sql := IF(@col_exists = 0, 'ALTER TABLE sales_order ADD COLUMN material_type VARCHAR(50) NULL COMMENT ''原料类型''', 'SELECT ''material_type already exists''');
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'sales_order'
      AND COLUMN_NAME = 'process_technology'
);
SET @ddl_sql := IF(@col_exists = 0, 'ALTER TABLE sales_order ADD COLUMN process_technology VARCHAR(100) NULL COMMENT ''加工工艺''', 'SELECT ''process_technology already exists''');
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'sales_order'
      AND COLUMN_NAME = 'order_time'
);
SET @ddl_sql := IF(@col_exists = 0, 'ALTER TABLE sales_order ADD COLUMN order_time BIGINT NULL COMMENT ''下单时间''', 'SELECT ''order_time already exists''');
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'sales_order'
      AND COLUMN_NAME = 'quantity'
);
SET @ddl_sql := IF(@col_exists = 0, 'ALTER TABLE sales_order ADD COLUMN quantity DECIMAL(15,2) NULL COMMENT ''数量''', 'SELECT ''quantity already exists''');
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'sales_order'
      AND COLUMN_NAME = 'unit'
);
SET @ddl_sql := IF(@col_exists = 0, 'ALTER TABLE sales_order ADD COLUMN unit VARCHAR(20) NULL COMMENT ''单位''', 'SELECT ''unit already exists''');
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'sales_order'
      AND COLUMN_NAME = 'unit_price'
);
SET @ddl_sql := IF(@col_exists = 0, 'ALTER TABLE sales_order ADD COLUMN unit_price DECIMAL(15,2) NULL COMMENT ''单价''', 'SELECT ''unit_price already exists''');
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'sales_order'
      AND COLUMN_NAME = 'currency'
);
SET @ddl_sql := IF(@col_exists = 0, 'ALTER TABLE sales_order ADD COLUMN currency VARCHAR(20) NULL COMMENT ''币种''', 'SELECT ''currency already exists''');
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

UPDATE sales_order
SET order_no = number
WHERE (order_no IS NULL OR order_no = '')
  AND number IS NOT NULL
  AND number <> '';

SET @idx_exists := (
    SELECT COUNT(*)
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'sales_order'
      AND INDEX_NAME = 'idx_sales_order_org_order_no'
);
SET @ddl_sql := IF(@idx_exists = 0, 'CREATE INDEX idx_sales_order_org_order_no ON sales_order (organization_id, order_no)', 'SELECT ''idx_sales_order_org_order_no already exists''');
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

SET @idx_exists := (
    SELECT COUNT(*)
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'sales_order'
      AND INDEX_NAME = 'idx_sales_order_org_process_order_no'
);
SET @ddl_sql := IF(@idx_exists = 0, 'CREATE INDEX idx_sales_order_org_process_order_no ON sales_order (organization_id, process_order_no)', 'SELECT ''idx_sales_order_org_process_order_no already exists''');
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;
