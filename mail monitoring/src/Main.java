import mailmonitoring.AppConfig;
import mailmonitoring.client.CrmClient;
import mailmonitoring.client.HttpCrmClient;
import mailmonitoring.client.ImapMailProviderClient;
import mailmonitoring.client.MailProviderClient;
import mailmonitoring.client.MockCrmClient;
import mailmonitoring.client.MockMailProviderClient;
import mailmonitoring.repository.InMemoryMailEventRepository;
import mailmonitoring.repository.InMemorySyncCursorRepository;
import mailmonitoring.repository.CrmAttachmentRepository;
import mailmonitoring.repository.JdbcCrmAttachmentRepository;
import mailmonitoring.repository.JdbcMailEventRepository;
import mailmonitoring.repository.MailEventRepository;
import mailmonitoring.repository.NoopCrmAttachmentRepository;
import mailmonitoring.repository.SyncCursorRepository;
import mailmonitoring.service.MailMonitorService;
import mailmonitoring.service.RetryPolicy;
import mailmonitoring.http.AttachmentHttpServer;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Main {
    public static void main(String[] args) {
        AppConfig config = AppConfig.fromEnv();
        MailProviderClient mailProviderClient;
        if (config.isUseImap()
                && config.getImapUser() != null && !config.getImapUser().isBlank()
                && config.getImapAuthCode() != null && !config.getImapAuthCode().isBlank()) {
            mailProviderClient = new ImapMailProviderClient(
                    config.getOrganizationId(),
                    config.getSourceMailbox(),
                    config.getTargetMailbox(),
                    config.getImapHost(),
                    config.getImapPort(),
                    config.getImapUser(),
                    config.getImapAuthCode(),
                    config.getImapFolder(),
                    config.getAttachmentSaveDir()
            );
            System.out.printf("[BOOT] use IMAP provider. host=%s, folder=%s, user=%s%n",
                    config.getImapHost(), config.getImapFolder(), config.getImapUser());
        } else {
            mailProviderClient = new MockMailProviderClient(
                    config.getOrganizationId(),
                    config.getSourceMailbox(),
                    config.getTargetMailbox()
            );
            System.out.println("[BOOT] use mock mail provider");
        }

        CrmClient crmClient;
        if (config.getCrmBaseUrl() != null && !config.getCrmBaseUrl().isBlank()) {
            crmClient = new HttpCrmClient(
                    config.getCrmBaseUrl(),
                    config.getCrmOrganizationId(),
                    config.getCrmAccessKey(),
                    config.getCrmSecretKey(),
                    config.getCrmConnectTimeoutMs(),
                    config.getCrmReadTimeoutMs(),
                    config.getCrmRetryMaxAttempts(),
                    config.getCrmRetryBackoffMs(),
                    config.getCrmWebhookPath()
            );
            System.out.println("[BOOT] use webhook endpoint: " + config.getCrmBaseUrl() + config.getCrmWebhookPath());
        } else {
            crmClient = new MockCrmClient();
            System.out.println("[BOOT] use mock CRM client");
        }

        MailEventRepository mailEventRepository;
        if (config.isDbEnabled()) {
            mailEventRepository = new JdbcMailEventRepository(config.getDbJdbcUrl(), config.getDbUsername(), config.getDbPassword());
            System.out.println("[BOOT] use JDBC mail repository: " + config.getDbJdbcUrl());
        } else {
            mailEventRepository = new InMemoryMailEventRepository();
            System.out.println("[BOOT] use in-memory mail repository");
        }

        SyncCursorRepository syncCursorRepository = new InMemorySyncCursorRepository();
        CrmAttachmentRepository crmAttachmentRepository;
        if (config.isCrmAttachmentDbEnabled()) {
            crmAttachmentRepository = new JdbcCrmAttachmentRepository(
                    config.getCrmAttachmentDbJdbcUrl(),
                    config.getCrmAttachmentDbUsername(),
                    config.getCrmAttachmentDbPassword()
            );
            System.out.println("[BOOT] CRM attachment direct-write enabled: " + config.getCrmAttachmentDbJdbcUrl());
        } else {
            crmAttachmentRepository = new NoopCrmAttachmentRepository();
            System.out.println("[BOOT] CRM attachment direct-write disabled");
        }

        final AttachmentHttpServer[] attachmentServerRef = new AttachmentHttpServer[1];
        if (config.isAttachmentHttpEnabled()) {
            try {
                attachmentServerRef[0] = new AttachmentHttpServer(
                        config.getAttachmentHttpPort(),
                        config.getAttachmentDownloadPath(),
                        config.getAttachmentSaveDir()
                );
                attachmentServerRef[0].start();
                System.out.printf("[BOOT] attachment server started. urlBase=%s, path=%s%n",
                        config.getAttachmentPublicBaseUrl(), config.getAttachmentDownloadPath());
            } catch (Exception ex) {
                System.err.println("[BOOT] attachment server start failed: " + ex.getMessage());
            }
        }

        MailMonitorService monitorService = new MailMonitorService(
                config.getOrganizationId(),
                config.getSourceMailbox(),
                config.getTargetMailboxes(),
                mailProviderClient,
                crmClient,
                mailEventRepository,
                syncCursorRepository,
                crmAttachmentRepository,
                new RetryPolicy(),
                config.getAttachmentPublicBaseUrl(),
                config.getAttachmentDownloadPath(),
                config.getAttachmentSaveDir()
        );

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(
                () -> {
                    try {
                        monitorService.pollOnce();
                    } catch (Exception ex) {
                        System.err.println("[ERROR] Monitor task failed: " + ex.getMessage());
                        ex.printStackTrace(System.err);
                    }
                },
                0,
                config.getPollSeconds(),
                TimeUnit.SECONDS
        );

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            scheduler.shutdown();
            if (attachmentServerRef[0] != null) {
                attachmentServerRef[0].stop(0);
            }
            System.out.println("[BOOT] monitor stopped");
        }));

        System.out.printf("[BOOT] monitor started. organizationId=%s, source=%s, targets=%s%n",
                config.getOrganizationId(), config.getSourceMailbox(), String.join(",", config.getTargetMailboxes()));
    }
}