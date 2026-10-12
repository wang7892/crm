package cn.cordys.crm.aiagent.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AiAgentConversationSnapshot {
    private int version = 1;
    private String permissionFingerprint;
    private String question;
    private String resolvedQuestion;
    private String mode;
    private AiAgentQueryContinuation continuation;
    private List<AiAgentQueryEvidence> datasets = new ArrayList<>();
}
