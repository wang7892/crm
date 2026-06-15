SET @order_sync_now := UNIX_TIMESTAMP() * 1000;

UPDATE sys_module_field mf
JOIN sys_module_form form ON form.id = mf.form_id AND form.form_key = 'order'
SET mf.name = '订单号',
    mf.type = 'INPUT'
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
        '$.editable', TRUE,
        '$.rules', JSON_ARRAY(JSON_OBJECT('key', 'required'))
    ),
    '$.serialNumberRules',
    '$.disableProps'
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
