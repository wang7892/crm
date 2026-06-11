package wecommonitoring.repository;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryCheckpointRepository implements CheckpointRepository {
    private final Map<String, Long> seqByKey = new ConcurrentHashMap<>();

    private static String key(String corpId, String type) {
        return corpId + "|" + type;
    }

    @Override
    public long getLastSeq(String corpId, String checkpointType) {
        return seqByKey.getOrDefault(key(corpId, checkpointType), 0L);
    }

    @Override
    public void saveLastSeq(String corpId, String checkpointType, long lastSeq) {
        seqByKey.put(key(corpId, checkpointType), lastSeq);
    }
}
