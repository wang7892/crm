-- Allow the built-in contact specialist role to use the AI agent without
-- granting agent administration permissions.
INSERT INTO sys_role_permission (id, role_id, permission_id)
SELECT UUID_SHORT(), 'sales_staff', 'AGENT:READ'
WHERE NOT EXISTS (
    SELECT 1
    FROM sys_role_permission
    WHERE role_id = 'sales_staff'
      AND permission_id = 'AGENT:READ'
);
