package cn.cordys.crm.aiagent.dto.internal;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ExternalOrderQueryResult {
    private boolean configured;
    private boolean dateFilterAvailable;
    private List<ExternalOrderRow> rows = new ArrayList<>();
    private List<String> searchedCustomers = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();
}
