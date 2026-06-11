package cn.cordys.crm.follow.dto.request;

import cn.cordys.common.dto.BasePageRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
public class RecordHomePageRequest extends BasePageRequest {

    @Schema(description = "跟进方式筛选: WECOM/MAIL")
    private String monitorSource;

    @Schema(description = "跟进开始时间")
    private Long startTime;

    @Schema(description = "跟进结束时间")
    private Long endTime;

    @Schema(description = "跟进方式选项值")
    private List<String> followMethodValues;
}
