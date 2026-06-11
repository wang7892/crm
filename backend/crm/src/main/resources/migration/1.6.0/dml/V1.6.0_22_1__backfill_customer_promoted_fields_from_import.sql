UPDATE customer c
JOIN customer_field cf ON cf.resource_id = c.id AND cf.field_id = 'wecomExternalId'
SET c.wecom_external_id = cf.field_value
WHERE (c.wecom_external_id IS NULL OR c.wecom_external_id = '')
  AND cf.field_value IS NOT NULL
  AND cf.field_value <> '';

UPDATE customer c
JOIN customer_field cf ON cf.resource_id = c.id AND cf.field_id = 'roomid'
SET c.roomid = cf.field_value
WHERE (c.roomid IS NULL OR c.roomid = '')
  AND cf.field_value IS NOT NULL
  AND cf.field_value <> '';

UPDATE customer c
JOIN customer_field cf ON cf.resource_id = c.id AND cf.field_id = 'email'
SET c.email = cf.field_value
WHERE (c.email IS NULL OR c.email = '')
  AND cf.field_value IS NOT NULL
  AND cf.field_value <> '';

UPDATE customer c
JOIN customer_field cf ON cf.resource_id = c.id AND cf.field_id = 'fullName'
SET c.full_name = cf.field_value
WHERE (c.full_name IS NULL OR c.full_name = '')
  AND cf.field_value IS NOT NULL
  AND cf.field_value <> '';

UPDATE customer c
JOIN customer_field cf ON cf.resource_id = c.id AND cf.field_id = 'creditLimit'
SET c.credit_limit = cf.field_value
WHERE (c.credit_limit IS NULL OR c.credit_limit = '')
  AND cf.field_value IS NOT NULL
  AND cf.field_value <> '';

UPDATE customer c
JOIN customer_field cf ON cf.resource_id = c.id AND cf.field_id = 'customsCode'
SET c.customs_code = cf.field_value
WHERE (c.customs_code IS NULL OR c.customs_code = '')
  AND cf.field_value IS NOT NULL
  AND cf.field_value <> '';

UPDATE customer c
JOIN customer_field cf ON cf.resource_id = c.id AND cf.field_id = 'region'
SET c.region = cf.field_value
WHERE (c.region IS NULL OR c.region = '')
  AND cf.field_value IS NOT NULL
  AND cf.field_value <> '';

UPDATE customer c
JOIN customer_field cf ON cf.resource_id = c.id AND cf.field_id = 'phone'
SET c.phone = cf.field_value
WHERE (c.phone IS NULL OR c.phone = '')
  AND cf.field_value IS NOT NULL
  AND cf.field_value <> '';

UPDATE customer c
JOIN customer_field cf ON cf.resource_id = c.id AND cf.field_id = 'address'
SET c.address = cf.field_value
WHERE (c.address IS NULL OR c.address = '')
  AND cf.field_value IS NOT NULL
  AND cf.field_value <> '';

UPDATE customer c
JOIN customer_field cf ON cf.resource_id = c.id AND cf.field_id = 'remark'
SET c.remark = cf.field_value
WHERE (c.remark IS NULL OR c.remark = '')
  AND cf.field_value IS NOT NULL
  AND cf.field_value <> '';
