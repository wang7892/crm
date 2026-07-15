package cn.cordys.crm.aiagent.dto.response;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class AiAgentAudioTranscriptionResponse {
    private String taskId;
    private String status;
    private String text;
    private String language;

    public AiAgentAudioTranscriptionResponse(String text, String language) {
        this.status = "SUCCESS";
        this.text = text;
        this.language = language;
    }

    public AiAgentAudioTranscriptionResponse(String taskId, String status, String text, String language) {
        this.taskId = taskId;
        this.status = status;
        this.text = text;
        this.language = language;
    }
}
