package cn.cordys.crm.aiagent.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiAgentRequestCancellationServiceTest {

    private final AiAgentRequestCancellationService service = new AiAgentRequestCancellationService();

    @Test
    void shouldAllowLegacyRequestsWithoutARequestId() {
        service.throwIfCancellationRequested(null);
        service.throwIfCancellationRequested("");
    }

    @Test
    void shouldOnlyCancelTheRequestForItsOwner() {
        service.register("request-1", "user-1");
        try {
            assertThat(service.cancel("request-1", "user-2")).isFalse();
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
        } finally {
            service.unregister("request-1");
        }
    }

    @Test
    void shouldCancelOnlyTheTrackedLlmRequest() {
        CompletableFuture<String> llmRequest = new CompletableFuture<>();
        service.register("request-2", "user-1");
        service.trackLlmRequest(llmRequest);
        try {
            assertThat(service.cancel("request-2", "user-1")).isTrue();
            assertThat(llmRequest.isCancelled()).isTrue();
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
            assertThatThrownBy(service::throwIfCancellationRequested)
                    .isInstanceOf(AiAgentRequestCancelledException.class);
        } finally {
            service.clearLlmRequest(llmRequest);
            service.unregister("request-2");
        }
    }

    @Test
    void shouldCancelAnLlmRequestRegisteredAfterTheStopCommand() {
        CompletableFuture<String> llmRequest = new CompletableFuture<>();
        service.register("request-3", "user-1");
        try {
            assertThat(service.cancel("request-3", "user-1")).isTrue();
            service.trackLlmRequest(llmRequest);
            assertThat(llmRequest.isCancelled()).isTrue();
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
        } finally {
            service.clearLlmRequest(llmRequest);
            service.unregister("request-3");
        }
    }
}
