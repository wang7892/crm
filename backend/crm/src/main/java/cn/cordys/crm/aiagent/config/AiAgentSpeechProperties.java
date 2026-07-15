package cn.cordys.crm.aiagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "crm.ai-agent.speech")
public class AiAgentSpeechProperties {
    private Boolean enabled;
    private String provider = "openai";
    private String baseUrl;
    private String transcriptionsPath;
    private String apiKey;
    private String model = "whisper-1";
    private int timeoutSeconds = 120;
    private long maxFileSizeMb = 25;
    private Baidu baidu = new Baidu();

    @Data
    public static class Baidu {
        private String tokenUrl = "https://aip.baidubce.com/oauth/2.0/token";
        private String serverUrl = "https://vop.baidu.com/server_api";
        private String createUrl = "https://aip.baidubce.com/rpc/2.0/aasr/v1/create";
        private String queryUrl = "https://aip.baidubce.com/rpc/2.0/aasr/v1/query";
        private String mode = "long";
        private String apiKey;
        private String secretKey;
        private String accessToken;
        private String cuid = "cordys-crm";
        private int devPid = 1537;
        private int pid = 80001;
        private int rate = 16000;
        private Integer roleNum;
        private Integer smoothText = 1;
        private Integer filterSensitive;
    }
}
