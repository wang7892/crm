package wecommonitoring.repository;

import wecommonitoring.model.WeComNormalizedMessage;

import java.util.Optional;

public interface CrmIngestionRepository {
    /**
     * @return CRM 日会话主记录 id；若 CRM 写入被禁用则返回 empty
     */
    Optional<String> insertPending(String organizationId, String corpId, WeComNormalizedMessage msg);
}
