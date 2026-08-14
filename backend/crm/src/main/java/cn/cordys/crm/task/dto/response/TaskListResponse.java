package cn.cordys.crm.task.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class TaskListResponse {

    private String id;
    private String name;
    private String source;
    private String assigneeId;
    private String assigneeName;
    private String customerId;
    private String customerName;
    private String description;
    private Long deadline;

    @Schema(description = "显示状态，超时未完成时为 OVERDUE")
    private String status;

    private String reportContent;
    private String aiReply;
    private Long startedAt;
    private Long completedAt;
    private Long reportSubmittedAt;
    private Long createTime;
    private Long updateTime;
    private String createUser;
    private String updateUser;

    @Schema(description = "UNSAVED/DRAFT/SUBMITTED")
    private String reportState;

    private List<TaskAttachmentResponse> taskAttachments = new ArrayList<>();
    private List<TaskAttachmentResponse> reportAttachments = new ArrayList<>();
}
