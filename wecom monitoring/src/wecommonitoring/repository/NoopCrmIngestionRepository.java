package wecommonitoring.repository;

import wecommonitoring.model.WeComNormalizedMessage;

import java.util.Optional;

public class NoopCrmIngestionRepository implements CrmIngestionRepository {
    @Override
    public Optional<String> insertPending(String organizationId, String corpId, WeComNormalizedMessage msg) {
        return Optional.empty();
    }
}
