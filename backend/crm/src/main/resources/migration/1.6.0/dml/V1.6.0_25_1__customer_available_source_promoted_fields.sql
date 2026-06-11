UPDATE customer c
JOIN customer_field cf ON cf.resource_id = c.id
    AND cf.field_id IN ('345735959565836387', 'customerAvailable', 'customerLevel')
SET c.customer_available = CASE cf.field_value
    WHEN '177855468405000000' THEN '1'
    WHEN '177855469380100000' THEN '0'
    ELSE cf.field_value
END
WHERE (c.customer_available IS NULL OR c.customer_available = '')
  AND cf.field_value IS NOT NULL
  AND cf.field_value <> '';

UPDATE customer c
JOIN customer_field cf ON cf.resource_id = c.id
    AND cf.field_id IN ('177986537827400000', 'customerSource')
SET c.customer_source = cf.field_value
WHERE (c.customer_source IS NULL OR c.customer_source = '')
  AND cf.field_value IS NOT NULL
  AND cf.field_value <> '';

UPDATE sys_module_field
SET internal_key = 'customerAvailable'
WHERE id = '345735959565836387';

UPDATE sys_module_field_blob
SET prop = REPLACE(
        REPLACE(prop, '"value":"177855468405000000"', '"value":"1"'),
        '"value":"177855469380100000"', '"value":"0"'
    )
WHERE id = '345735959565836387';

UPDATE sys_module_field
SET internal_key = 'customerSource'
WHERE id = '177986537827400000';

UPDATE customer
SET customer_available = CASE customer_available
    WHEN '177855468405000000' THEN '1'
    WHEN '177855469380100000' THEN '0'
    ELSE customer_available
END
WHERE customer_available IN ('177855468405000000', '177855469380100000');

DELETE cf
FROM customer_field cf
JOIN customer c ON c.id = cf.resource_id
WHERE cf.field_id IN ('345735959565836387', 'customerAvailable', 'customerLevel')
  AND c.customer_available = CASE cf.field_value
      WHEN '177855468405000000' THEN '1'
      WHEN '177855469380100000' THEN '0'
      ELSE cf.field_value
  END;

DELETE cf
FROM customer_field cf
JOIN customer c ON c.id = cf.resource_id
WHERE cf.field_id IN ('177986537827400000', 'customerSource')
  AND c.customer_source = cf.field_value;
