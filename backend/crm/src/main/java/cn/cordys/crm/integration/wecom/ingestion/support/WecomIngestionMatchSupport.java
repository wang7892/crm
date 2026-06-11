package cn.cordys.crm.integration.wecom.ingestion.support;

import cn.cordys.crm.integration.wecom.ingestion.domain.WecomIngestionEvent;
import org.apache.commons.lang3.StringUtils;

/**
 * 与《企业微信监测与 CRM 匹配规则》第 3 节一致：从单条缓冲事件解析专员 userid 与客户 external_userid。
 */
public final class WecomIngestionMatchSupport {

    private WecomIngestionMatchSupport() {
    }

    public static String specialistWecomUserid(WecomIngestionEvent e) {
        if (e == null) {
            return null;
        }
        if ("INBOUND".equalsIgnoreCase(StringUtils.trimToEmpty(e.getMessageDirection()))) {
            return trimToNull(e.getPeerUserid());
        }
        return trimToNull(e.getSenderUserid());
    }

    public static String customerExternalUserid(WecomIngestionEvent e) {
        if (e == null) {
            return null;
        }
        if ("INBOUND".equalsIgnoreCase(StringUtils.trimToEmpty(e.getMessageDirection()))) {
            return trimToNull(e.getSenderExternalUserid());
        }
        String matched = trimToNull(e.getMatchedExternalUserid());
        if (matched != null) {
            return matched;
        }
        return trimToNull(e.getExternalUserid());
    }

    public static String matchRuleSummary(WecomIngestionEvent e) {
        if (e == null) {
            return "";
        }
        boolean room = StringUtils.isNotBlank(e.getRoomid());
        if ("INBOUND".equalsIgnoreCase(StringUtils.trimToEmpty(e.getMessageDirection()))) {
            if (room) {
                return "群聊·客户发言：客户 external_userid ← sender_external_userid；专员 userid ← peer_userid（优先）";
            }
            return "单聊·客户→专员：客户 external_userid ← sender_external_userid；专员 userid ← peer_userid";
        }
        if (room) {
            return "群聊·专员发言：专员 userid ← sender_userid；客户 external ← matched_external_userid 优先，否则 external_userid";
        }
        return "单聊·专员→客户：专员 userid ← sender_userid；客户 external ← matched_external_userid 优先，否则 external_userid";
    }

    public static String trimToNull(String s) {
        String t = StringUtils.trimToNull(s);
        return t;
    }
}
