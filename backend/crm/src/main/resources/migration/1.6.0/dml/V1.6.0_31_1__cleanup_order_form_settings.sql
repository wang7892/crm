UPDATE sys_module_field mf
JOIN sys_module_form form ON form.id = mf.form_id AND form.form_key = 'order'
SET mf.name = '订单号',
    mf.type = 'INPUT',
    mf.mobile = b'1'
WHERE mf.internal_key = 'orderNo';

UPDATE sys_module_field_blob mfb
JOIN sys_module_field mf ON mf.id = mfb.id
JOIN sys_module_form form ON form.id = mf.form_id AND form.form_key = 'order'
SET mfb.prop = JSON_REMOVE(
    JSON_SET(
        mfb.prop,
        '$.name', '订单号',
        '$.type', 'INPUT',
        '$.placeholder', '请输入订单号',
        '$.readable', TRUE,
        '$.editable', TRUE,
        '$.mobile', TRUE,
        '$.rules', JSON_ARRAY(JSON_OBJECT('key', 'required'))
    ),
    '$.serialNumberRules',
    '$.disableProps',
    '$.disabledProps',
    '$.prefixType',
    '$.prefixDefaultValue'
)
WHERE mf.internal_key = 'orderNo';

DELETE sof
FROM sales_order_field sof
JOIN sys_module_field mf ON mf.id = sof.field_id
JOIN sys_module_form form ON form.id = mf.form_id AND form.form_key = 'order'
WHERE mf.internal_key IN ('orderName', 'orderAmount', 'orderCustomer', 'orderContract');

DELETE sofb
FROM sales_order_field_blob sofb
JOIN sys_module_field mf ON mf.id = sofb.field_id
JOIN sys_module_form form ON form.id = mf.form_id AND form.form_key = 'order'
WHERE mf.internal_key IN ('orderName', 'orderAmount', 'orderCustomer', 'orderContract');

DELETE mfb
FROM sys_module_field_blob mfb
JOIN sys_module_field mf ON mf.id = mfb.id
JOIN sys_module_form form ON form.id = mf.form_id AND form.form_key = 'order'
WHERE mf.internal_key IN ('orderName', 'orderAmount', 'orderCustomer', 'orderContract');

DELETE mf
FROM sys_module_field mf
JOIN sys_module_form form ON form.id = mf.form_id AND form.form_key = 'order'
WHERE mf.internal_key IN ('orderName', 'orderAmount', 'orderCustomer', 'orderContract');

DROP TEMPORARY TABLE IF EXISTS tmp_auto_order_sync_field_ids;
CREATE TEMPORARY TABLE tmp_auto_order_sync_field_ids
(
    id VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL
);

INSERT INTO tmp_auto_order_sync_field_ids (id)
SELECT MD5(CONCAT(form.id, ':', def.internal_key))
FROM sys_module_form form
JOIN (
    SELECT 'processOrderNo' AS internal_key
    UNION ALL SELECT 'orderProcessor'
    UNION ALL SELECT 'orderMerchandiser'
    UNION ALL SELECT 'orderExternalStatus'
    UNION ALL SELECT 'orderColor'
    UNION ALL SELECT 'orderColorCode'
    UNION ALL SELECT 'orderComposition'
    UNION ALL SELECT 'orderMaterialName'
    UNION ALL SELECT 'orderMaterialType'
    UNION ALL SELECT 'orderProcessTechnology'
    UNION ALL SELECT 'orderTime'
    UNION ALL SELECT 'orderQuantity'
    UNION ALL SELECT 'orderUnit'
    UNION ALL SELECT 'orderUnitPrice'
    UNION ALL SELECT 'orderCurrency'
) def
WHERE form.form_key = 'order';

DELETE sof
FROM sales_order_field sof
JOIN tmp_auto_order_sync_field_ids tmp ON tmp.id = sof.field_id;

DELETE sofb
FROM sales_order_field_blob sofb
JOIN tmp_auto_order_sync_field_ids tmp ON tmp.id = sofb.field_id;

DELETE mfb
FROM sys_module_field_blob mfb
JOIN tmp_auto_order_sync_field_ids tmp ON tmp.id = mfb.id;

DELETE mf
FROM sys_module_field mf
JOIN tmp_auto_order_sync_field_ids tmp ON tmp.id = mf.id;

DROP TEMPORARY TABLE IF EXISTS tmp_auto_order_sync_field_ids;
