UPDATE customer c
JOIN customer_field cf ON cf.resource_id = c.id AND cf.field_id = '177855551784200000'
SET c.address = cf.field_value
WHERE (c.address IS NULL OR c.address = '')
  AND cf.field_value IS NOT NULL
  AND cf.field_value <> '';

UPDATE sys_module_field
SET internal_key = 'customerAddress'
WHERE id = '177855551784200000';

DELETE cf
FROM customer_field cf
JOIN customer c ON c.id = cf.resource_id
WHERE cf.field_value IS NOT NULL
  AND cf.field_value <> ''
  AND (
      (cf.field_id IN ('177676244643300000', 'wecomExternalId') AND c.wecom_external_id = cf.field_value)
      OR (cf.field_id IN ('177898123865800000', 'roomid') AND c.roomid = cf.field_value)
      OR (cf.field_id IN ('177676248585700000', 'email') AND c.email = cf.field_value)
      OR (cf.field_id IN ('177855464453000000', 'fullName') AND c.full_name = cf.field_value)
      OR (cf.field_id IN ('177855488944900000', 'creditLimit') AND c.credit_limit = cf.field_value)
      OR (cf.field_id IN ('177855497741600000', 'customsCode') AND c.customs_code = cf.field_value)
      OR (cf.field_id IN ('177855499908200000', 'region') AND c.region = cf.field_value)
      OR (cf.field_id IN ('177855548250600000', 'phone') AND c.phone = cf.field_value)
      OR (cf.field_id IN ('177855517842000000', '177855551784200000', 'address') AND c.address = cf.field_value)
      OR (cf.field_id IN ('177855575351400000', 'remark') AND c.remark = cf.field_value)
  );
