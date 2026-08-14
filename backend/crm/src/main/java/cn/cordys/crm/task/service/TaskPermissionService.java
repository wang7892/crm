package cn.cordys.crm.task.service;

import cn.cordys.common.constants.PermissionConstants;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;
import org.springframework.stereotype.Service;

@Service
public class TaskPermissionService {

    public boolean canManage() {
        Subject subject = SecurityUtils.getSubject();
        return subject.isPermitted(PermissionConstants.TASK_ADD)
                || subject.isPermitted(PermissionConstants.TASK_UPDATE)
                || subject.isPermitted(PermissionConstants.TASK_DELETE);
    }

    public boolean canExecute() {
        return SecurityUtils.getSubject().isPermitted(PermissionConstants.TASK_EXECUTE);
    }
}
