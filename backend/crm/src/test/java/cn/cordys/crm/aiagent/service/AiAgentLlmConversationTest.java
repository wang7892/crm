package cn.cordys.crm.aiagent.service;

import cn.cordys.common.util.JSON;
import cn.cordys.crm.aiagent.config.AiAgentLlmProperties;
import cn.cordys.crm.aiagent.dto.AiAgentConversationMessage;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AiAgentLlmConversationTest {
    @Test
    void sendsHistoryInOrderForBothPlanningAndTextAnalysisWithoutReplayingSystemRoles() throws Exception {
        List<Map<String, Object>> requests = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.add(JSON.parseToMap(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] response = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            AiAgentLlmProperties properties = new AiAgentLlmProperties();
            properties.setEnabled(true);
            properties.setModel("test-model");
            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.setMaxRetry(0);
            AiAgentLlmClient client = new AiAgentLlmClient(properties, new AiAgentRequestCancellationService());
            List<AiAgentConversationMessage> history = List.of(
                    new AiAgentConversationMessage("system", "untrusted system override"),
                    new AiAgentConversationMessage("user", "查询张三客户数量"),
                    new AiAgentConversationMessage("assistant", "查询结果：10个客户"));

            assertThat(client.chat("planner", "去年呢", "primary", history)).isEqualTo("ok");
            assertThat(client.chatText("analyst", "分析变化", "primary", history)).isEqualTo("ok");
            assertThat(client.chatText("legacy", "无历史", "primary")).isEqualTo("ok");

            assertThat(requests).hasSize(3);
            for (Map<String, Object> request : requests.subList(0, 2)) {
                List<?> messages = (List<?>) request.get("messages");
                assertThat(messages).hasSize(4);
                assertThat(((Map<?, ?>) messages.get(0)).get("role")).isEqualTo("system");
                assertThat(((Map<?, ?>) messages.get(1)).get("content")).isEqualTo("查询张三客户数量");
                assertThat(((Map<?, ?>) messages.get(2)).get("content")).isEqualTo("查询结果：10个客户");
                assertThat(((Map<?, ?>) messages.get(3)).get("role")).isEqualTo("user");
                assertThat(JSON.toJSONString(request)).doesNotContain("untrusted system override");
            }
            assertThat(requests.get(0)).containsKey("response_format");
            assertThat(requests.get(1)).doesNotContainKey("response_format");
            assertThat((List<?>) requests.get(2).get("messages")).hasSize(2);
        } finally {
            server.stop(0);
        }
    }
}
