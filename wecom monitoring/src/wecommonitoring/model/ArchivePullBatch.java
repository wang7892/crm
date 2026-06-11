package wecommonitoring.model;

import java.util.Collections;
import java.util.List;

public class ArchivePullBatch {
    private final long nextSeq;
    private final List<WeComNormalizedMessage> messages;
    private final int rawCount;
    private final int decodeFailureCount;

    public ArchivePullBatch(long nextSeq, List<WeComNormalizedMessage> messages) {
        this(nextSeq, messages, messages == null ? 0 : messages.size(), 0);
    }

    public ArchivePullBatch(long nextSeq, List<WeComNormalizedMessage> messages,
                            int rawCount, int decodeFailureCount) {
        this.nextSeq = nextSeq;
        this.messages = messages == null ? List.of() : Collections.unmodifiableList(messages);
        this.rawCount = Math.max(0, rawCount);
        this.decodeFailureCount = Math.max(0, decodeFailureCount);
    }

    public long getNextSeq() {
        return nextSeq;
    }

    public List<WeComNormalizedMessage> getMessages() {
        return messages;
    }

    public int getRawCount() {
        return rawCount;
    }

    public int getDecodeFailureCount() {
        return decodeFailureCount;
    }
}
