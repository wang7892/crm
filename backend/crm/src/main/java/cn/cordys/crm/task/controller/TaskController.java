package cn.cordys.crm.task.controller;

import cn.cordys.common.constants.PermissionConstants;
import cn.cordys.common.dto.OptionDTO;
import cn.cordys.common.pager.Pager;
import cn.cordys.context.OrganizationContext;
import cn.cordys.crm.task.dto.request.TaskAddRequest;
import cn.cordys.crm.task.dto.request.TaskPageRequest;
import cn.cordys.crm.task.dto.request.TaskReassignRequest;
import cn.cordys.crm.task.dto.request.TaskReportSaveRequest;
import cn.cordys.crm.task.dto.request.TaskUpdateRequest;
import cn.cordys.crm.task.dto.response.TaskListResponse;
import cn.cordys.crm.task.service.TaskPermissionService;
import cn.cordys.crm.task.service.TaskService;
import cn.cordys.security.SessionUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "任务管理")
@RestController
@RequestMapping("/task")
public class TaskController {

    @Resource
    private TaskService taskService;
    @Resource
    private TaskPermissionService taskPermissionService;

    @PostMapping("/page")
    @RequiresPermissions(PermissionConstants.TASK_READ)
    @Operation(summary = "任务列表")
    public Pager<List<TaskListResponse>> page(@Validated @RequestBody TaskPageRequest request) {
        return taskService.page(request, SessionUtils.getUserId(), OrganizationContext.getOrganizationId(),
                taskPermissionService.canManage());
    }

    @GetMapping("/get/{id}")
    @RequiresPermissions(PermissionConstants.TASK_READ)
    @Operation(summary = "任务详情")
    public TaskListResponse get(@PathVariable String id) {
        return taskService.get(id, SessionUtils.getUserId(), OrganizationContext.getOrganizationId(),
                taskPermissionService.canManage());
    }

    @PostMapping("/add")
    @RequiresPermissions(PermissionConstants.TASK_ADD)
    @Operation(summary = "领导下发任务")
    public TaskListResponse add(@Validated @RequestBody TaskAddRequest request) {
        return taskService.add(request, SessionUtils.getUserId(), OrganizationContext.getOrganizationId());
    }

    @PostMapping("/update")
    @RequiresPermissions(PermissionConstants.TASK_UPDATE)
    @Operation(summary = "修改任务")
    public TaskListResponse update(@Validated @RequestBody TaskUpdateRequest request) {
        return taskService.update(request, SessionUtils.getUserId(), OrganizationContext.getOrganizationId());
    }

    @PostMapping("/reassign")
    @RequiresPermissions(PermissionConstants.TASK_UPDATE)
    @Operation(summary = "重新分配未完成任务")
    public TaskListResponse reassign(@Validated @RequestBody TaskReassignRequest request) {
        return taskService.reassign(request, SessionUtils.getUserId(), OrganizationContext.getOrganizationId());
    }

    @GetMapping("/delete/{id}")
    @RequiresPermissions(PermissionConstants.TASK_DELETE)
    @Operation(summary = "删除任务及其附件")
    public void delete(@PathVariable String id) {
        taskService.delete(id, OrganizationContext.getOrganizationId());
    }

    @PostMapping("/start/{id}")
    @RequiresPermissions(PermissionConstants.TASK_EXECUTE)
    @Operation(summary = "开始执行任务")
    public TaskListResponse start(@PathVariable String id) {
        return taskService.start(id, SessionUtils.getUserId(), OrganizationContext.getOrganizationId(),
                taskPermissionService.canManage());
    }

    @PostMapping("/report/save")
    @RequiresPermissions(PermissionConstants.TASK_EXECUTE)
    @Operation(summary = "保存汇报草稿")
    public TaskListResponse saveReport(@Validated @RequestBody TaskReportSaveRequest request) {
        return taskService.saveDraft(request, SessionUtils.getUserId(), OrganizationContext.getOrganizationId(),
                taskPermissionService.canManage());
    }

    @PostMapping("/report/submit")
    @RequiresPermissions(PermissionConstants.TASK_EXECUTE)
    @Operation(summary = "提交汇报并完成任务")
    public TaskListResponse submitReport(@Validated @RequestBody TaskReportSaveRequest request) {
        return taskService.submitReport(request, SessionUtils.getUserId(), OrganizationContext.getOrganizationId(),
                taskPermissionService.canManage());
    }

    @PostMapping("/ai-reply/regenerate/{id}")
    @RequiresPermissions(PermissionConstants.TASK_UPDATE)
    @Operation(summary = "重新生成 AI 建议回复")
    public TaskListResponse regenerateAiReply(@PathVariable String id) {
        return taskService.regenerateAiReply(id, SessionUtils.getUserId(), OrganizationContext.getOrganizationId());
    }

    @GetMapping("/assignee/options")
    @RequiresPermissions(PermissionConstants.TASK_ADD)
    @Operation(summary = "可执行任务的联系专员选项")
    public List<OptionDTO> assigneeOptions() {
        return taskService.assigneeOptions(OrganizationContext.getOrganizationId());
    }

    @GetMapping("/customer/options")
    @RequiresPermissions(PermissionConstants.TASK_ADD)
    @Operation(summary = "任务关联客户选项")
    public List<OptionDTO> customerOptions(@RequestParam(required = false) String keyword) {
        return taskService.customerOptions(OrganizationContext.getOrganizationId(), keyword);
    }
}
