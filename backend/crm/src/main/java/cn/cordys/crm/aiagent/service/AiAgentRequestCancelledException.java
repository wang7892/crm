package cn.cordys.crm.aiagent.service;

public class AiAgentRequestCancelledException extends RuntimeException {

    public AiAgentRequestCancelledException() {
        super("AI agent request cancelled", null, false, false);
    }
}
