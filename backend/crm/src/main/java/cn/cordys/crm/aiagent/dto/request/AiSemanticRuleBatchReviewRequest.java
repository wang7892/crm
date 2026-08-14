package cn.cordys.crm.aiagent.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AiSemanticRuleBatchReviewRequest {
    @Valid
    @NotEmpty
    private List<Item> items = new ArrayList<>();

    @Data
    public static class Item extends AiSemanticRuleReviewRequest {
        @jakarta.validation.constraints.NotBlank
        private String chunkId;
    }
}
