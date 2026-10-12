package cn.cordys.crm.aiagent.service;

import cn.cordys.common.util.JSON;
import cn.cordys.crm.aiagent.domain.AiAgentMessage;
import cn.cordys.crm.aiagent.dto.query.AiAgentQueryPlan;
import cn.cordys.crm.aiagent.dto.query.AiAgentQueryResult;
import cn.cordys.crm.aiagent.dto.response.AiAgentMessageResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AiAgentAnalysisEvidenceTest {
    @Test
    void capturedRowsAreMaskedAndNumericalValuesRemainExact() {
        AiAgentQueryPlan plan = new AiAgentQueryPlan();
        plan.setEntity("customer");
        plan.setQueryType("LIST");
        plan.setLimit(100);
        AiAgentQueryResult result = new AiAgentQueryResult();
        result.setPlan(plan);
        result.setEntityLabel("客户");
        result.setTimeWindowLabel("2026年");
        result.setFieldMasks(Map.of("phone", "phone", "email", "email", "secret", "sensitive"));
        result.setRows(List.of(
                Map.of("phone", "13812345678", "email", "alice@example.com", "secret", "private-token",
                        "amount", new BigDecimal("1234567890.12")),
                Map.of("amount", new BigDecimal("0.01"))));
        Map<String, Object> data = new AiAgentAnswerRenderer().analysisData(result, 1);
        String json = JSON.toJSONString(data);

        assertThat(json).doesNotContain("13812345678", "alice@example.com", "private-token");
        assertThat(json).contains("138****5678", "al***@example.com", "1234567890.12", "2026年");
        assertThat(data).containsEntry("truncated", true).containsEntry("includedRows", 1).containsEntry("returnedRows", 2);
        assertThat(data.get("coverage").toString()).contains("不代表全部");
        assertThat(result.getRows().getFirst().get("phone")).isEqualTo("13812345678");
    }

    @Test
    void internalMemoryIsNotIncludedInThePublicHistoryResponse() {
        AiAgentMessage message = new AiAgentMessage();
        message.setContent("可展示回答");
        message.setEvidenceJson(JSON.toJSONString(Map.of(
                "response", Map.of("answer", "可展示回答", "points", List.of("客户统计")),
                "conversation", Map.of("permissionFingerprint", "private", "datasets", List.of("internal rows")))));

        AiAgentMessageResponse response = ReflectionTestUtils.invokeMethod(new AiAgentAuditService(), "toMessageResponse", message);

        assertThat(response.getEvidenceJson()).contains("可展示回答", "客户统计");
        assertThat(response.getEvidenceJson()).doesNotContain("conversation", "private", "internal rows");
    }
}
