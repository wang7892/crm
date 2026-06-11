DELETE FROM sys_role_permission WHERE permission_id = 'CONTRACT:APPROVAL';

DELETE FROM sys_dict_config WHERE module = 'CONTRACT_APPROVAL';

DELETE FROM sys_dict WHERE module = 'CONTRACT_APPROVAL';
