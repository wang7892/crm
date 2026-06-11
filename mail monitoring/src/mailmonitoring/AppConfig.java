package mailmonitoring;

import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

public class AppConfig {
    private final String organizationId;
    private final List<MailboxAccount> mailboxAccounts;
    private final List<String> targetMailboxes;
    private final int pollSeconds;
    private final String crmBaseUrl;
    private final String crmOrganizationId;
    private final String crmAccessKey;
    private final String crmSecretKey;
    private final int crmConnectTimeoutMs;
    private final int crmReadTimeoutMs;
    private final int crmRetryMaxAttempts;
    private final int crmRetryBackoffMs;
    private final String crmWebhookPath;
    private final boolean useImap;
    private final String imapHost;
    private final int imapPort;
    private final String imapUser;
    private final String imapAuthCode;
    private final String imapFolder;
    private final String attachmentSaveDir;
    private final boolean dbEnabled;
    private final String dbJdbcUrl;
    private final String dbUsername;
    private final String dbPassword;
    private final boolean crmAttachmentDbEnabled;
    private final String crmAttachmentDbJdbcUrl;
    private final String crmAttachmentDbUsername;
    private final String crmAttachmentDbPassword;

    private final boolean attachmentHttpEnabled;
    private final int attachmentHttpPort;
    private final String attachmentPublicBaseUrl;
    private final String attachmentDownloadPath;
    private final boolean mailboxDynamicLoadEnabled;
    private final int mailboxRefreshSeconds;

    private AppConfig(String organizationId, List<MailboxAccount> mailboxAccounts, List<String> targetMailboxes,
                      int pollSeconds, String crmBaseUrl, String crmOrganizationId,
                      String crmAccessKey, String crmSecretKey,
                      int crmConnectTimeoutMs, int crmReadTimeoutMs,
                      int crmRetryMaxAttempts, int crmRetryBackoffMs, String crmWebhookPath,
                      boolean useImap, String imapHost, int imapPort,
                      String imapUser, String imapAuthCode, String imapFolder, String attachmentSaveDir,
                      boolean dbEnabled, String dbJdbcUrl, String dbUsername, String dbPassword,
                      boolean crmAttachmentDbEnabled, String crmAttachmentDbJdbcUrl,
                      String crmAttachmentDbUsername, String crmAttachmentDbPassword,
                      boolean attachmentHttpEnabled, int attachmentHttpPort,
                      String attachmentPublicBaseUrl, String attachmentDownloadPath,
                      boolean mailboxDynamicLoadEnabled, int mailboxRefreshSeconds) {
        this.organizationId = organizationId;
        this.mailboxAccounts = new ArrayList<>(mailboxAccounts);
        this.targetMailboxes = new ArrayList<>(targetMailboxes);
        this.pollSeconds = pollSeconds;
        this.crmBaseUrl = crmBaseUrl;
        this.crmOrganizationId = crmOrganizationId;
        this.crmAccessKey = crmAccessKey;
        this.crmSecretKey = crmSecretKey;
        this.crmConnectTimeoutMs = crmConnectTimeoutMs;
        this.crmReadTimeoutMs = crmReadTimeoutMs;
        this.crmRetryMaxAttempts = crmRetryMaxAttempts;
        this.crmRetryBackoffMs = crmRetryBackoffMs;
        this.crmWebhookPath = crmWebhookPath;
        this.useImap = useImap;
        this.imapHost = imapHost;
        this.imapPort = imapPort;
        this.imapUser = imapUser;
        this.imapAuthCode = imapAuthCode;
        this.imapFolder = imapFolder;
        this.attachmentSaveDir = attachmentSaveDir;
        this.dbEnabled = dbEnabled;
        this.dbJdbcUrl = dbJdbcUrl;
        this.dbUsername = dbUsername;
        this.dbPassword = dbPassword;
        this.crmAttachmentDbEnabled = crmAttachmentDbEnabled;
        this.crmAttachmentDbJdbcUrl = crmAttachmentDbJdbcUrl;
        this.crmAttachmentDbUsername = crmAttachmentDbUsername;
        this.crmAttachmentDbPassword = crmAttachmentDbPassword;
        this.attachmentHttpEnabled = attachmentHttpEnabled;
        this.attachmentHttpPort = attachmentHttpPort;
        this.attachmentPublicBaseUrl = attachmentPublicBaseUrl;
        this.attachmentDownloadPath = attachmentDownloadPath;
        this.mailboxDynamicLoadEnabled = mailboxDynamicLoadEnabled;
        this.mailboxRefreshSeconds = mailboxRefreshSeconds;
    }

    public static AppConfig fromEnv() {
        Properties fileProps = loadFileProperties();

        String organizationId = getConfigOrDefault(fileProps, "ORGANIZATION_ID", "org001");
        String sourceMailboxRaw = getConfigOrNull(fileProps, "SOURCE_MAILBOXES");
        if (sourceMailboxRaw == null || sourceMailboxRaw.isBlank()) {
            sourceMailboxRaw = getConfigOrDefault(fileProps, "SOURCE_MAILBOX", "a@company.com");
        }
        List<String> sourceMailboxes = parseMailboxes(sourceMailboxRaw);
        String targetMailboxRaw = getConfigOrNull(fileProps, "TARGET_MAILBOXES");
        if (targetMailboxRaw == null || targetMailboxRaw.isBlank()) {
            targetMailboxRaw = getConfigOrDefault(fileProps, "TARGET_MAILBOX", "b@company.com");
        }
        List<String> targetMailboxes = parseMailboxes(targetMailboxRaw);
        int pollSeconds = Integer.parseInt(getConfigOrDefault(fileProps, "POLL_SECONDS", "10"));
        String crmBaseUrl = getConfigOrNull(fileProps, "CRM_BASE_URL");
        String crmOrganizationId = getConfigOrDefault(fileProps, "CRM_ORGANIZATION_ID", organizationId);
        String crmAccessKey = getConfigOrNull(fileProps, "CRM_ACCESS_KEY");
        String crmSecretKey = getConfigOrNull(fileProps, "CRM_SECRET_KEY");
        int crmConnectTimeoutMs = Integer.parseInt(getConfigOrDefault(fileProps, "CRM_TIMEOUT_CONNECT_MS", "3000"));
        int crmReadTimeoutMs = Integer.parseInt(getConfigOrDefault(fileProps, "CRM_TIMEOUT_READ_MS", "10000"));
        int crmRetryMaxAttempts = Integer.parseInt(getConfigOrDefault(fileProps, "CRM_RETRY_MAX_ATTEMPTS", "3"));
        int crmRetryBackoffMs = Integer.parseInt(getConfigOrDefault(fileProps, "CRM_RETRY_BACKOFF_MS", "1000"));
        String crmWebhookPath = getConfigOrDefault(fileProps, "CRM_WEBHOOK_PATH",
                getConfigOrDefault(fileProps, "CRM_FOLLOW_ADD_PATH", "/api/webhook/email-log"));
        boolean useImap = Boolean.parseBoolean(getConfigOrDefault(fileProps, "USE_IMAP", "true"));
        String imapHost = getConfigOrDefault(fileProps, "IMAP_HOST", "imap.163.com");
        int imapPort = Integer.parseInt(getConfigOrDefault(fileProps, "IMAP_PORT", "993"));
        String imapUserRaw = getConfigOrNull(fileProps, "IMAP_USERS");
        if (imapUserRaw == null || imapUserRaw.isBlank()) {
            imapUserRaw = getConfigOrNull(fileProps, "IMAP_USER");
        }
        String imapAuthCodeRaw = getConfigOrNull(fileProps, "IMAP_AUTH_CODES");
        if (imapAuthCodeRaw == null || imapAuthCodeRaw.isBlank()) {
            imapAuthCodeRaw = getConfigOrNull(fileProps, "IMAP_AUTH_CODE");
        }
        List<String> imapUsers = parseMailboxes(imapUserRaw);
        List<String> imapAuthCodes = parseSecrets(imapAuthCodeRaw);
        String imapFolder = getConfigOrDefault(fileProps, "IMAP_FOLDER", "Sent Messages");
        String attachmentSaveDir = getConfigOrDefault(fileProps, "ATTACHMENT_SAVE_DIR", "./attachments");
        boolean dbEnabled = Boolean.parseBoolean(getConfigOrDefault(fileProps, "DB_ENABLED", "false"));
        String dbJdbcUrl = getConfigOrDefault(fileProps, "DB_JDBC_URL", "jdbc:mysql://127.0.0.1:3306/cordys_crm?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8");
        String dbUsername = getConfigOrDefault(fileProps, "DB_USERNAME", "root");
        String dbPassword = getConfigOrDefault(fileProps, "DB_PASSWORD", "");
        boolean crmAttachmentDbEnabled = Boolean.parseBoolean(getConfigOrDefault(fileProps, "CRM_ATTACHMENT_DB_ENABLED", "false"));
        String crmAttachmentDbJdbcUrl = getConfigOrDefault(fileProps, "CRM_ATTACHMENT_DB_JDBC_URL", dbJdbcUrl);
        String crmAttachmentDbUsername = getConfigOrDefault(fileProps, "CRM_ATTACHMENT_DB_USERNAME", dbUsername);
        String crmAttachmentDbPassword = getConfigOrDefault(fileProps, "CRM_ATTACHMENT_DB_PASSWORD", dbPassword);

        boolean attachmentHttpEnabled = Boolean.parseBoolean(getConfigOrDefault(fileProps, "ATTACHMENT_HTTP_ENABLED", "true"));
        int attachmentHttpPort = Integer.parseInt(getConfigOrDefault(fileProps, "ATTACHMENT_HTTP_PORT", "8090"));
        String attachmentPublicBaseUrl = getConfigOrDefault(fileProps, "ATTACHMENT_PUBLIC_BASE_URL",
                "http://127.0.0.1:" + attachmentHttpPort);
        String attachmentDownloadPath = getConfigOrDefault(fileProps, "ATTACHMENT_DOWNLOAD_PATH", "/api/attachments");
        boolean mailboxDynamicLoadEnabled = Boolean.parseBoolean(
                getConfigOrDefault(fileProps, "MAILBOX_DYNAMIC_LOAD_ENABLED", "false")
        );
        int mailboxRefreshSeconds = Integer.parseInt(getConfigOrDefault(fileProps, "MAILBOX_REFRESH_SECONDS", "60"));
        List<MailboxAccount> mailboxAccounts = mailboxDynamicLoadEnabled
                ? List.of()
                : buildMailboxAccounts(sourceMailboxes, imapUsers, imapAuthCodes, useImap);
        return new AppConfig(
                organizationId, mailboxAccounts, targetMailboxes, pollSeconds,
                crmBaseUrl, crmOrganizationId, crmAccessKey, crmSecretKey,
                crmConnectTimeoutMs, crmReadTimeoutMs, crmRetryMaxAttempts, crmRetryBackoffMs, crmWebhookPath,
                useImap, imapHost, imapPort, firstOrNull(imapUsers), firstOrNull(imapAuthCodes), imapFolder, attachmentSaveDir,
                dbEnabled, dbJdbcUrl, dbUsername, dbPassword,
                crmAttachmentDbEnabled, crmAttachmentDbJdbcUrl, crmAttachmentDbUsername, crmAttachmentDbPassword,
                attachmentHttpEnabled, attachmentHttpPort, attachmentPublicBaseUrl, attachmentDownloadPath,
                mailboxDynamicLoadEnabled, mailboxRefreshSeconds
        );
    }

    private static List<String> parseMailboxes(String raw) {
        List<String> values = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return values;
        }
        String[] split = raw.split(",");
        for (String item : split) {
            String trimmed = item == null ? "" : item.trim().toLowerCase();
            if (!trimmed.isBlank()) {
                values.add(trimmed);
            }
        }
        return values;
    }

    private static List<String> parseSecrets(String raw) {
        List<String> values = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return values;
        }
        String[] split = raw.split(",");
        for (String item : split) {
            String trimmed = item == null ? "" : item.trim();
            if (!trimmed.isBlank()) {
                values.add(trimmed);
            }
        }
        return values;
    }

    private static List<MailboxAccount> buildMailboxAccounts(List<String> sourceMailboxes, List<String> imapUsers,
                                                             List<String> imapAuthCodes, boolean useImap) {
        List<MailboxAccount> accounts = new ArrayList<>();
        for (int i = 0; i < sourceMailboxes.size(); i++) {
            String sourceMailbox = sourceMailboxes.get(i);
            String imapUser = imapUsers.size() > i ? imapUsers.get(i) : null;
            String imapAuthCode = imapAuthCodes.size() > i ? imapAuthCodes.get(i) : null;
            if (useImap && (imapUser == null || imapAuthCode == null)) {
                System.out.printf("[WARN] skip mailbox due to missing IMAP credential. mailbox=%s, reason=missing_auth_code%n",
                        sourceMailbox);
                continue;
            }
            accounts.add(new MailboxAccount(sourceMailbox, imapUser, imapAuthCode));
        }
        if (accounts.isEmpty() && !sourceMailboxes.isEmpty()) {
            throw new IllegalArgumentException("No valid mailbox account configured. Check IMAP_USERS and IMAP_AUTH_CODES.");
        }
        if (accounts.isEmpty() && sourceMailboxes.isEmpty()) {
            throw new IllegalArgumentException("At least one SOURCE_MAILBOX or SOURCE_MAILBOXES item is required.");
        }
        return accounts;
    }

    private static String firstOrNull(List<String> values) {
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static String getenvOrDefault(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static String getConfigOrDefault(Properties fileProps, String key, String defaultValue) {
        String fromFile = fileProps.getProperty(key);
        if (fromFile != null && !fromFile.isBlank()) {
            return fromFile.trim();
        }
        return getenvOrDefault(key, defaultValue);
    }

    private static String getConfigOrNull(Properties fileProps, String key) {
        String fromFile = fileProps.getProperty(key);
        if (fromFile != null && !fromFile.isBlank()) {
            return fromFile.trim();
        }
        String fromEnv = System.getenv(key);
        return (fromEnv == null || fromEnv.isBlank()) ? null : fromEnv;
    }

    private static Properties loadFileProperties() {
        Properties props = new Properties();
        String[] paths = new String[]{"./config.properties", "./src/config.properties", "../config.properties"};
        for (String path : paths) {
            try (FileInputStream inputStream = new FileInputStream(path)) {
                props.load(inputStream);
                System.out.println("[BOOT] loaded config file: " + path);
                return props;
            } catch (Exception ignored) {
                // Try next candidate path.
            }
        }
        return props;
    }

    public String getOrganizationId() {
        return organizationId;
    }

    public String getSourceMailbox() {
        return mailboxAccounts.isEmpty() ? "" : mailboxAccounts.get(0).getSourceMailbox();
    }

    public String getTargetMailbox() {
        return targetMailboxes.isEmpty() ? "" : targetMailboxes.get(0);
    }

    public List<String> getSourceMailboxes() {
        List<String> values = new ArrayList<>();
        for (MailboxAccount account : mailboxAccounts) {
            values.add(account.getSourceMailbox());
        }
        return Collections.unmodifiableList(values);
    }

    public List<MailboxAccount> getMailboxAccounts() {
        return Collections.unmodifiableList(mailboxAccounts);
    }

    public List<String> getTargetMailboxes() {
        return Collections.unmodifiableList(targetMailboxes);
    }

    public int getPollSeconds() {
        return pollSeconds;
    }

    public String getCrmBaseUrl() {
        return crmBaseUrl;
    }

    public String getCrmOrganizationId() {
        return crmOrganizationId;
    }

    public String getCrmAccessKey() {
        return crmAccessKey;
    }

    public String getCrmSecretKey() {
        return crmSecretKey;
    }

    public int getCrmConnectTimeoutMs() {
        return crmConnectTimeoutMs;
    }

    public int getCrmReadTimeoutMs() {
        return crmReadTimeoutMs;
    }

    public int getCrmRetryMaxAttempts() {
        return crmRetryMaxAttempts;
    }

    public int getCrmRetryBackoffMs() {
        return crmRetryBackoffMs;
    }

    public String getCrmWebhookPath() {
        return crmWebhookPath;
    }

    public boolean isUseImap() {
        return useImap;
    }

    public String getImapHost() {
        return imapHost;
    }

    public int getImapPort() {
        return imapPort;
    }

    public String getImapUser() {
        return imapUser;
    }

    public String getImapAuthCode() {
        return imapAuthCode;
    }

    public String getImapFolder() {
        return imapFolder;
    }

    public String getAttachmentSaveDir() {
        return attachmentSaveDir;
    }

    public boolean isDbEnabled() {
        return dbEnabled;
    }

    public String getDbJdbcUrl() {
        return dbJdbcUrl;
    }

    public String getDbUsername() {
        return dbUsername;
    }

    public String getDbPassword() {
        return dbPassword;
    }

    public boolean isCrmAttachmentDbEnabled() {
        return crmAttachmentDbEnabled;
    }

    public String getCrmAttachmentDbJdbcUrl() {
        return crmAttachmentDbJdbcUrl;
    }

    public String getCrmAttachmentDbUsername() {
        return crmAttachmentDbUsername;
    }

    public String getCrmAttachmentDbPassword() {
        return crmAttachmentDbPassword;
    }

    public boolean isAttachmentHttpEnabled() {
        return attachmentHttpEnabled;
    }

    public int getAttachmentHttpPort() {
        return attachmentHttpPort;
    }

    public String getAttachmentPublicBaseUrl() {
        return attachmentPublicBaseUrl;
    }

    public String getAttachmentDownloadPath() {
        return attachmentDownloadPath;
    }

    public boolean isMailboxDynamicLoadEnabled() {
        return mailboxDynamicLoadEnabled;
    }

    public int getMailboxRefreshSeconds() {
        return mailboxRefreshSeconds;
    }

    public static class MailboxAccount {
        private final String sourceMailbox;
        private final String imapUser;
        private final String imapAuthCode;

        public MailboxAccount(String sourceMailbox, String imapUser, String imapAuthCode) {
            this.sourceMailbox = sourceMailbox;
            this.imapUser = imapUser;
            this.imapAuthCode = imapAuthCode;
        }

        public String getSourceMailbox() {
            return sourceMailbox;
        }

        public String getImapUser() {
            return imapUser;
        }

        public String getImapAuthCode() {
            return imapAuthCode;
        }
    }
}
