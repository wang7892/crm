package cn.cordys.crm.aiagent.dto.response;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AiAgentChatResponse {
    private String datasourceId;
    private String datasourceName;
    private String datasourceType;
    private String sessionId;
    private String messageId;
    private String answer;
    private String reasoningContent;
    private AiAgentResultTable resultTable;
    private AiAgentResultTable predictionTable;
    private java.util.Map<String, Object> chartConfig = new java.util.LinkedHashMap<>();
    private List<String> recommendedQuestions = new ArrayList<>();
    private String queryError;
    private String queryQuestion;
    private String intent;
    private String llmProvider;
    private String llmModel;
    private Long durationMs;
    private Long totalTokens;
    private List<ModelCall> modelCalls = new ArrayList<>();
    public record ModelCall(String stage, String provider, String model, long durationMs,
                            Long promptTokens, Long completionTokens, Long totalTokens, String status) { }
    private AiAgentPageInfo pageInfo;
    private List<String> sqlQueries = new ArrayList<>();
    private List<AiAgentChart> charts = new ArrayList<>();
    private List<ProcessStep> processSteps = new ArrayList<>();
    private List<String> points = new ArrayList<>();
    private List<String> evidence = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();
    private List<AiAgentToolCallDTO> tools = new ArrayList<>();
    private List<AiAgentCitationDTO> citations = new ArrayList<>();
    public record ProcessDetail(String type, String title, String content) { }
    public record ProcessStep(String name, String summary, List<ProcessDetail> details, Long durationMs, String status) {
        public ProcessStep(String name, String summary) { this(name, summary, List.of()); }
        public ProcessStep(String name, String summary, List<ProcessDetail> details) { this(name, summary, details, null, null); }
        public ProcessStep {
            details = details == null ? List.of() : List.copyOf(details);
        }
    }
}
