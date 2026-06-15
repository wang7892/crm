DROP TEMPORARY TABLE IF EXISTS tmp_order_info_promoted_fields;
CREATE TEMPORARY TABLE tmp_order_info_promoted_fields
(
    field_name   VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
    internal_key VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
    field_type   VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL
);

INSERT INTO tmp_order_info_promoted_fields (field_name, internal_key, field_type)
VALUES ('加工单号', 'processOrderNo', 'INPUT'),
       ('加工商', 'orderProcessor', 'INPUT'),
       ('跟单员', 'orderMerchandiser', 'INPUT'),
       ('状态', 'orderExternalStatus', 'INPUT'),
       ('颜色', 'orderColor', 'INPUT'),
       ('色号', 'orderColorCode', 'INPUT'),
       ('成分', 'orderComposition', 'INPUT'),
       ('原料名称', 'orderMaterialName', 'INPUT'),
       ('原料类型', 'orderMaterialType', 'INPUT'),
       ('加工工艺', 'orderProcessTechnology', 'INPUT'),
       ('下单时间', 'orderTime', 'DATE_TIME'),
       ('数量', 'orderQuantity', 'INPUT_NUMBER'),
       ('单位', 'orderUnit', 'INPUT'),
       ('单价', 'orderUnitPrice', 'INPUT_NUMBER'),
       ('金额', 'orderAmount', 'INPUT_NUMBER'),
       ('币种', 'orderCurrency', 'INPUT');

UPDATE sys_module_field mf
JOIN sys_module_form form ON form.id = mf.form_id AND form.form_key = 'order'
JOIN tmp_order_info_promoted_fields tmp
  ON mf.internal_key COLLATE utf8mb4_unicode_ci = tmp.internal_key COLLATE utf8mb4_unicode_ci
  OR (
      mf.name COLLATE utf8mb4_unicode_ci = tmp.field_name COLLATE utf8mb4_unicode_ci
      AND (mf.internal_key IS NULL OR mf.internal_key = '' OR mf.internal_key COLLATE utf8mb4_unicode_ci = tmp.internal_key COLLATE utf8mb4_unicode_ci)
  )
SET mf.internal_key = tmp.internal_key,
    mf.type = tmp.field_type;

UPDATE sys_module_field_blob mfb
JOIN sys_module_field mf ON mf.id = mfb.id
JOIN sys_module_form form ON form.id = mf.form_id AND form.form_key = 'order'
JOIN tmp_order_info_promoted_fields tmp ON mf.internal_key COLLATE utf8mb4_unicode_ci = tmp.internal_key COLLATE utf8mb4_unicode_ci
SET mfb.prop = JSON_SET(
        COALESCE(NULLIF(mfb.prop, ''), '{}'),
        '$.internalKey', tmp.internal_key,
        '$.type', tmp.field_type
    );

UPDATE sales_order so
JOIN sales_order_field sof ON sof.resource_id = so.id
JOIN sys_module_field mf ON mf.id = sof.field_id
JOIN sys_module_form form ON form.id = mf.form_id AND form.form_key = 'order'
SET so.process_order_no = sof.field_value
WHERE mf.internal_key = 'processOrderNo'
  AND (so.process_order_no IS NULL OR so.process_order_no = '')
  AND sof.field_value IS NOT NULL
  AND sof.field_value <> '';

UPDATE sales_order so
JOIN sales_order_field sof ON sof.resource_id = so.id
JOIN sys_module_field mf ON mf.id = sof.field_id
JOIN sys_module_form form ON form.id = mf.form_id AND form.form_key = 'order'
SET so.processor = sof.field_value
WHERE mf.internal_key = 'orderProcessor'
  AND (so.processor IS NULL OR so.processor = '')
  AND sof.field_value IS NOT NULL
  AND sof.field_value <> '';

UPDATE sales_order so
JOIN sales_order_field sof ON sof.resource_id = so.id
JOIN sys_module_field mf ON mf.id = sof.field_id
JOIN sys_module_form form ON form.id = mf.form_id AND form.form_key = 'order'
SET so.merchandiser = sof.field_value
WHERE mf.internal_key = 'orderMerchandiser'
  AND (so.merchandiser IS NULL OR so.merchandiser = '')
  AND sof.field_value IS NOT NULL
  AND sof.field_value <> '';

UPDATE sales_order so
JOIN sales_order_field sof ON sof.resource_id = so.id
JOIN sys_module_field mf ON mf.id = sof.field_id
JOIN sys_module_form form ON form.id = mf.form_id AND form.form_key = 'order'
SET so.status = sof.field_value
WHERE mf.internal_key = 'orderExternalStatus'
  AND (so.status IS NULL OR so.status = '')
  AND sof.field_value IS NOT NULL
  AND sof.field_value <> '';

UPDATE sales_order so
JOIN sales_order_field sof ON sof.resource_id = so.id
JOIN sys_module_field mf ON mf.id = sof.field_id
JOIN sys_module_form form ON form.id = mf.form_id AND form.form_key = 'order'
SET so.color = sof.field_value
WHERE mf.internal_key = 'orderColor'
  AND (so.color IS NULL OR so.color = '')
  AND sof.field_value IS NOT NULL
  AND sof.field_value <> '';

UPDATE sales_order so
JOIN sales_order_field sof ON sof.resource_id = so.id
JOIN sys_module_field mf ON mf.id = sof.field_id
JOIN sys_module_form form ON form.id = mf.form_id AND form.form_key = 'order'
SET so.color_code = sof.field_value
WHERE mf.internal_key = 'orderColorCode'
  AND (so.color_code IS NULL OR so.color_code = '')
  AND sof.field_value IS NOT NULL
  AND sof.field_value <> '';

UPDATE sales_order so
JOIN sales_order_field sof ON sof.resource_id = so.id
JOIN sys_module_field mf ON mf.id = sof.field_id
JOIN sys_module_form form ON form.id = mf.form_id AND form.form_key = 'order'
SET so.composition = sof.field_value
WHERE mf.internal_key = 'orderComposition'
  AND (so.composition IS NULL OR so.composition = '')
  AND sof.field_value IS NOT NULL
  AND sof.field_value <> '';

UPDATE sales_order so
JOIN sales_order_field sof ON sof.resource_id = so.id
JOIN sys_module_field mf ON mf.id = sof.field_id
JOIN sys_module_form form ON form.id = mf.form_id AND form.form_key = 'order'
SET so.material_name = sof.field_value
WHERE mf.internal_key = 'orderMaterialName'
  AND (so.material_name IS NULL OR so.material_name = '')
  AND sof.field_value IS NOT NULL
  AND sof.field_value <> '';

UPDATE sales_order so
JOIN sales_order_field sof ON sof.resource_id = so.id
JOIN sys_module_field mf ON mf.id = sof.field_id
JOIN sys_module_form form ON form.id = mf.form_id AND form.form_key = 'order'
SET so.material_type = sof.field_value
WHERE mf.internal_key = 'orderMaterialType'
  AND (so.material_type IS NULL OR so.material_type = '')
  AND sof.field_value IS NOT NULL
  AND sof.field_value <> '';

UPDATE sales_order so
JOIN sales_order_field sof ON sof.resource_id = so.id
JOIN sys_module_field mf ON mf.id = sof.field_id
JOIN sys_module_form form ON form.id = mf.form_id AND form.form_key = 'order'
SET so.process_technology = sof.field_value
WHERE mf.internal_key = 'orderProcessTechnology'
  AND (so.process_technology IS NULL OR so.process_technology = '')
  AND sof.field_value IS NOT NULL
  AND sof.field_value <> '';

UPDATE sales_order so
JOIN sales_order_field sof ON sof.resource_id = so.id
JOIN sys_module_field mf ON mf.id = sof.field_id
JOIN sys_module_form form ON form.id = mf.form_id AND form.form_key = 'order'
SET so.order_time = CASE
        WHEN sof.field_value REGEXP '^[0-9]+$' THEN CAST(sof.field_value AS UNSIGNED)
        ELSE UNIX_TIMESTAMP(sof.field_value) * 1000
    END
WHERE mf.internal_key = 'orderTime'
  AND so.order_time IS NULL
  AND sof.field_value IS NOT NULL
  AND sof.field_value <> '';

UPDATE sales_order so
JOIN sales_order_field sof ON sof.resource_id = so.id
JOIN sys_module_field mf ON mf.id = sof.field_id
JOIN sys_module_form form ON form.id = mf.form_id AND form.form_key = 'order'
SET so.quantity = sof.field_value
WHERE mf.internal_key = 'orderQuantity'
  AND so.quantity IS NULL
  AND sof.field_value IS NOT NULL
  AND sof.field_value <> '';

UPDATE sales_order so
JOIN sales_order_field sof ON sof.resource_id = so.id
JOIN sys_module_field mf ON mf.id = sof.field_id
JOIN sys_module_form form ON form.id = mf.form_id AND form.form_key = 'order'
SET so.unit = sof.field_value
WHERE mf.internal_key = 'orderUnit'
  AND (so.unit IS NULL OR so.unit = '')
  AND sof.field_value IS NOT NULL
  AND sof.field_value <> '';

UPDATE sales_order so
JOIN sales_order_field sof ON sof.resource_id = so.id
JOIN sys_module_field mf ON mf.id = sof.field_id
JOIN sys_module_form form ON form.id = mf.form_id AND form.form_key = 'order'
SET so.unit_price = sof.field_value
WHERE mf.internal_key = 'orderUnitPrice'
  AND so.unit_price IS NULL
  AND sof.field_value IS NOT NULL
  AND sof.field_value <> '';

UPDATE sales_order so
JOIN sales_order_field sof ON sof.resource_id = so.id
JOIN sys_module_field mf ON mf.id = sof.field_id
JOIN sys_module_form form ON form.id = mf.form_id AND form.form_key = 'order'
SET so.amount = sof.field_value
WHERE mf.internal_key = 'orderAmount'
  AND (so.amount IS NULL OR so.amount = 0)
  AND sof.field_value IS NOT NULL
  AND sof.field_value <> '';

UPDATE sales_order so
JOIN sales_order_field sof ON sof.resource_id = so.id
JOIN sys_module_field mf ON mf.id = sof.field_id
JOIN sys_module_form form ON form.id = mf.form_id AND form.form_key = 'order'
SET so.currency = sof.field_value
WHERE mf.internal_key = 'orderCurrency'
  AND (so.currency IS NULL OR so.currency = '')
  AND sof.field_value IS NOT NULL
  AND sof.field_value <> '';

DELETE sof
FROM sales_order_field sof
JOIN sys_module_field mf ON mf.id = sof.field_id
JOIN sys_module_form form ON form.id = mf.form_id AND form.form_key = 'order'
JOIN tmp_order_info_promoted_fields tmp ON mf.internal_key COLLATE utf8mb4_unicode_ci = tmp.internal_key COLLATE utf8mb4_unicode_ci;

DELETE sofb
FROM sales_order_field_blob sofb
JOIN sys_module_field mf ON mf.id = sofb.field_id
JOIN sys_module_form form ON form.id = mf.form_id AND form.form_key = 'order'
JOIN tmp_order_info_promoted_fields tmp ON mf.internal_key COLLATE utf8mb4_unicode_ci = tmp.internal_key COLLATE utf8mb4_unicode_ci;

DROP TEMPORARY TABLE IF EXISTS tmp_order_info_promoted_fields;
