UPDATE sys_module_field mf
JOIN sys_module_form form ON form.id = mf.form_id AND form.form_key = 'order'
SET mf.name = '联系专员'
WHERE mf.internal_key = 'orderOwner';

UPDATE sys_module_field_blob mfb
JOIN sys_module_field mf ON mf.id = mfb.id
JOIN sys_module_form form ON form.id = mf.form_id AND form.form_key = 'order'
SET mfb.prop = JSON_SET(
        COALESCE(NULLIF(mfb.prop, ''), '{}'),
        '$.name', '联系专员'
    )
WHERE mf.internal_key = 'orderOwner';
