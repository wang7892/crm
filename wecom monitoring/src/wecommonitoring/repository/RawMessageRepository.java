package wecommonitoring.repository;

public interface RawMessageRepository {
    void insertRaw(String corpId, String wecomMsgId, long seq, String payload);
}
