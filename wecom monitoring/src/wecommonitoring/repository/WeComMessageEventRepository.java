package wecommonitoring.repository;

import wecommonitoring.model.WeComNormalizedMessage;

/**
 * 监测侧归一化事件落库。
 */
public interface WeComMessageEventRepository {
    /**
     * @return 新插入行的 id；若已存在同幂等键则返回 null
     */
    String insertIfAbsent(String organizationId, String corpId, WeComNormalizedMessage msg);

    void updateCrmIngestionId(String eventRowId, String crmIngestionId);
}
