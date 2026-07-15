package cn.cordys.crm.aiagent.service;

import cn.cordys.crm.aiagent.config.AiAgentLlmProperties;
import cn.cordys.crm.aiagent.config.AiAgentSpeechProperties;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AiAgentAudioTranscriptionServiceTest {

    private final AiAgentAudioTranscriptionService service = new AiAgentAudioTranscriptionService(
            new AiAgentLlmProperties(), new AiAgentSpeechProperties(), null);

    @Test
    void shouldExtractNestedLongAudioResultOnlyOnce() {
        Map<String, Object> taskResult = new LinkedHashMap<>();
        taskResult.put("result", List.of("first sentence", "second sentence"));
        Map<String, Object> taskInfo = Map.of("task_result", taskResult);

        assertThat(service.extractBaiduLongAudioText(taskInfo)).isEqualTo("first sentence\nsecond sentence");
    }

    @Test
    void shouldKeepDetailedResultWhenItIsTheOnlyTranscription() {
        Map<String, Object> taskResult = new LinkedHashMap<>();
        taskResult.put("detailed_result", List.of(Map.of("sentence", "detailed transcription")));
        Map<String, Object> taskInfo = Map.of("task_result", taskResult);

        assertThat(service.extractBaiduLongAudioText(taskInfo)).isEqualTo("detailed transcription");
    }
}
