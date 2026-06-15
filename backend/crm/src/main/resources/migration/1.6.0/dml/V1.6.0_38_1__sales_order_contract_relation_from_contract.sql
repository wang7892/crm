UPDATE sales_order so
LEFT JOIN contract c
  ON c.number = so.order_no
 AND c.organization_id = so.organization_id
SET so.contract_id = c.id,
    so.customer_id = c.customer_id,
    so.owner = NULLIF(TRIM(c.owner), '')
WHERE NOT (so.contract_id <=> c.id
           AND so.customer_id <=> c.customer_id
           AND so.owner <=> NULLIF(TRIM(c.owner), ''));
