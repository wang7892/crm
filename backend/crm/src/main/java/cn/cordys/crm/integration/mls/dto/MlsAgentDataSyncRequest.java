package cn.cordys.crm.integration.mls.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class MlsAgentDataSyncRequest {

    @Schema(description = "单页读取条数，默认 2000，最大 10000")
    private Integer pageSize;
}
