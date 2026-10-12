package cn.cordys.crm.aiagent.service;
import cn.cordys.common.util.JSON;
import cn.cordys.crm.aiagent.config.AiAgentLlmProperties;
import cn.cordys.crm.aiagent.dto.response.AiAgentChatResponse;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class AiAgentSqlBotModelUsageTest {
    @Test void eachTaskRecordsActualModelTimingAndTokensAndKeepsLastModelCompatibility() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body=JSON.toJSONString(Map.of("model","returned-model","choices",List.of(Map.of("message",Map.of("content","ok"))),
                    "usage",Map.of("prompt_tokens",12,"completion_tokens",5,"total_tokens",17))).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","application/json"); exchange.sendResponseHeaders(200,body.length);
            exchange.getResponseBody().write(body); exchange.close();
        }); server.start();
        var props=new AiAgentLlmProperties(); props.setEnabled(true); props.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort()+"/v1"); props.setModel("configured-model"); props.setMaxRetry(0);
        var llm=new AiAgentLlmClient(props,new AiAgentRequestCancellationService());
        try(var progress=AiAgentProgress.captureIfAbsent();var usage=AiAgentLlmUsage.begin()) {
            AiAgentProgress.stage("extract_keywords","keywords"); llm.chatTextQuiet("system","query",null,List.of());
            AiAgentProgress.stage("sql_generation","sql"); llm.chat("system","query");
            var response=new AiAgentChatResponse(); usage.applyTo(response);
            assertThat(response.getModelCalls()).hasSize(2);
            assertThat(response.getModelCalls()).extracting(AiAgentChatResponse.ModelCall::stage).containsExactly("extract_keywords","sql_generation");
            assertThat(response.getModelCalls()).allSatisfy(c -> { assertThat(c.model()).isEqualTo("returned-model"); assertThat(c.totalTokens()).isEqualTo(17); assertThat(c.durationMs()).isGreaterThanOrEqualTo(0); });
            assertThat(response.getLlmModel()).isEqualTo("returned-model");
            assertThat(JSON.parseObject(JSON.toJSONString(response),AiAgentChatResponse.class).getModelCalls()).hasSize(2);
        } finally { server.stop(0); }
    }
}
