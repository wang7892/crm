package cn.cordys.crm.integration.mls.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class MlsAgentDataSyncResult {

    @Schema(description = "同步运行 ID")
    private String runId;
    @Schema(description = "组织 ID")
    private String organizationId;
    @Schema(description = "运行状态：SUCCESS、PARTIAL、FAILED、SKIPPED_LOCKED、DISABLED、NOT_CONFIGURED、REJECTED_ORGANIZATION")
    private String status;
    @Schema(description = "触发类型")
    private String triggerType;
    private Long startTime;
    private Long endTime;

    private long customerRead;
    private long customerCreated;
    private long customerUpdated;
    private long customerSkipped;
    private long customerFailed;

    private long contractRead;
    private long contractCreated;
    private long contractUpdated;
    private long contractSkipped;
    private long contractFailed;
    private long contractDeleted;
    private long contractConflicts;

    private long orderRead;
    private long orderCreated;
    private long orderUpdated;
    private long orderSkipped;
    private long orderFailed;
    private long orderDeleted;

    private long shipmentTaskScanned;
    private long shipmentTaskCreated;
    private long shipmentTaskSkipped;
    private long shipmentTaskFailed;

    private boolean mirrorProtectionTriggered;

    private final List<String> warnings = new ArrayList<>();

    public void warning(String message) {
        if (message != null && warnings.size() < 100) {
            warnings.add(message);
        }
    }

    public void warningIf(boolean condition, String message) {
        if (condition) {
            warning(message);
        }
    }

    public boolean hasFailures() {
        return customerFailed > 0 || contractFailed > 0 || orderFailed > 0
                || contractConflicts > 0 || mirrorProtectionTriggered;
    }
}
