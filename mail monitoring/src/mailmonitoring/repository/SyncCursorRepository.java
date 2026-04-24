package mailmonitoring.repository;

public interface SyncCursorRepository {
    String getCursor(String mailbox);

    void saveCursor(String mailbox, String cursor);
}
