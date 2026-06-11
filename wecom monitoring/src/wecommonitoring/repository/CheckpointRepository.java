package wecommonitoring.repository;

public interface CheckpointRepository {
    long getLastSeq(String corpId, String checkpointType);

    void saveLastSeq(String corpId, String checkpointType, long lastSeq);
}
