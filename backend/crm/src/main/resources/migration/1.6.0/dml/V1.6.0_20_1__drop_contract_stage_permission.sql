DELETE FROM sys_role_permission WHERE permission_id = 'CONTRACT:STAGE';

DELETE suc
FROM sys_user_view_condition suc
JOIN sys_user_view su ON suc.sys_user_view_id = su.id
WHERE su.resource_type = 'CONTRACT'
  AND suc.name IN ('stage', 'voidReason', 'alreadyPayAmount', 'already_pay_amount');
