package cn.cordys.crm.aiagent.service;

import cn.cordys.crm.aiagent.dto.ParsedAiAgentQuestion;
import cn.cordys.crm.aiagent.dto.query.AiAgentQueryPlan;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

@Service
public class AiAgentParsedQuestionNormalizer {

    private final AiAgentIntentValidator intentValidator;

    public AiAgentParsedQuestionNormalizer(AiAgentIntentValidator intentValidator) {
        this.intentValidator = intentValidator;
    }

    public ParsedAiAgentQuestion normalize(ParsedAiAgentQuestion parsed, String rawQuestion, String source) {
        if (parsed == null) {
            return null;
        }
        parsed.setRawQuestion(rawQuestion);
        parsed.setSource(StringUtils.defaultIfBlank(source, parsed.getSource()));
        parsed.setIntent(StringUtils.trimToNull(parsed.getIntent()));
        parsed.setCustomerName(cleanText(parsed.getCustomerName()));
        parsed.setSpecialistName(cleanText(parsed.getSpecialistName()));
        parsed.setKeyword(cleanText(parsed.getKeyword()));
        parsed.setProductName(cleanText(parsed.getProductName()));
        parsed.setOrderNo(cleanText(parsed.getOrderNo()));
        parsed.setTimeRange(cleanText(parsed.getTimeRange()));
        parsed.setCandidateSql(StringUtils.trimToNull(parsed.getCandidateSql()));
        parsed.setClarificationQuestion(StringUtils.trimToNull(parsed.getClarificationQuestion()));
        normalizeQueryPlan(parsed);
        if (parsed.getConfidence() < 0 || parsed.getConfidence() > 1) {
            parsed.setConfidence(0);
        }
        if (StringUtils.isNotBlank(parsed.getIntent()) && !intentValidator.isAllowed(parsed.getIntent())) {
            parsed.setIntent(null);
            parsed.setConfidence(0);
        }
        return parsed;
    }

    private void normalizeQueryPlan(ParsedAiAgentQuestion parsed) {
        AiAgentQueryPlan queryPlan = parsed.getQueryPlan();
        if (queryPlan == null) {
            return;
        }
        queryPlan.setIntent(StringUtils.defaultIfBlank(StringUtils.trimToNull(queryPlan.getIntent()), "CRM_DATABASE_QUERY"));
        queryPlan.setQueryType(StringUtils.trimToNull(queryPlan.getQueryType()));
        queryPlan.setEntity(cleanText(queryPlan.getEntity()));
        queryPlan.setClarificationQuestion(StringUtils.trimToNull(queryPlan.getClarificationQuestion()));
        if (StringUtils.isBlank(parsed.getIntent())) {
            parsed.setIntent(queryPlan.getIntent());
        }
        if (Boolean.TRUE.equals(queryPlan.getNeedClarification())) {
            parsed.setNeedClarification(false);
        }
    }

    private String cleanText(String value) {
        String result = StringUtils.defaultString(value)
                .replace("<", "")
                .replace(">", "")
                .replace("`", "")
                .trim();
        return StringUtils.isBlank(result) ? null : StringUtils.abbreviate(result, 128);
    }
}
