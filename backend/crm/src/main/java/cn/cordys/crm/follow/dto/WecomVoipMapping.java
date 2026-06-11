package cn.cordys.crm.follow.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class WecomVoipMapping {

    @Schema(description = "Follow record ID")
    private String followRecordId;

    @Schema(description = "Send time")
    private Long sendTime;

    @Schema(description = "Message content text")
    private String contentText;

    @Schema(description = "Raw extra JSON")
    private String extraJson;
}
