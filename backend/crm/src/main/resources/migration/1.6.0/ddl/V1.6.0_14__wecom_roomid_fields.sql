DROP TABLE IF EXISTS wecom_room_customer_binding;

SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'customer'
      AND COLUMN_NAME = 'roomid'
);
SET @ddl_sql := IF(@col_exists = 0, 'ALTER TABLE customer ADD COLUMN roomid VARCHAR(1024) DEFAULT NULL COMMENT ''企业微信群聊 roomid，多个 roomid 可用英文逗号分隔'' AFTER wecom_external_id', 'SELECT ''customer.roomid already exists''');
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'sys_user'
      AND COLUMN_NAME = 'roomid'
);
SET @ddl_sql := IF(@col_exists = 0, 'ALTER TABLE sys_user ADD COLUMN roomid VARCHAR(1024) DEFAULT NULL COMMENT ''企业微信群聊 roomid，多个 roomid 可用英文逗号分隔'' AFTER wecom_id', 'SELECT ''sys_user.roomid already exists''');
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;
