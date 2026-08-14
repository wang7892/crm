DROP TEMPORARY TABLE IF EXISTS tmp_contract_promoted_fields;
CREATE TEMPORARY TABLE tmp_contract_promoted_fields
(
    field_name   VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
    field_id     VARCHAR(32)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
    internal_key VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
    field_type   VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL
);

INSERT INTO tmp_contract_promoted_fields (field_name, field_id, internal_key, field_type)
VALUES ('订单状态', '177980251841700000', 'orderStatus', 'INPUT'),
       ('币种', '177977881820900000', 'orderCurrency', 'INPUT');

-- Normalize the existing form fields so the UI continues to render them as
-- business fields while their values are now read from contract.
UPDATE sys_module_field mf
JOIN sys_module_form form ON form.id = mf.form_id AND form.form_key = 'contract'
JOIN tmp_contract_promoted_fields tmp
  ON mf.id = tmp.field_id
  OR mf.internal_key = tmp.internal_key
  OR mf.name = tmp.field_name
SET mf.internal_key = tmp.internal_key,
    mf.type = tmp.field_type;

UPDATE sys_module_field_blob mfb
JOIN sys_module_field mf ON mf.id = mfb.id
JOIN sys_module_form form ON form.id = mf.form_id AND form.form_key = 'contract'
JOIN tmp_contract_promoted_fields tmp
  ON mf.id = tmp.field_id
  OR mf.internal_key = tmp.internal_key
SET mfb.prop = JSON_SET(
        COALESCE(NULLIF(mfb.prop, ''), '{}'),
        '$.internalKey', tmp.internal_key,
        '$.type', tmp.field_type
    );

DROP TEMPORARY TABLE IF EXISTS tmp_contract_promoted_values;
CREATE TEMPORARY TABLE tmp_contract_promoted_values
(
    resource_id  VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
    order_status VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci,
    currency     VARCHAR(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci,
    PRIMARY KEY (resource_id)
);

INSERT INTO tmp_contract_promoted_values (resource_id, order_status, currency)
SELECT cf.resource_id,
       MAX(CASE WHEN tmp.internal_key = 'orderStatus' THEN NULLIF(TRIM(cf.field_value), '') END),
       MAX(CASE WHEN tmp.internal_key = 'orderCurrency' THEN NULLIF(TRIM(cf.field_value), '') END)
FROM contract_field cf
JOIN sys_module_field mf ON mf.id = cf.field_id
JOIN sys_module_form form ON form.id = mf.form_id AND form.form_key = 'contract'
JOIN tmp_contract_promoted_fields tmp
  ON mf.id = tmp.field_id
  OR mf.internal_key = tmp.internal_key
WHERE cf.ref_sub_id IS NULL
  AND cf.row_id IS NULL
GROUP BY cf.resource_id;

UPDATE contract c
JOIN tmp_contract_promoted_values v ON v.resource_id = c.id
SET c.order_status = COALESCE(NULLIF(c.order_status, ''), v.order_status),
    c.currency = COALESCE(NULLIF(c.currency, ''), v.currency)
WHERE v.order_status IS NOT NULL
   OR v.currency IS NOT NULL;

-- The values are now owned by contract. Remove only top-level values for the
-- two promoted fields; unrelated custom fields and sub-table values remain.
DELETE cf
FROM contract_field cf
JOIN sys_module_field mf ON mf.id = cf.field_id
JOIN sys_module_form form ON form.id = mf.form_id AND form.form_key = 'contract'
JOIN tmp_contract_promoted_fields tmp
  ON mf.id = tmp.field_id
  OR mf.internal_key = tmp.internal_key
WHERE cf.ref_sub_id IS NULL
  AND cf.row_id IS NULL;

DELETE cfb
FROM contract_field_blob cfb
JOIN sys_module_field mf ON mf.id = cfb.field_id
JOIN sys_module_form form ON form.id = mf.form_id AND form.form_key = 'contract'
JOIN tmp_contract_promoted_fields tmp
  ON mf.id = tmp.field_id
  OR mf.internal_key = tmp.internal_key
WHERE cfb.ref_sub_id IS NULL
  AND cfb.row_id IS NULL;

DROP TEMPORARY TABLE IF EXISTS tmp_contract_promoted_values;
DROP TEMPORARY TABLE IF EXISTS tmp_contract_promoted_fields;
