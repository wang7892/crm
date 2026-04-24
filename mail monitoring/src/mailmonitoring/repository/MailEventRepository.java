package mailmonitoring.repository;

import mailmonitoring.model.MailEvent;

import java.time.Instant;
import java.util.List;

public interface MailEventRepository {
    boolean saveIfAbsent(MailEvent event);

    void markProcessed(MailEvent event, String followRecordId);

    void markFailed(MailEvent event, String error, Instant nextRetryAt);

    void markDead(MailEvent event, String error);

    List<MailEvent> findRetryable(Instant now);
}
