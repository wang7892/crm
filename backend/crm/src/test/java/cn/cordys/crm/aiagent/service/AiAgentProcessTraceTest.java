package cn.cordys.crm.aiagent.service;

import cn.cordys.common.util.JSON;
import cn.cordys.crm.aiagent.dto.knowledge.AiAgentQueryKnowledge.Item;
import cn.cordys.crm.aiagent.dto.knowledge.AiAgentQueryKnowledge.Matches;
import cn.cordys.crm.aiagent.dto.response.AiAgentChatResponse;
import cn.cordys.crm.aiagent.dto.response.AiAgentChatResponse.ProcessDetail;
import cn.cordys.crm.aiagent.dto.response.AiAgentChatResponse.ProcessStep;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiAgentProcessTraceTest {
    @Test
    void ordinaryCaptureDoesNotEnableStreamingAndDoesNotLeakToTheNextRequest() {
        try (var scope = AiAgentProgress.captureIfAbsent()) {
            assertThat(AiAgentProgress.streaming()).isFalse();
            AiAgentProgress.stage("query_result", "查询成功", List.of(new ProcessDetail("text", "条数", "2")));
            assertThat(AiAgentProgress.steps()).hasSize(1);
            try (var nested = AiAgentProgress.captureIfAbsent()) {
                assertThat(nested).isNull();
                AiAgentProgress.stage("chart", "图表生成");
            }
            assertThat(AiAgentProgress.steps()).hasSize(2);
        }
        assertThat(AiAgentProgress.steps()).isEmpty();
        assertThat(AiAgentProgress.streaming()).isFalse();
    }

    @Test
    void streamingAndSavedStepsCarryIdenticalDetails() {
        var events = new ArrayList<Object>();
        try (var scope = AiAgentProgress.open((event, data) -> { if ("stage".equals(event)) events.add(data); });
             var capture = AiAgentProgress.captureIfAbsent()) {
            assertThat(capture).isNull();
            assertThat(AiAgentProgress.streaming()).isTrue();
            AiAgentProgress.stage("sql_generated", "待校验", List.of(new ProcessDetail("sql", "SQL", "SELECT 1 AS value")));
            assertThat(events).containsExactlyElementsOf(AiAgentProgress.steps());
            var response = new AiAgentChatResponse();
            response.setProcessSteps(AiAgentProgress.steps());
            var restored = JSON.parseObject(JSON.toJSONString(response), AiAgentChatResponse.class);
            assertThat(restored.getProcessSteps()).isEqualTo(response.getProcessSteps());
        }
        var old = JSON.parseObject("{\"processSteps\":[{\"name\":\"sql_query\",\"summary\":\"旧处理记录\"}]}", AiAgentChatResponse.class);
        assertThat(old.getProcessSteps()).containsExactly(new ProcessStep("sql_query", "旧处理记录"));
    }

    @Test
    void referencesShowOnlyProvidedMatchesAndAnalysisDoesNotClaimSqlExamplesWereUsed() {
        var term = new Item(); term.setName("客户地区"); term.setSynonyms(List.of("地区"));
        term.setContent("地区为空时，从客户名称补充匹配印度、香港、义乌。");
        var example = new Item(); example.setName("印度客户");
        example.setContent("SELECT name AS name FROM customers WHERE region = '印度'");
        var matches = new Matches(List.of(term), List.of(example), true);
        try (var scope = AiAgentProgress.captureIfAbsent()) {
            AiAgentQueryKnowledgeService.traceMatches(matches, false);
            var steps = AiAgentProgress.steps();
            assertThat(steps).extracting(ProcessStep::name).containsExactly("terminology", "sql_examples", "knowledge_limit");
            assertThat(steps.getFirst().details().getFirst().content()).contains("同义词：地区", term.getContent());
            assertThat(steps.get(1).details()).containsExactly(new ProcessDetail("sql", example.getName(), example.getContent()));
        }
        try (var scope = AiAgentProgress.captureIfAbsent()) {
            AiAgentQueryKnowledgeService.traceMatches(matches, true);
            assertThat(AiAgentProgress.steps()).extracting(ProcessStep::name).containsExactly("analysis_terminology", "knowledge_limit");
        }
        try (var scope = AiAgentProgress.captureIfAbsent()) {
            AiAgentQueryKnowledgeService.traceMatches(Matches.empty(), false);
            assertThat(AiAgentProgress.steps()).allSatisfy(s -> {
                assertThat(s.summary()).startsWith("未检索到");
                assertThat(s.details()).isEmpty();
            });
        }
    }

    @Test
    void completedExtractionKeepsItsDetailsAndTimingWhenTheNextStepStarts() {
        var clock = new java.util.concurrent.atomic.AtomicLong();
        try (var progress = AiAgentProgress.open(null, clock::get)) {
            AiAgentProgress.stage("extract_keywords", "提取关键词");
            clock.set(100_000_000L);
            AiAgentProgress.completeCurrent("已提取查询关键词", List.of(new ProcessDetail("text", "提取结果", "客户,埃及")));
            var completed = AiAgentProgress.steps().getFirst();
            clock.set(500_000_000L);
            AiAgentProgress.stage("table_selection", "选择相关表");
            assertThat(AiAgentProgress.steps().getFirst()).isEqualTo(completed);
            assertThat(completed.durationMs()).isEqualTo(100L);
            assertThat(completed.details()).containsExactly(new ProcessDetail("text", "提取结果", "客户,埃及"));
        }
    }
}
