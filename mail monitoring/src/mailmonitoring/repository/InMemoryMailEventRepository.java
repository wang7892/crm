package mailmonitoring.repository;

import mailmonitoring.model.MailEvent;
import mailmonitoring.model.ProcessStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryMailEventRepository implements MailEventRepository {
    private final Map<String, MailEvent> store = new ConcurrentHashMap<>();

    public boolean saveIfAbsent(MailEvent event) {
        return store.putIfAbsent(event.dedupeKey(), event) == null;
    }

    public void markProcessed(MailEvent event, String followRecordId) {
        event.setProcessStatus(ProcessStatus.PROCESSED);
        event.setFollowRecordId(followRecordId);
        event.setErrorMessage(null);
        event.setNextRetryAt(null);
    }

    public void markFailed(MailEvent event, String error, Instant nextRetryAt) {
        event.setProcessStatus(ProcessStatus.FAILED);
        event.setErrorMessage(error);
        event.increaseRetryCount();
        event.setNextRetryAt(nextRetryAt);
    }

    public void markDead(MailEvent event, String error) {
        event.setProcessStatus(ProcessStatus.DEAD);
        event.setErrorMessage(error);
        event.setNextRetryAt(null);
    }

    public List<MailEvent> findRetryable(Instant now) {
        List<MailEvent> result = new ArrayList<>();
        for (MailEvent event : store.values()) {
            if (event.getProcessStatus() == ProcessStatus.FAILED
                    && event.getNextRetryAt() != null
                    && !event.getNextRetryAt().isAfter(now)) {
                result.add(event);
            }
        }
        return result;
    }
}
