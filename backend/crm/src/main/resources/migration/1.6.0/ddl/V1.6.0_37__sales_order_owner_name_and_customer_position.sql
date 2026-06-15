ALTER TABLE sales_order MODIFY COLUMN order_no VARCHAR(50) NULL COMMENT '订单号' AFTER id;
ALTER TABLE sales_order MODIFY COLUMN customer_id VARCHAR(32) NULL COMMENT '客户id' AFTER order_no;
ALTER TABLE sales_order MODIFY COLUMN contract_id VARCHAR(32) NULL COMMENT '合同id' AFTER customer_id;
ALTER TABLE sales_order MODIFY COLUMN owner VARCHAR(255) NULL COMMENT '联系专员' AFTER contract_id;
ALTER TABLE sales_order MODIFY COLUMN organization_id VARCHAR(32) NOT NULL COMMENT '组织id' AFTER owner;

UPDATE sales_order so
JOIN sys_user su ON su.id = so.owner
SET so.owner = su.name
WHERE so.owner IS NOT NULL
  AND so.owner <> '';
