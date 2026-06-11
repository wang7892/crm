import wecommonitoring.AppConfig;
import wecommonitoring.client.ArchivePullClient;
import wecommonitoring.client.FinanceSdkBridge;
import wecommonitoring.client.WxChatDataPullHttpClient;
import wecommonitoring.repository.CheckpointRepository;
import wecommonitoring.repository.CrmIngestionRepository;
import wecommonitoring.repository.InMemoryCheckpointRepository;
import wecommonitoring.repository.InMemoryWeComMessageEventRepository;
import wecommonitoring.repository.JdbcCheckpointRepository;
import wecommonitoring.repository.JdbcCrmIngestionRepository;
import wecommonitoring.repository.JdbcWeComMediaRepository;
import wecommonitoring.repository.JdbcMonitoredUserRepository;
import wecommonitoring.repository.JdbcRawMessageRepository;
import wecommonitoring.repository.JdbcWeComMessageEventRepository;
import wecommonitoring.repository.NoopCrmIngestionRepository;
import wecommonitoring.repository.NoopRawMessageRepository;
import wecommonitoring.repository.RawMessageRepository;
import wecommonitoring.repository.WeComMessageEventRepository;
import wecommonitoring.service.WeComMediaLandingService;
import wecommonitoring.service.WeComMonitorService;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Main {
    public static void main(String[] args) {
        AppConfig config = AppConfig.fromEnv();

        CheckpointRepository checkpointRepository;
        WeComMessageEventRepository messageEventRepository;
        RawMessageRepository rawMessageRepository;
        if (config.isDbEnabled()) {
            checkpointRepository = new JdbcCheckpointRepository(config.getDbJdbcUrl(), config.getDbUsername(), config.getDbPassword());
            messageEventRepository = new JdbcWeComMessageEventRepository(config.getDbJdbcUrl(), config.getDbUsername(), config.getDbPassword());
            rawMessageRepository = new JdbcRawMessageRepository(config.getDbJdbcUrl(), config.getDbUsername(), config.getDbPassword());
            System.out.println("[BOOT] monitor DB JDBC enabled: " + config.getDbJdbcUrl());
        } else {
            checkpointRepository = new InMemoryCheckpointRepository();
            messageEventRepository = new InMemoryWeComMessageEventRepository();
            rawMessageRepository = new NoopRawMessageRepository();
            System.out.println("[BOOT] monitor DB disabled, using in-memory repositories");
        }

        CrmIngestionRepository crmIngestionRepository;
        if (config.isCrmIngestionDbEnabled()) {
            crmIngestionRepository = new JdbcCrmIngestionRepository(
                    config.getCrmIngestionJdbcUrl(),
                    config.getCrmIngestionUsername(),
                    config.getCrmIngestionPassword()
            );
            System.out.println("[BOOT] CRM ingestion JDBC enabled: " + config.getCrmIngestionJdbcUrl());
        } else {
            crmIngestionRepository = new NoopCrmIngestionRepository();
            System.out.println("[BOOT] CRM ingestion JDBC disabled (Noop)");
        }

        List<String> monitored = resolveMonitoredUserids(config);
        if (monitored.isEmpty()) {
            System.err.println("[BOOT] sys_user.wecom_id list is empty - no messages will match.");
        }

        ArchivePullClient archiveClient = buildArchiveClient(config, rawMessageRepository, monitored);
        WeComMediaLandingService mediaLandingService = buildMediaLandingService(config);
        WeComMonitorService monitorService = new WeComMonitorService(
                config.getOrganizationId(),
                config.getCorpId(),
                monitored,
                archiveClient,
                checkpointRepository,
                messageEventRepository,
                crmIngestionRepository,
                config.getWecomSeqOverlap(),
                config.getWecomMaxPagesPerPoll()
        );

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(
                () -> {
                    try {
                        monitorService.pollOnce();
                    } catch (Exception ex) {
                        System.err.println("[ERROR] wecom poll failed: " + ex.getMessage());
                        ex.printStackTrace(System.err);
                    }
                    if (mediaLandingService != null) {
                        try {
                            mediaLandingService.processPending();
                        } catch (Exception ex) {
                            System.err.println("[ERROR] wecom media landing failed: " + ex.getMessage());
                            ex.printStackTrace(System.err);
                        }
                    }
                },
                0,
                config.getPollSeconds(),
                TimeUnit.SECONDS
        );

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            scheduler.shutdown();
            System.out.println("[BOOT] wecom monitor stopped");
        }));

        System.out.printf("[BOOT] wecom monitor started. organizationId=%s, corpId=%s, pollSeconds=%d, monitoredCount=%d, seqOverlap=%d, maxPagesPerPoll=%d%n",
                config.getOrganizationId(), config.getCorpId(), config.getPollSeconds(), monitored.size(),
                config.getWecomSeqOverlap(), config.getWecomMaxPagesPerPoll());
    }

    private static List<String> resolveMonitoredUserids(AppConfig config) {
        if (!config.isCrmIngestionDbEnabled()) {
            System.err.println("[WARN] CRM ingestion DB disabled, cannot load sys_user.wecom_id.");
            return List.of();
        }
        try {
            JdbcMonitoredUserRepository repo = new JdbcMonitoredUserRepository(
                    config.getCrmIngestionJdbcUrl(), config.getCrmIngestionUsername(), config.getCrmIngestionPassword());
            List<String> fromDb = repo.listEnabledUserids();
            System.out.println("[BOOT] monitored userids loaded from sys_user.wecom_id, count=" + fromDb.size());
            return fromDb;
        } catch (Exception ex) {
            System.err.println("[WARN] load monitored users from sys_user.wecom_id failed: " + ex.getMessage());
            return List.of();
        }
    }

    private static ArchivePullClient buildArchiveClient(AppConfig config, RawMessageRepository rawMessageRepository, List<String> monitored) {
        if (config.getCorpId() == null || config.getCorpId().isBlank() || config.getCorpId().contains("xxxxxxxx")) {
            throw new IllegalStateException("WECOM_CORP_ID is empty or placeholder.");
        }
        if (config.getWecomCorpSecret() == null || config.getWecomCorpSecret().isBlank()) {
            throw new IllegalStateException("WECOM_CORP_SECRET is empty.");
        }
        return new WxChatDataPullHttpClient(
                config.getCorpId(),
                config.getWecomCorpSecret(),
                config.getWecomApiHost(),
                config.getWecomChatDataPath(),
                config.getWecomHttpConnectTimeoutMs(),
                config.getWecomHttpReadTimeoutMs(),
                config.getWecomPullLimit(),
                config.getWecomArchiveProxy(),
                config.getWecomArchiveProxyPassword(),
                config.getWecomArchiveTimeoutSeconds(),
                config.getWecomPrivateKeyPem(),
                config.getWecomPrivateKeyPath(),
                rawMessageRepository,
                monitored
        );
    }

    private static WeComMediaLandingService buildMediaLandingService(AppConfig config) {
        if (!config.isWecomMediaFetchEnabled()) {
            System.out.println("[BOOT] WECOM_MEDIA_FETCH_ENABLED=false, media landing disabled");
            return null;
        }
        if (!config.isCrmIngestionDbEnabled()) {
            System.out.println("[BOOT] CRM ingestion JDBC disabled, media landing skipped");
            return null;
        }
        FinanceSdkBridge mediaBridge = FinanceSdkBridge.create(config.getCorpId(), config.getWecomCorpSecret());
        JdbcWeComMediaRepository mediaRepository = new JdbcWeComMediaRepository(
                config.getCrmIngestionJdbcUrl(),
                config.getCrmIngestionUsername(),
                config.getCrmIngestionPassword()
        );
        System.out.printf("[BOOT] wecom media landing enabled. batchSize=%d, attachmentBaseDir=%s%n",
                config.getWecomMediaFetchBatchSize(), config.getCrmAttachmentBaseDir());
        return new WeComMediaLandingService(
                config.getOrganizationId(),
                mediaBridge,
                mediaRepository,
                config.getCrmAttachmentBaseDir(),
                config.getWecomArchiveProxy(),
                config.getWecomArchiveProxyPassword(),
                config.getWecomArchiveTimeoutSeconds(),
                config.getWecomMediaFetchBatchSize()
        );
    }
}
