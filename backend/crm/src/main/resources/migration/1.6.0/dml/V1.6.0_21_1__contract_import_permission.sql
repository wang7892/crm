-- set innodb lock wait timeout
SET SESSION innodb_lock_wait_timeout = 7200;

INSERT INTO sys_role_permission (id, role_id, permission_id)
SELECT UUID_SHORT(), 'org_admin', 'CONTRACT:IMPORT'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_role_permission WHERE role_id = 'org_admin' AND permission_id = 'CONTRACT:IMPORT'
);

INSERT INTO sys_role_permission (id, role_id, permission_id)
SELECT UUID_SHORT(), 'sales_manager', 'CONTRACT:IMPORT'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_role_permission WHERE role_id = 'sales_manager' AND permission_id = 'CONTRACT:IMPORT'
);

INSERT INTO sys_role_permission (id, role_id, permission_id)
SELECT UUID_SHORT(), 'sales_staff', 'CONTRACT:IMPORT'
WHERE NOT EXISTS (
    SELECT 1 FROM sys_role_permission WHERE role_id = 'sales_staff' AND permission_id = 'CONTRACT:IMPORT'
);

SET SESSION innodb_lock_wait_timeout = DEFAULT;
