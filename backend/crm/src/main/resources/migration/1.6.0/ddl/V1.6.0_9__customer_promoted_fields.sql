SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'customer'
      AND COLUMN_NAME = 'wecom_external_id'
);
SET @ddl_sql := IF(@col_exists = 0, 'ALTER TABLE customer ADD COLUMN wecom_external_id VARCHAR(255) NULL COMMENT ''Customer WeCom external ID''', 'SELECT ''wecom_external_id already exists''');
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'customer'
      AND COLUMN_NAME = 'email'
);
SET @ddl_sql := IF(@col_exists = 0, 'ALTER TABLE customer ADD COLUMN email VARCHAR(255) NULL COMMENT ''Email''', 'SELECT ''email already exists''');
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'customer'
      AND COLUMN_NAME = 'full_name'
);
SET @ddl_sql := IF(@col_exists = 0, 'ALTER TABLE customer ADD COLUMN full_name VARCHAR(255) NULL COMMENT ''Full name''', 'SELECT ''full_name already exists''');
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'customer'
      AND COLUMN_NAME = 'credit_limit'
);
SET @ddl_sql := IF(@col_exists = 0, 'ALTER TABLE customer ADD COLUMN credit_limit VARCHAR(255) NULL COMMENT ''Credit limit''', 'SELECT ''credit_limit already exists''');
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'customer'
      AND COLUMN_NAME = 'customs_code'
);
SET @ddl_sql := IF(@col_exists = 0, 'ALTER TABLE customer ADD COLUMN customs_code VARCHAR(255) NULL COMMENT ''Customs code''', 'SELECT ''customs_code already exists''');
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'customer'
      AND COLUMN_NAME = 'region'
);
SET @ddl_sql := IF(@col_exists = 0, 'ALTER TABLE customer ADD COLUMN region VARCHAR(255) NULL COMMENT ''Region''', 'SELECT ''region already exists''');
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'customer'
      AND COLUMN_NAME = 'phone'
);
SET @ddl_sql := IF(@col_exists = 0, 'ALTER TABLE customer ADD COLUMN phone VARCHAR(255) NULL COMMENT ''Phone''', 'SELECT ''phone already exists''');
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'customer'
      AND COLUMN_NAME = 'address'
);
SET @ddl_sql := IF(@col_exists = 0, 'ALTER TABLE customer ADD COLUMN address VARCHAR(512) NULL COMMENT ''Address''', 'SELECT ''address already exists''');
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'customer'
      AND COLUMN_NAME = 'remark'
);
SET @ddl_sql := IF(@col_exists = 0, 'ALTER TABLE customer ADD COLUMN remark VARCHAR(512) NULL COMMENT ''Remark''', 'SELECT ''remark already exists''');
PREPARE ddl_stmt FROM @ddl_sql;
EXECUTE ddl_stmt;
DEALLOCATE PREPARE ddl_stmt;

UPDATE customer c
JOIN customer_field cf ON cf.resource_id = c.id AND cf.field_id = '177676244643300000'
SET c.wecom_external_id = cf.field_value
WHERE (c.wecom_external_id IS NULL OR c.wecom_external_id = '')
  AND cf.field_value IS NOT NULL
  AND cf.field_value <> '';

UPDATE customer c
JOIN customer_field cf ON cf.resource_id = c.id AND cf.field_id = '177676248585700000'
SET c.email = cf.field_value
WHERE (c.email IS NULL OR c.email = '')
  AND cf.field_value IS NOT NULL
  AND cf.field_value <> '';

UPDATE customer c
JOIN customer_field cf ON cf.resource_id = c.id AND cf.field_id = '177855464453000000'
SET c.full_name = cf.field_value
WHERE (c.full_name IS NULL OR c.full_name = '')
  AND cf.field_value IS NOT NULL
  AND cf.field_value <> '';

UPDATE customer c
JOIN customer_field cf ON cf.resource_id = c.id AND cf.field_id = '177855488944900000'
SET c.credit_limit = cf.field_value
WHERE (c.credit_limit IS NULL OR c.credit_limit = '')
  AND cf.field_value IS NOT NULL
  AND cf.field_value <> '';

UPDATE customer c
JOIN customer_field cf ON cf.resource_id = c.id AND cf.field_id = '177855497741600000'
SET c.customs_code = cf.field_value
WHERE (c.customs_code IS NULL OR c.customs_code = '')
  AND cf.field_value IS NOT NULL
  AND cf.field_value <> '';

UPDATE customer c
JOIN customer_field cf ON cf.resource_id = c.id AND cf.field_id = '177855499908200000'
SET c.region = cf.field_value
WHERE (c.region IS NULL OR c.region = '')
  AND cf.field_value IS NOT NULL
  AND cf.field_value <> '';

UPDATE customer c
JOIN customer_field cf ON cf.resource_id = c.id AND cf.field_id = '177855548250600000'
SET c.phone = cf.field_value
WHERE (c.phone IS NULL OR c.phone = '')
  AND cf.field_value IS NOT NULL
  AND cf.field_value <> '';

UPDATE customer c
JOIN customer_field cf ON cf.resource_id = c.id AND cf.field_id = '177855517842000000'
SET c.address = cf.field_value
WHERE (c.address IS NULL OR c.address = '')
  AND cf.field_value IS NOT NULL
  AND cf.field_value <> '';

UPDATE customer c
JOIN customer_field cf ON cf.resource_id = c.id AND cf.field_id = '177855575351400000'
SET c.remark = cf.field_value
WHERE (c.remark IS NULL OR c.remark = '')
  AND cf.field_value IS NOT NULL
  AND cf.field_value <> '';

UPDATE sys_module_field SET internal_key = 'customerWecomExternalId' WHERE id = '177676244643300000';
UPDATE sys_module_field SET internal_key = 'customerEmail' WHERE id = '177676248585700000';
UPDATE sys_module_field SET internal_key = 'customerFullName' WHERE id = '177855464453000000';
UPDATE sys_module_field SET internal_key = 'customerCreditLimit' WHERE id = '177855488944900000';
UPDATE sys_module_field SET internal_key = 'customerCustomsCode' WHERE id = '177855497741600000';
UPDATE sys_module_field SET internal_key = 'customerRegion' WHERE id = '177855499908200000';
UPDATE sys_module_field SET internal_key = 'customerPhone' WHERE id = '177855548250600000';
UPDATE sys_module_field SET internal_key = 'customerAddress' WHERE id = '177855517842000000';
UPDATE sys_module_field SET internal_key = 'customerRemark' WHERE id = '177855575351400000';
