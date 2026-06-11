package cn.cordys.crm.integration.wecom.ingestion.schedule;

import cn.cordys.crm.integration.wecom.ingestion.service.WecomIngestionService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时消费企微缓冲事件，按匹配规则自动写入客户跟进记录。
 */
@Component
@Slf4j
public class WecomIngestionAutoFollowScheduler {

    @Resource
    private WecomIngestionService wecomIngestionService;

    @Value("${crm.wecom.auto-create-follow:true}")
    private boolean autoCreateFollow;

    @Scheduled(fixedDelayString = "${crm.wecom.auto-follow-poll-ms:60000}")
    public void consumePendingWecomEvents() {
        if (!autoCreateFollow) {
            return;
        }
        try {
            wecomIngestionService.consumePendingAutoFollow();
        } catch (Exception ex) {
            log.warn("wecom auto-follow batch failed: {}", ex.getMessage());
        }
    }
}
