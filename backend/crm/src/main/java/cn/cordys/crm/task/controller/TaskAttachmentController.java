package cn.cordys.crm.task.controller;

import cn.cordys.common.constants.PermissionConstants;
import cn.cordys.context.OrganizationContext;
import cn.cordys.crm.task.constants.TaskAttachmentScene;
import cn.cordys.crm.task.dto.response.TaskAttachmentResponse;
import cn.cordys.crm.task.service.TaskAttachmentService;
import cn.cordys.crm.task.service.TaskPermissionService;
import cn.cordys.security.SessionUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.apache.shiro.authz.annotation.Logical;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Tag(name = "任务附件")
@RestController
@RequestMapping("/task/attachment")
public class TaskAttachmentController {

    @Resource
    private TaskAttachmentService taskAttachmentService;
    @Resource
    private TaskPermissionService taskPermissionService;

    @PostMapping("/upload/{taskId}/{scene}")
    @RequiresPermissions(value = {PermissionConstants.TASK_UPDATE, PermissionConstants.TASK_EXECUTE}, logical = Logical.OR)
    @Operation(summary = "上传任务或汇报附件")
    public List<TaskAttachmentResponse> upload(@PathVariable String taskId,
                                               @PathVariable String scene,
                                               @RequestParam("files") List<MultipartFile> files) {
        return taskAttachmentService.upload(taskId, TaskAttachmentScene.parse(scene), files,
                SessionUtils.getUserId(), OrganizationContext.getOrganizationId(),
                taskPermissionService.canManage(), taskPermissionService.canExecute());
    }

    @DeleteMapping("/delete/{id}")
    @RequiresPermissions(value = {PermissionConstants.TASK_UPDATE, PermissionConstants.TASK_EXECUTE}, logical = Logical.OR)
    @Operation(summary = "删除任务附件")
    public void delete(@PathVariable String id) {
        taskAttachmentService.delete(id, SessionUtils.getUserId(), OrganizationContext.getOrganizationId(),
                taskPermissionService.canManage(), taskPermissionService.canExecute());
    }

    @GetMapping("/preview/{id}")
    @RequiresPermissions(PermissionConstants.TASK_READ)
    @Operation(summary = "预览任务附件")
    public ResponseEntity<org.springframework.core.io.Resource> preview(@PathVariable String id) {
        return taskAttachmentService.resource(id, true, SessionUtils.getUserId(),
                OrganizationContext.getOrganizationId(), taskPermissionService.canManage());
    }

    @GetMapping("/download/{id}")
    @RequiresPermissions(PermissionConstants.TASK_READ)
    @Operation(summary = "下载任务附件")
    public ResponseEntity<org.springframework.core.io.Resource> download(@PathVariable String id) {
        return taskAttachmentService.resource(id, false, SessionUtils.getUserId(),
                OrganizationContext.getOrganizationId(), taskPermissionService.canManage());
    }
}
