-- Warehouse actual shipment date supplied by mls_agent_data.order_timeline.
SET @column_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'sales_order'
      AND COLUMN_NAME = 'warehouse_actual_ship_date'
);
SET @ddl_sql := IF(@column_exists = 0,
                   'ALTER TABLE `sales_order` ADD COLUMN `warehouse_actual_ship_date` BIGINT NULL COMMENT ''warehouse actual shipment date (epoch milliseconds)'' AFTER `order_time`',
                   'SELECT ''sales_order.warehouse_actual_ship_date already exists''');
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;
