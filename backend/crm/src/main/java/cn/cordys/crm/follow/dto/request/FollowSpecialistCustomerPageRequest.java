package cn.cordys.crm.follow.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class FollowSpecialistCustomerPageRequest extends RecordHomePageRequest {

    @Schema(description = "联系专员ID")
    private String owner;

    @Schema(description = "客户来源")
    private String customerSource;
}
