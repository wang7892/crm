UPDATE sys_role
SET data_scope = 'ALL',
    update_user = 'admin',
    update_time = UNIX_TIMESTAMP() * 1000
WHERE id = 'sales_manager'
  AND internal = 1
  AND data_scope <> 'ALL';
