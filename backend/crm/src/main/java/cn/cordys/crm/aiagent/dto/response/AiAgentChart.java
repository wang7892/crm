package cn.cordys.crm.aiagent.dto.response;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class AiAgentChart {
    private String title;
    private String type;
    private String dimension;
    private String coverage;
    private boolean partial;
    private Integer predictionStart;
    private List<String> categories = new ArrayList<>();
    private List<Series> series = new ArrayList<>();
    public record Series(String name, List<Double> values) { }
}
