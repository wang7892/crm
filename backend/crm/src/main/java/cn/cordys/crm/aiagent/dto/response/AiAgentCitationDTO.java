package cn.cordys.crm.aiagent.dto.response;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AiAgentCitationDTO {
    private String type;
    private String module;
    private String title;
    private List<String> recordIds = new ArrayList<>();
    private String updatedAt;
}
