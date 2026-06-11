package wecommonitoring.repository;

public class NoopRawMessageRepository implements RawMessageRepository {
    @Override
    public void insertRaw(String corpId, String wecomMsgId, long seq, String payload) {
        // no-op
    }
}
