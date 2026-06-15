UPDATE sys_role
SET data_scope = 'SELF',
    update_user = 'admin',
    update_time = UNIX_TIMESTAMP() * 1000
WHERE id = 'sales_staff'
  AND internal = 1
  AND data_scope <> 'SELF';
