package cn.cordys.crm.aiagent.service;

import cn.cordys.crm.aiagent.dto.ParsedAiAgentQuestion;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class AiAgentIntentValidator {

    private static final Set<String> ALLOWED_INTENTS = Set.of(
            "CUSTOMER_CONTRACT_STATUS_LIST",
            "CUSTOMER_NEW_ORDER_CHECK",
            "CUSTOMER_ACTIVE_ORDER_LIST",
            "SALES_CUSTOMER_NEW_ORDER_CHECK",
            "SALES_CUSTOMER_ACTIVE_ORDER_LIST",
            "SALES_RECENT_ORDER_LIST",
            "CUSTOMER_NAME_SEARCH",
            "CUSTOMER_SUMMARY",
            "SPECIALIST_CUSTOMER_LIST",
            "CUSTOMER_OWNER_LOOKUP",
            "CUSTOMER_CONTACT_LOOKUP",
            "CUSTOMER_LAST_FOLLOW_LOOKUP",
            "CUSTOMER_REMARK_LOOKUP",
            "CUSTOMER_REGION_LOOKUP",
            "CUSTOMER_WECOM_ID_LOOKUP",
            "CUSTOMER_ROOMID_LOOKUP",
            "CUSTOMER_FOLLOW_RECORD_LIST",
            "CUSTOMER_LAST_FOLLOWER_LOOKUP",
            "CUSTOMER_COMMUNICATION_SUMMARY",
            "SPECIALIST_COMMUNICATION_SUMMARY",
            "COMMUNICATION_SIGNAL_LIST",
            "ORDER_AMOUNT_INSIGHT",
            "ORDER_DELIVERY_SIGNAL_LIST",
            "ORDER_STATUS_BY_NO",
            "PRODUCT_ORDER_LIST",
            "CUSTOMER_ATTENTION_SIGNAL_LIST",
            "LOW_COMMUNICATION_HIGH_VALUE_CUSTOMER",
            "SPECIALIST_STALE_FOLLOW_CUSTOMER_LIST",
            "STALE_FOLLOW_CUSTOMER_LIST",
            "THIS_WEEK_NOT_FOLLOWED_CUSTOMER_LIST",
            "LEAST_FOLLOW_CUSTOMER_LIST",
            "NO_FOLLOW_RECORD_CUSTOMER_LIST",
            "RECENT_CUSTOMER_LIST",
            "VISIBLE_CUSTOMER_LIST",
            "CRM_DATABASE_QUERY"
    );

    public boolean isAllowed(String intent) {
        return StringUtils.isNotBlank(intent) && ALLOWED_INTENTS.contains(intent);
    }

    public boolean isExecutable(ParsedAiAgentQuestion question) {
        if (question == null) {
            return false;
        }
        if (question.isNeedClarification()) {
            return true;
        }
        if (question.isSqlRequired()) {
            return StringUtils.isNotBlank(question.getCandidateSql());
        }
        return isAllowed(question.getIntent());
    }

    public Set<String> allowedIntents() {
        return ALLOWED_INTENTS;
    }
}
