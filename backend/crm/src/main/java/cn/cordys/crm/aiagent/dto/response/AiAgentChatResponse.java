package cn.cordys.crm.aiagent.dto.response;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AiAgentChatResponse {
    private String sessionId;
    private String messageId;
    private String answer;
    private String intent;
    private List<String> points = new ArrayList<>();
    private List<String> evidence = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();
    private List<AiAgentToolCallDTO> tools = new ArrayList<>();
    private List<AiAgentCitationDTO> citations = new ArrayList<>();
}
