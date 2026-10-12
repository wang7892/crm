-- Allow the built-in contact specialist role to fully manage the company knowledge base.
-- AGENT:UPDATE covers listing, upload, detail, chunks, reparse, enable/disable,
-- download, search testing, and the knowledge-base menu.
-- AGENT:DELETE covers document deletion.
INSERT INTO sys_role_permission (id, role_id, permission_id)
SELECT UUID_SHORT(), 'sales_staff', permission_id
FROM (
    SELECT 'AGENT:UPDATE' AS permission_id
    UNION ALL SELECT 'AGENT:DELETE'
) permissions
WHERE NOT EXISTS (
    SELECT 1
    FROM sys_role_permission existing
    WHERE existing.role_id = 'sales_staff'
      AND existing.permission_id = permissions.permission_id
);
