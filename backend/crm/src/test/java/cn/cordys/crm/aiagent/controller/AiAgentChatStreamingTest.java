package cn.cordys.crm.aiagent.controller;

import cn.cordys.common.response.handler.ResultResponseBodyAdvice;
import cn.cordys.crm.aiagent.dto.response.AiAgentChatResponse;
import cn.cordys.crm.aiagent.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AiAgentChatStreamingTest {
    private AiAgentChatService chat;
    private MockMvc mvc;

    @BeforeEach void setup() {
        chat = mock(AiAgentChatService.class);
        var controller = new AiAgentChatController();
        ReflectionTestUtils.setField(controller, "aiAgentChatService", chat);
        ReflectionTestUtils.setField(controller, "aiAgentRequestCancellationService", new AiAgentRequestCancellationService());
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ResultResponseBodyAdvice()).build();
    }

    @Test void streamsStageDeltaAndFinalResponseWithoutJsonEnvelope() throws Exception {
        when(chat.chat(any())).thenAnswer(invocation -> {
            AiAgentProgress.stage("sql_query", "查询数据", java.util.List.of(
                    new AiAgentChatResponse.ProcessDetail("sql", "SQL", "SELECT 1 AS value")));
            AiAgentProgress.delta("回答");
            var response = new AiAgentChatResponse(); response.setAnswer("回答"); response.setMessageId("message");
            response.setProcessSteps(AiAgentProgress.steps()); return response;
        });
        var body = mvc.perform(post("/ai-agent/chat/stream").contentType("application/json").accept("text/event-stream")
                        .content("{\"question\":\"测试\",\"requestId\":\"request\"}"))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/event-stream"))
                .andExpect(header().string("X-Accel-Buffering", "no")).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("event: stage", "event: delta", "event: result", "\"messageId\":\"message\"", "processSteps");
        assertThat(body).doesNotContain("\"code\":200");
        assertThat(body.split("SELECT 1 AS value", -1)).hasSize(3);
        assertThat(AiAgentProgress.streaming()).isFalse();
    }

    @Test void handlesCancellationAndErrorsAsSseNotASecondResponse() throws Exception {
        when(chat.chat(any())).thenThrow(new AiAgentRequestCancelledException());
        assertThat(mvc.perform(post("/ai-agent/chat/stream").contentType("application/json")
                .content("{\"question\":\"测试\",\"requestId\":\"request\"}")).andReturn().getResponse().getContentAsString())
                .contains("event: cancelled").doesNotContain("event: result");
        doThrow(new IllegalStateException("secret-provider-token")).when(chat).chat(any());
        assertThat(mvc.perform(post("/ai-agent/chat/stream").contentType("application/json")
                .content("{\"question\":\"测试\",\"requestId\":\"request\"}")).andReturn().getResponse().getContentAsString())
                .contains("event: error").doesNotContain("secret-provider-token");
        assertThat(AiAgentProgress.streaming()).isFalse();
    }

    @Test void nestedProgressScopeRestoresAuthenticatedOuterSink() {
        var events = new java.util.ArrayList<String>();
        try (var outer = AiAgentProgress.open((event, data) -> events.add(event))) {
            AiAgentProgress.stage("outer", "outer");
            try (var inner = AiAgentProgress.open((event, data) -> {})) {
                AiAgentProgress.stage("inner", "inner");
            }
            AiAgentProgress.send("result", Map.of());
            assertThat(AiAgentProgress.steps()).extracting(AiAgentChatResponse.ProcessStep::name).containsExactly("outer");
        }
        assertThat(events).containsExactly("stage", "result");
        assertThat(AiAgentProgress.streaming()).isFalse();
    }
}
