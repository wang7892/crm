package cn.cordys.crm.aiagent.service;

import cn.cordys.crm.aiagent.config.AiAgentLlmProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AiAgentLlmClientCancellationTest {

    @Test
    void shouldCancelOnlyTheLlmHttpRequestWithoutInterruptingTheChatThread() throws Exception {
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                requestStarted.countDown();
                releaseResponse.await(5, TimeUnit.SECONDS);
                byte[] response = "{\"choices\":[{\"message\":{\"content\":\"done\"}}]}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } catch (Exception ignored) {
                // The client closes the exchange when cancellation succeeds.
            } finally {
                exchange.close();
            }
        });
        server.start();

        AiAgentLlmProperties properties = new AiAgentLlmProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setModel("test-model");
        properties.setMaxRetry(0);
        properties.setTimeoutSeconds(10);
        AiAgentRequestCancellationService cancellationService = new AiAgentRequestCancellationService();
        AiAgentLlmClient client = new AiAgentLlmClient(properties, cancellationService);

        CountDownLatch requestCancelled = new CountDownLatch(1);
        AtomicBoolean chatThreadInterrupted = new AtomicBoolean(true);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread chatThread = new Thread(() -> {
            cancellationService.register("request-1", "user-1");
            try {
                client.chatText("system", "question", "primary");
                failure.set(new AssertionError("LLM request was not cancelled"));
            } catch (AiAgentRequestCancelledException ignored) {
                requestCancelled.countDown();
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                chatThreadInterrupted.set(Thread.currentThread().isInterrupted());
                cancellationService.unregister("request-1");
            }
        });

        try {
            chatThread.start();
            assertThat(requestStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(cancellationService.cancel("request-1", "user-1")).isTrue();
            assertThat(requestCancelled.await(2, TimeUnit.SECONDS)).isTrue();
            chatThread.join(2_000);

            assertThat(chatThread.isAlive()).isFalse();
            assertThat(chatThreadInterrupted.get()).isFalse();
            assertThat(failure.get()).isNull();
        } finally {
            releaseResponse.countDown();
            server.stop(0);
        }
    }
}
