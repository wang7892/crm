package mailmonitoring.repository;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemorySyncCursorRepository implements SyncCursorRepository {
    private final Map<String, String> cursorStore = new ConcurrentHashMap<>();

    public String getCursor(String mailbox) {
        return cursorStore.get(mailbox);
    }

    public void saveCursor(String mailbox, String cursor) {
        if (cursor != null) {
            cursorStore.put(mailbox, cursor);
        }
    }
}
