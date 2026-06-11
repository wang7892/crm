package cn.cordys.crm.aiagent.dto.internal;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class ExternalOrderRow {
    private String id;
    private String orderNo;
    private String productName;
    private String manager;
    private String customer;
    private String orderStatus;
    private Map<String, String> fields = new LinkedHashMap<>();
}
