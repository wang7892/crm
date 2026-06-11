package wecommonitoring.repository;

import wecommonitoring.model.WeComNormalizedMessage;
import wecommonitoring.util.Ids;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryWeComMessageEventRepository implements WeComMessageEventRepository {
    private final Set<String> keys = ConcurrentHashMap.newKeySet();

    private static String dedupeKey(String corpId, WeComNormalizedMessage msg) {
        return corpId + "|" + msg.getWecomMsgId();
    }

    @Override
    public String insertIfAbsent(String organizationId, String corpId, WeComNormalizedMessage msg) {
        String k = dedupeKey(corpId, msg);
        if (!keys.add(k)) {
            return null;
        }
        return Ids.newId();
    }

    @Override
    public void updateCrmIngestionId(String eventRowId, String crmIngestionId) {
        // in-memory: no table to update
    }
}
