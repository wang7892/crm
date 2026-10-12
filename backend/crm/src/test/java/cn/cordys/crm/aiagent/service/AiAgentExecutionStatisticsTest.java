package cn.cordys.crm.aiagent.service;

import cn.cordys.common.util.JSON;
import cn.cordys.crm.aiagent.dto.response.AiAgentChatResponse;
import cn.cordys.crm.aiagent.dto.response.AiAgentChatResponse.ProcessDetail;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class AiAgentExecutionStatisticsTest {
    @Test
    void wholeAnswerIncludesRetrievalAndSqlTimeAndExcludesSuggestionTokens() {
        var clock = new AtomicLong();
        var response = new AiAgentChatResponse();
        try (var progress = AiAgentProgress.open(null, clock::get); var usage = AiAgentLlmUsage.begin(clock::get)) {
            AiAgentProgress.stage("knowledge", "检索术语");
            clock.set(500_000_000L);
            AiAgentProgress.stage("sql_generation", "生成 SQL");
            AiAgentLlmUsage.startCall();
            clock.set(1_500_000_000L);
            AiAgentLlmUsage.record("primary", "model", Map.of("prompt_tokens", 80, "completion_tokens", 20, "total_tokens", 100));
            AiAgentProgress.stage("sql_query", "执行 SQL");
            clock.set(2_000_000_000L);
            AiAgentProgress.stage("chart", "生成图表");
            AiAgentLlmUsage.startCall();
            clock.set(2_500_000_000L);
            AiAgentLlmUsage.record("primary", "model", Map.of("total_tokens", 30));
            AiAgentLlmUsage.finishAnswer();
            AiAgentProgress.stage("guess", "生成推荐问题");
            AiAgentLlmUsage.startCall();
            clock.set(3_000_000_000L);
            AiAgentLlmUsage.record("primary", "model", Map.of("total_tokens", 999));
            response.setProcessSteps(AiAgentProgress.finish(false));
            usage.applyTo(response);
        }
        assertThat(response.getDurationMs()).isEqualTo(2500L);
        assertThat(response.getTotalTokens()).isEqualTo(130L);
        assertThat(response.getModelCalls()).hasSize(3);
        assertThat(response.getModelCalls().stream().mapToLong(AiAgentChatResponse.ModelCall::durationMs).sum()).isEqualTo(2000L);
        assertThat(response.getProcessSteps()).extracting(AiAgentChatResponse.ProcessStep::durationMs)
                .containsExactly(500L, 1000L, 500L, 500L, 500L);
        assertThat(response.getProcessSteps()).allSatisfy(s -> assertThat(s.status()).isEqualTo("SUCCESS"));
        var restored = JSON.parseObject(JSON.toJSONString(response), AiAgentChatResponse.class);
        assertThat(restored.getTotalTokens()).isEqualTo(response.getTotalTokens());
        assertThat(restored.getDurationMs()).isEqualTo(response.getDurationMs());
        assertThat(restored.getProcessSteps()).isEqualTo(response.getProcessSteps());
    }

    @Test
    void failedAttemptAndOptionalChartFailureKeepAccurateStatusAndUsage() {
        var clock = new AtomicLong();
        var response = new AiAgentChatResponse();
        try (var progress = AiAgentProgress.open(null, clock::get); var usage = AiAgentLlmUsage.begin(clock::get)) {
            AiAgentProgress.stage("sql_generation", "生成 SQL");
            AiAgentLlmUsage.startCall();
            clock.set(10_000_000L);
            AiAgentLlmUsage.failed("primary", "failed-model");
            AiAgentLlmUsage.startCall();
            clock.set(30_000_000L);
            AiAgentLlmUsage.record("fallback", "model", Map.of("total_tokens", 42));
            AiAgentProgress.stage("chart", "生成图表");
            clock.set(40_000_000L);
            AiAgentProgress.failCurrent();
            clock.set(50_000_000L);
            AiAgentProgress.stage("guess", "推荐问题");
            response.setProcessSteps(AiAgentProgress.finish(false));
            usage.applyTo(response);
        }
        assertThat(response.getTotalTokens()).isEqualTo(42L);
        assertThat(response.getModelCalls()).extracting(AiAgentChatResponse.ModelCall::status).containsExactly("FAILED", "SUCCESS");
        assertThat(response.getProcessSteps()).extracting(AiAgentChatResponse.ProcessStep::status).containsExactly("SUCCESS", "FAILED", "SUCCESS");
        assertThat(response.getProcessSteps().get(1).durationMs()).isEqualTo(10L);
    }

    @Test
    void missingProviderUsageRemainsUnknownAndDoesNotLeakBetweenRequests() {
        var unknown = new AiAgentChatResponse();
        try (var usage = AiAgentLlmUsage.begin()) {
            AiAgentLlmUsage.startCall();
            AiAgentLlmUsage.record("primary", "model");
            usage.applyTo(unknown);
        }
        assertThat(unknown.getTotalTokens()).isNull();
        var local = new AiAgentChatResponse();
        try (var usage = AiAgentLlmUsage.begin()) { usage.applyTo(local); }
        assertThat(local.getTotalTokens()).isZero();
        assertThat(local.getModelCalls()).isEmpty();
        assertThat(AiAgentProgress.steps()).isEmpty();
        var legacy = JSON.parseObject("{\"processSteps\":[{\"name\":\"sql_query\",\"summary\":\"旧记录\"}]}", AiAgentChatResponse.class);
        assertThat(legacy.getDurationMs()).isNull();
        assertThat(legacy.getTotalTokens()).isNull();
        assertThat(legacy.getProcessSteps().getFirst().durationMs()).isNull();
    }

    @Test
    void executionFailureFinishesSqlStepAndPreservesDetailsInStreamAndHistory() {
        var clock = new AtomicLong();
        var streamed = new java.util.ArrayList<Object>();
        var details = List.of(new ProcessDetail("sql", "生成 SQL", "SELECT 1"));
        try (var progress = AiAgentProgress.open((event, data) -> streamed.add(data), clock::get)) {
            AiAgentProgress.stage("sql_query", "执行 SQL", details);
            clock.set(250_000_000L);
            AiAgentProgress.stage("query_failed", "SQL 执行失败");
            var finished = AiAgentProgress.finish(true);
            assertThat(finished.getFirst().durationMs()).isEqualTo(250L);
            assertThat(finished).allSatisfy(s -> assertThat(s.status()).isEqualTo("FAILED"));
            assertThat(((AiAgentChatResponse.ProcessStep) streamed.getFirst()).details()).isEqualTo(finished.getFirst().details());
        }
    }
}
