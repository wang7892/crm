-- Restore the contract list entry for the built-in sales staff role.
-- The role uses SELF data scope, so contract queries remain limited to
-- contracts owned by the currently logged-in sales staff member.
INSERT INTO sys_role_permission (id, role_id, permission_id)
SELECT UUID_SHORT(), 'sales_staff', 'CONTRACT:READ'
WHERE NOT EXISTS (
    SELECT 1
    FROM sys_role_permission
    WHERE role_id = 'sales_staff'
      AND permission_id = 'CONTRACT:READ'
);
