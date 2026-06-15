ALTER TABLE sales_order MODIFY COLUMN number VARCHAR(50) NULL COMMENT '编号';
ALTER TABLE sales_order MODIFY COLUMN name VARCHAR(255) NULL COMMENT '订单名称';
ALTER TABLE sales_order MODIFY COLUMN customer_id VARCHAR(32) NULL COMMENT '客户id';
ALTER TABLE sales_order MODIFY COLUMN contract_id VARCHAR(32) NULL COMMENT '合同id';
ALTER TABLE sales_order MODIFY COLUMN owner VARCHAR(32) NULL COMMENT '订单负责人';
ALTER TABLE sales_order MODIFY COLUMN amount DECIMAL(20, 10) NULL COMMENT '金额';

UPDATE sales_order
SET order_no = CASE
        WHEN order_no IS NULL OR order_no = '' THEN NULLIF(number, '')
        ELSE order_no
    END,
    number = NULL,
    name = NULL,
    customer_id = NULL,
    contract_id = NULL
WHERE external_order_info_id IS NOT NULL;
