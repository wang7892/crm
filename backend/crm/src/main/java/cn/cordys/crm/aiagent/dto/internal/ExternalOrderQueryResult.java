package cn.cordys.crm.aiagent.dto.internal;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ExternalOrderQueryResult {
    private boolean configured;
    private boolean dateFilterAvailable;
    private Long totalRows;
    private long offset;
    private int limit;
    private String totalWarning;
    private AiAgentExternalOrderQuery query;
    private List<ExternalOrderRow> rows = new ArrayList<>();
    private List<String> searchedCustomers = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();
}
