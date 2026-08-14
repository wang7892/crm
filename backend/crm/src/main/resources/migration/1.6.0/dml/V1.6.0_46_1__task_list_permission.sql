-- Register the task list module for every existing organization.
INSERT INTO sys_module (id, organization_id, module_key, enable, pos, create_user, create_time, update_user, update_time)
SELECT UUID_SHORT(), module_org.organization_id, 'task', b'1', module_org.max_pos + 1,
       'admin', UNIX_TIMESTAMP() * 1000, 'admin', UNIX_TIMESTAMP() * 1000
FROM (
    SELECT organization_id, MAX(pos) AS max_pos
    FROM sys_module
    GROUP BY organization_id
) module_org
WHERE NOT EXISTS (
    SELECT 1
    FROM sys_module existing_module
    WHERE existing_module.organization_id = module_org.organization_id
      AND existing_module.module_key = 'task'
);

-- Organization administrators can manage and execute every task action.
INSERT INTO sys_role_permission (id, role_id, permission_id)
SELECT UUID_SHORT(), 'org_admin', permission_id
FROM (
    SELECT 'TASK:READ' AS permission_id
    UNION ALL SELECT 'TASK:ADD'
    UNION ALL SELECT 'TASK:UPDATE'
    UNION ALL SELECT 'TASK:DELETE'
    UNION ALL SELECT 'TASK:EXECUTE'
) permissions
WHERE NOT EXISTS (
    SELECT 1
    FROM sys_role_permission existing
    WHERE existing.role_id = 'org_admin'
      AND existing.permission_id = permissions.permission_id
);

-- Sales managers can create, edit, reassign, and delete tasks.
INSERT INTO sys_role_permission (id, role_id, permission_id)
SELECT UUID_SHORT(), 'sales_manager', permission_id
FROM (
    SELECT 'TASK:READ' AS permission_id
    UNION ALL SELECT 'TASK:ADD'
    UNION ALL SELECT 'TASK:UPDATE'
    UNION ALL SELECT 'TASK:DELETE'
) permissions
WHERE NOT EXISTS (
    SELECT 1
    FROM sys_role_permission existing
    WHERE existing.role_id = 'sales_manager'
      AND existing.permission_id = permissions.permission_id
);

-- Contact specialists can view assigned tasks and report their execution.
INSERT INTO sys_role_permission (id, role_id, permission_id)
SELECT UUID_SHORT(), 'sales_staff', permission_id
FROM (
    SELECT 'TASK:READ' AS permission_id
    UNION ALL SELECT 'TASK:EXECUTE'
) permissions
WHERE NOT EXISTS (
    SELECT 1
    FROM sys_role_permission existing
    WHERE existing.role_id = 'sales_staff'
      AND existing.permission_id = permissions.permission_id
);
