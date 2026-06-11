UPDATE customer c
JOIN customer_field cf ON cf.resource_id = c.id AND cf.field_id = '177898123865800000'
SET c.roomid = cf.field_value
WHERE (c.roomid IS NULL OR c.roomid = '')
  AND cf.field_value IS NOT NULL
  AND cf.field_value <> '';

DELETE cf
FROM customer_field cf
WHERE cf.field_id = '177898123865800000';

UPDATE sys_module_field
SET internal_key = 'customerRoomid'
WHERE id = '177898123865800000';
