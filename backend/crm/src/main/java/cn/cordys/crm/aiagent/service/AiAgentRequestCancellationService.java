package cn.cordys.crm.aiagent.service;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class AiAgentRequestCancellationService {

    private static final int MAX_REQUEST_ID_LENGTH = 128;

    private final ConcurrentMap<String, ActiveRequest> activeRequests = new ConcurrentHashMap<>();
    private final ThreadLocal<String> currentRequestId = new ThreadLocal<>();

    public void register(String requestId, String userId) {
        if (!isValidRequestId(requestId)) {
            return;
        }
        ActiveRequest activeRequest = new ActiveRequest(userId);
        if (activeRequests.putIfAbsent(requestId, activeRequest) != null) {
            throw new IllegalStateException("AI agent request id is already active");
        }
        currentRequestId.set(requestId);
    }

    public boolean cancel(String requestId, String userId) {
        if (!isValidRequestId(requestId)) {
            return false;
        }
        ActiveRequest activeRequest = activeRequests.get(requestId);
        if (activeRequest == null || !StringUtils.equals(activeRequest.userId(), userId)) {
            return false;
        }
        activeRequest.cancelled().set(true);
        CompletableFuture<?> llmRequest = activeRequest.llmRequest().get();
        if (llmRequest != null) {
            llmRequest.cancel(true);
        }
        return true;
    }

    public void throwIfCancellationRequested(String requestId) {
        ActiveRequest activeRequest = isValidRequestId(requestId) ? activeRequests.get(requestId) : null;
        if (activeRequest != null && activeRequest.cancelled().get()) {
            throw new AiAgentRequestCancelledException();
        }
    }

    public void throwIfCancellationRequested() {
        throwIfCancellationRequested(currentRequestId.get());
    }

    public <T> CompletableFuture<T> trackLlmRequest(CompletableFuture<T> llmRequest) {
        ActiveRequest activeRequest = currentActiveRequest();
        if (activeRequest == null) {
            return llmRequest;
        }
        activeRequest.llmRequest().set(llmRequest);
        if (activeRequest.cancelled().get()) {
            llmRequest.cancel(true);
        }
        return llmRequest;
    }

    public void clearLlmRequest(CompletableFuture<?> llmRequest) {
        ActiveRequest activeRequest = currentActiveRequest();
        if (activeRequest != null) {
            activeRequest.llmRequest().compareAndSet(llmRequest, null);
        }
    }

    public void unregister(String requestId) {
        try {
            if (isValidRequestId(requestId)) {
                ActiveRequest activeRequest = activeRequests.get(requestId);
                if (activeRequest != null) {
                    activeRequests.remove(requestId, activeRequest);
                }
            }
        } finally {
            currentRequestId.remove();
        }
    }

    private ActiveRequest currentActiveRequest() {
        String requestId = currentRequestId.get();
        return isValidRequestId(requestId) ? activeRequests.get(requestId) : null;
    }

    private boolean isValidRequestId(String requestId) {
        return StringUtils.isNotBlank(requestId) && requestId.length() <= MAX_REQUEST_ID_LENGTH;
    }

    private record ActiveRequest(String userId, AtomicBoolean cancelled,
                                 AtomicReference<CompletableFuture<?>> llmRequest) {
        private ActiveRequest(String userId) {
            this(userId, new AtomicBoolean(false), new AtomicReference<>());
        }
    }
}
