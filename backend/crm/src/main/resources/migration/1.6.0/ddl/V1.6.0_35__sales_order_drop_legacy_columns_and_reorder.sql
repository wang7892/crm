SET @has_stage := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'sales_order'
      AND COLUMN_NAME = 'stage'
);
SET @has_status := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'sales_order'
      AND COLUMN_NAME = 'status'
);
SET @ddl_sql := IF(@has_stage > 0 AND @has_status > 0,
                   'UPDATE sales_order SET status = stage WHERE (status IS NULL OR status = '''') AND stage IS NOT NULL AND stage <> ''''',
                   'SELECT ''skip stage to status migration''');
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

SET @idx_exists := (
    SELECT COUNT(*)
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'sales_order'
      AND INDEX_NAME = 'idx_name'
);
SET @ddl_sql := IF(@idx_exists > 0, 'DROP INDEX idx_name ON sales_order', 'SELECT ''idx_name already dropped''');
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
SET @ddl_sql := IF(@idx_exists > 0, 'DROP INDEX uk_sales_order_external_info_org ON sales_order', 'SELECT ''uk_sales_order_external_info_org already dropped''');
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'sales_order'
      AND COLUMN_NAME = 'number'
);
SET @ddl_sql := IF(@col_exists > 0, 'ALTER TABLE sales_order DROP COLUMN number', 'SELECT ''number already dropped''');
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'sales_order'
      AND COLUMN_NAME = 'name'
);
SET @ddl_sql := IF(@col_exists > 0, 'ALTER TABLE sales_order DROP COLUMN name', 'SELECT ''name already dropped''');
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'sales_order'
      AND COLUMN_NAME = 'external_order_info_id'
);
SET @ddl_sql := IF(@col_exists > 0, 'ALTER TABLE sales_order DROP COLUMN external_order_info_id', 'SELECT ''external_order_info_id already dropped''');
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'sales_order'
      AND COLUMN_NAME = 'stage'
);
SET @ddl_sql := IF(@col_exists > 0, 'ALTER TABLE sales_order DROP COLUMN stage', 'SELECT ''stage already dropped''');
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

ALTER TABLE sales_order MODIFY COLUMN order_no VARCHAR(50) NULL COMMENT '订单号' AFTER id;
ALTER TABLE sales_order MODIFY COLUMN customer_id VARCHAR(32) NULL COMMENT '客户id' AFTER order_no;
ALTER TABLE sales_order MODIFY COLUMN contract_id VARCHAR(32) NULL COMMENT '合同id' AFTER customer_id;
ALTER TABLE sales_order MODIFY COLUMN owner VARCHAR(32) NULL COMMENT '联系专员' AFTER contract_id;
ALTER TABLE sales_order MODIFY COLUMN organization_id VARCHAR(32) NOT NULL COMMENT '组织id' AFTER owner;
ALTER TABLE sales_order MODIFY COLUMN process_order_no VARCHAR(50) NULL COMMENT '加工单号' AFTER organization_id;
ALTER TABLE sales_order MODIFY COLUMN processor VARCHAR(100) NULL COMMENT '加工商' AFTER process_order_no;
ALTER TABLE sales_order MODIFY COLUMN merchandiser VARCHAR(50) NULL COMMENT '跟单员' AFTER processor;
ALTER TABLE sales_order MODIFY COLUMN status VARCHAR(50) NULL COMMENT '状态' AFTER merchandiser;
ALTER TABLE sales_order MODIFY COLUMN color VARCHAR(50) NULL COMMENT '颜色' AFTER status;
ALTER TABLE sales_order MODIFY COLUMN color_code VARCHAR(50) NULL COMMENT '色号' AFTER color;
ALTER TABLE sales_order MODIFY COLUMN composition VARCHAR(200) NULL COMMENT '成分' AFTER color_code;
ALTER TABLE sales_order MODIFY COLUMN material_name VARCHAR(100) NULL COMMENT '原料名称' AFTER composition;
ALTER TABLE sales_order MODIFY COLUMN material_type VARCHAR(50) NULL COMMENT '原料类型' AFTER material_name;
ALTER TABLE sales_order MODIFY COLUMN process_technology VARCHAR(100) NULL COMMENT '加工工艺' AFTER material_type;
ALTER TABLE sales_order MODIFY COLUMN order_time BIGINT NULL COMMENT '下单时间' AFTER process_technology;
ALTER TABLE sales_order MODIFY COLUMN quantity DECIMAL(15, 2) NULL COMMENT '数量' AFTER order_time;
ALTER TABLE sales_order MODIFY COLUMN unit VARCHAR(20) NULL COMMENT '单位' AFTER quantity;
ALTER TABLE sales_order MODIFY COLUMN unit_price DECIMAL(15, 2) NULL COMMENT '单价' AFTER unit;
ALTER TABLE sales_order MODIFY COLUMN amount DECIMAL(20, 10) NULL COMMENT '金额' AFTER unit_price;
ALTER TABLE sales_order MODIFY COLUMN currency VARCHAR(20) NULL COMMENT '币种' AFTER amount;
ALTER TABLE sales_order MODIFY COLUMN create_time BIGINT NOT NULL COMMENT '创建时间' AFTER currency;
ALTER TABLE sales_order MODIFY COLUMN update_time BIGINT NOT NULL COMMENT '更新时间' AFTER create_time;
ALTER TABLE sales_order MODIFY COLUMN create_user VARCHAR(32) NOT NULL COMMENT '创建人' AFTER update_time;
ALTER TABLE sales_order MODIFY COLUMN update_user VARCHAR(32) NOT NULL COMMENT '更新人' AFTER create_user;
