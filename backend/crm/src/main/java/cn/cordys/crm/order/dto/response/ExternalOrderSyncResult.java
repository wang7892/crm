package cn.cordys.crm.order.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ExternalOrderSyncResult {

    @Schema(description = "外部订单数据源是否已配置")
    private boolean configured;

    @Schema(description = "本次读取外部订单条数")
    private int total;

    @Schema(description = "新增 CRM 订单条数")
    private int created;

    @Schema(description = "更新 CRM 订单条数")
    private int updated;

    @Schema(description = "跳过条数")
    private int skipped;

    @Schema(description = "External order_info id synced in this batch")
    private Long nextMinId;

    @Schema(description = "Whether more external order_info rows exist")
    private boolean hasMore;

    @Schema(description = "同步提示")
    private List<String> warnings = new ArrayList<>();

    public void increaseCreated() {
        this.created++;
    }

    public void increaseUpdated() {
        this.updated++;
    }

    public void increaseSkipped() {
        this.skipped++;
    }
}
