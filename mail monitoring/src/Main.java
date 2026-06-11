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
import mailmonitoring.repository.DynamicMailboxAccount;
import mailmonitoring.repository.JdbcDynamicMailboxAccountRepository;
import mailmonitoring.repository.JdbcCrmAttachmentRepository;
import mailmonitoring.repository.JdbcMailEventRepository;
import mailmonitoring.repository.MailEventRepository;
import mailmonitoring.repository.NoopCrmAttachmentRepository;
import mailmonitoring.repository.SyncCursorRepository;
import mailmonitoring.service.MailMonitorService;
import mailmonitoring.service.RetryPolicy;
import mailmonitoring.http.AttachmentHttpServer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Main {
    public static void main(String[] args) {
        AppConfig config = AppConfig.fromEnv();
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

        Map<String, MonitorRuntime> runtimes = new ConcurrentHashMap<>();
        JdbcDynamicMailboxAccountRepository dynamicRepository = null;
        if (config.isMailboxDynamicLoadEnabled()) {
            dynamicRepository = new JdbcDynamicMailboxAccountRepository(
                    config.getCrmAttachmentDbJdbcUrl(),
                    config.getCrmAttachmentDbUsername(),
                    config.getCrmAttachmentDbPassword()
            );
            System.out.printf("[BOOT] dynamic mailbox load enabled. refreshSeconds=%d, customerEmailSource=customer.email%n",
                    config.getMailboxRefreshSeconds());
        } else {
            List<DynamicMailboxAccount> staticAccounts = toStaticAccounts(config);
            refreshMonitorRuntimes(staticAccounts, runtimes, config, crmClient, mailEventRepository, syncCursorRepository, crmAttachmentRepository);
            System.out.println("[BOOT] dynamic mailbox load disabled, use static config accounts");
        }

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        final JdbcDynamicMailboxAccountRepository finalDynamicRepository = dynamicRepository;
        if (config.isMailboxDynamicLoadEnabled()) {
            scheduler.scheduleAtFixedRate(
                    () -> {
                        try {
                            List<DynamicMailboxAccount> latest = finalDynamicRepository.loadMonitorableAccounts();
                            refreshMonitorRuntimes(latest, runtimes, config, crmClient, mailEventRepository, syncCursorRepository, crmAttachmentRepository);
                        } catch (Exception ex) {
                            System.err.println("[ERROR] mailbox dynamic refresh failed: " + ex.getMessage());
                        }
                    },
                    0,
                    config.getMailboxRefreshSeconds(),
                    TimeUnit.SECONDS
            );
        }
        scheduler.scheduleAtFixedRate(
                () -> {
                    try {
                        for (MonitorRuntime runtime : runtimes.values()) {
                            runtime.monitorService.pollOnce();
                        }
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

        System.out.printf("[BOOT] monitor started. organizationId=%s, accountCount=%d%n",
                config.getOrganizationId(), runtimes.size());
    }

    private static synchronized void refreshMonitorRuntimes(List<DynamicMailboxAccount> latestAccounts,
                                                            Map<String, MonitorRuntime> runtimes,
                                                            AppConfig config,
                                                            CrmClient crmClient,
                                                            MailEventRepository mailEventRepository,
                                                            SyncCursorRepository syncCursorRepository,
                                                            CrmAttachmentRepository crmAttachmentRepository) {
        Map<String, DynamicMailboxAccount> latestBySource = new ConcurrentHashMap<>();
        for (DynamicMailboxAccount account : latestAccounts) {
            latestBySource.put(account.identityKey(), account);
        }

        for (String source : new ArrayList<>(runtimes.keySet())) {
            if (!latestBySource.containsKey(source)) {
                runtimes.remove(source);
                System.out.printf("[RUNTIME] removed mailbox monitor. source=%s%n", source);
            }
        }

        for (DynamicMailboxAccount account : latestAccounts) {
            MonitorRuntime existing = runtimes.get(account.identityKey());
            if (existing != null && existing.account.equals(account)) {
                continue;
            }
            MonitorRuntime created = createRuntime(account, config, crmClient, mailEventRepository, syncCursorRepository, crmAttachmentRepository);
            runtimes.put(account.identityKey(), created);
            if (existing == null) {
                System.out.printf("[RUNTIME] added mailbox monitor. source=%s, targets=%d%n",
                        account.getSourceMailbox(), account.getTargetMailboxes().size());
            } else {
                System.out.printf("[RUNTIME] updated mailbox monitor. source=%s, targets=%d%n",
                        account.getSourceMailbox(), account.getTargetMailboxes().size());
            }
        }
    }

    private static MonitorRuntime createRuntime(DynamicMailboxAccount account,
                                                AppConfig config,
                                                CrmClient crmClient,
                                                MailEventRepository mailEventRepository,
                                                SyncCursorRepository syncCursorRepository,
                                                CrmAttachmentRepository crmAttachmentRepository) {
        MailProviderClient mailProviderClient;
        if (config.isUseImap()) {
            mailProviderClient = new ImapMailProviderClient(
                    config.getOrganizationId(),
                    account.getSourceMailbox(),
                    account.getTargetMailboxes().isEmpty() ? "" : account.getTargetMailboxes().get(0),
                    config.getImapHost(),
                    config.getImapPort(),
                    account.getImapUser(),
                    account.getImapAuthCode(),
                    config.getImapFolder(),
                    config.getAttachmentSaveDir()
            );
            System.out.printf("[BOOT] use IMAP provider. source=%s, user=%s, auth=%s%n",
                    account.getSourceMailbox(), account.getImapUser(), maskSecret(account.getImapAuthCode()));
        } else {
            mailProviderClient = new MockMailProviderClient(
                    config.getOrganizationId(),
                    account.getSourceMailbox(),
                    account.getTargetMailboxes().isEmpty() ? "" : account.getTargetMailboxes().get(0)
            );
            System.out.printf("[BOOT] use mock mail provider. source=%s%n", account.getSourceMailbox());
        }

        MailMonitorService monitorService = new MailMonitorService(
                config.getOrganizationId(),
                account.getSourceMailbox(),
                account.getTargetMailboxes(),
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
        return new MonitorRuntime(account, monitorService);
    }

    private static List<DynamicMailboxAccount> toStaticAccounts(AppConfig config) {
        List<DynamicMailboxAccount> accounts = new ArrayList<>();
        for (AppConfig.MailboxAccount mailboxAccount : config.getMailboxAccounts()) {
            List<String> targets = config.getTargetMailboxes().isEmpty() ? List.of() : config.getTargetMailboxes();
            accounts.add(new DynamicMailboxAccount(
                    mailboxAccount.getSourceMailbox(),
                    mailboxAccount.getSourceMailbox(),
                    mailboxAccount.getImapUser(),
                    mailboxAccount.getImapAuthCode(),
                    targets
            ));
        }
        return accounts;
    }

    private static String maskSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            return "***";
        }
        if (secret.length() <= 4) {
            return "****";
        }
        return secret.substring(0, 2) + "***" + secret.substring(secret.length() - 2);
    }

    private static class MonitorRuntime {
        private final DynamicMailboxAccount account;
        private final MailMonitorService monitorService;

        private MonitorRuntime(DynamicMailboxAccount account, MailMonitorService monitorService) {
            this.account = account;
            this.monitorService = monitorService;
        }
    }
}
