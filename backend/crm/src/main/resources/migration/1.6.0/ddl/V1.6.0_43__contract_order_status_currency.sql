SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'contract'
      AND COLUMN_NAME = 'order_status'
);
SET @ddl_sql := IF(@col_exists = 0,
                   'ALTER TABLE contract ADD COLUMN order_status VARCHAR(255) NULL COMMENT ''订单状态'' AFTER amount',
                   'SELECT ''contract.order_status already exists''');
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'contract'
      AND COLUMN_NAME = 'currency'
);
SET @ddl_sql := IF(@col_exists = 0,
                   'ALTER TABLE contract ADD COLUMN currency VARCHAR(20) NULL COMMENT ''币种'' AFTER order_status',
                   'SELECT ''contract.currency already exists''');
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;
