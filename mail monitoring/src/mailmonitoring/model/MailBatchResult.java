package mailmonitoring.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MailBatchResult {
    private final List<MailEvent> events;
    private final String nextCursor;

    public MailBatchResult(List<MailEvent> events, String nextCursor) {
        this.events = new ArrayList<>(events == null ? Collections.emptyList() : events);
        this.nextCursor = nextCursor;
    }

    public List<MailEvent> getEvents() {
        return Collections.unmodifiableList(events);
    }

    public String getNextCursor() {
        return nextCursor;
    }
}
