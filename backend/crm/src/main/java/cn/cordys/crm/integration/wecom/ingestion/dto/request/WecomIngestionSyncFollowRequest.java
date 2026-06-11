package cn.cordys.crm.integration.wecom.ingestion.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class WecomIngestionSyncFollowRequest {

    @NotEmpty
    @Schema(description = "要合并写入一条跟进记录的消息明细 id 列表")
    private List<String> eventIds;

    @Schema(description = "手工覆盖 CRM 客户 id（未传则按匹配规则解析）")
    private String customerId;

    @Schema(description = "手工覆盖负责人（sys_user.id，未传则按 sys_user.wecom_id 匹配联系专员）")
    private String ownerUserId;
}
