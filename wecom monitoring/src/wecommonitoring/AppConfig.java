package wecommonitoring;

import java.io.FileInputStream;
import java.util.Properties;

public class AppConfig {
    private final String organizationId;
    private final String corpId;
    private final int pollSeconds;
    private final boolean dbEnabled;
    private final String dbJdbcUrl;
    private final String dbUsername;
    private final String dbPassword;
    private final boolean crmIngestionDbEnabled;
    private final String crmIngestionJdbcUrl;
    private final String crmIngestionUsername;
    private final String crmIngestionPassword;
    private final String wecomCorpSecret;
    private final String wecomApiHost;
    private final String wecomChatDataPath;
    private final int wecomHttpConnectTimeoutMs;
    private final int wecomHttpReadTimeoutMs;
    private final int wecomPullLimit;
    private final int wecomSeqOverlap;
    private final int wecomMaxPagesPerPoll;
    private final String wecomArchiveProxy;
    private final String wecomArchiveProxyPassword;
    private final int wecomArchiveTimeoutSeconds;
    private final boolean wecomMediaFetchEnabled;
    private final int wecomMediaFetchBatchSize;
    private final String crmAttachmentBaseDir;
    private final String wecomPrivateKeyPath;
    private final String wecomPrivateKeyPem;

    private AppConfig(String organizationId, String corpId, int pollSeconds,
                      boolean dbEnabled, String dbJdbcUrl, String dbUsername, String dbPassword,
                      boolean crmIngestionDbEnabled, String crmIngestionJdbcUrl, String crmIngestionUsername,
                      String crmIngestionPassword, String wecomCorpSecret, String wecomApiHost,
                      String wecomChatDataPath, int wecomHttpConnectTimeoutMs, int wecomHttpReadTimeoutMs,
                      int wecomPullLimit, int wecomSeqOverlap, int wecomMaxPagesPerPoll,
                      String wecomArchiveProxy, String wecomArchiveProxyPassword, int wecomArchiveTimeoutSeconds,
                      boolean wecomMediaFetchEnabled, int wecomMediaFetchBatchSize, String crmAttachmentBaseDir,
                      String wecomPrivateKeyPath, String wecomPrivateKeyPem) {
        this.organizationId = organizationId;
        this.corpId = corpId;
        this.pollSeconds = pollSeconds;
        this.dbEnabled = dbEnabled;
        this.dbJdbcUrl = dbJdbcUrl;
        this.dbUsername = dbUsername;
        this.dbPassword = dbPassword;
        this.crmIngestionDbEnabled = crmIngestionDbEnabled;
        this.crmIngestionJdbcUrl = crmIngestionJdbcUrl;
        this.crmIngestionUsername = crmIngestionUsername;
        this.crmIngestionPassword = crmIngestionPassword;
        this.wecomCorpSecret = wecomCorpSecret;
        this.wecomApiHost = wecomApiHost;
        this.wecomChatDataPath = wecomChatDataPath;
        this.wecomHttpConnectTimeoutMs = wecomHttpConnectTimeoutMs;
        this.wecomHttpReadTimeoutMs = wecomHttpReadTimeoutMs;
        this.wecomPullLimit = wecomPullLimit;
        this.wecomSeqOverlap = wecomSeqOverlap;
        this.wecomMaxPagesPerPoll = wecomMaxPagesPerPoll;
        this.wecomArchiveProxy = wecomArchiveProxy;
        this.wecomArchiveProxyPassword = wecomArchiveProxyPassword;
        this.wecomArchiveTimeoutSeconds = wecomArchiveTimeoutSeconds;
        this.wecomMediaFetchEnabled = wecomMediaFetchEnabled;
        this.wecomMediaFetchBatchSize = wecomMediaFetchBatchSize;
        this.crmAttachmentBaseDir = crmAttachmentBaseDir;
        this.wecomPrivateKeyPath = wecomPrivateKeyPath;
        this.wecomPrivateKeyPem = wecomPrivateKeyPem;
    }

    public static AppConfig fromEnv() {
        Properties fileProps = loadFileProperties();
        String organizationId = getConfigOrDefault(fileProps, "ORGANIZATION_ID", "org001");
        String corpId = getConfigOrDefault(fileProps, "WECOM_CORP_ID", "wwxxxxxxxxxxxxxxxx");
        int pollSeconds = Integer.parseInt(getConfigOrDefault(fileProps, "POLL_SECONDS", "60"));
        boolean dbEnabled = Boolean.parseBoolean(getConfigOrDefault(fileProps, "DB_ENABLED", "false"));
        String dbJdbcUrl = getConfigOrDefault(fileProps, "DB_JDBC_URL",
                "jdbc:mysql://127.0.0.1:3306/wecom_monitor?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8");
        String dbUsername = getConfigOrDefault(fileProps, "DB_USERNAME", "root");
        String dbPassword = getConfigOrDefault(fileProps, "DB_PASSWORD", "");
        boolean crmIngestionDbEnabled = Boolean.parseBoolean(getConfigOrDefault(fileProps, "CRM_INGESTION_DB_ENABLED", "false"));
        String crmIngestionJdbcUrl = getConfigOrDefault(fileProps, "CRM_INGESTION_DB_JDBC_URL", dbJdbcUrl);
        String crmIngestionUsername = getConfigOrDefault(fileProps, "CRM_INGESTION_DB_USERNAME", dbUsername);
        String crmIngestionPassword = getConfigOrDefault(fileProps, "CRM_INGESTION_DB_PASSWORD", dbPassword);
        String wecomCorpSecret = getConfigOrDefault(fileProps, "WECOM_CORP_SECRET", "");
        String wecomApiHost = getConfigOrDefault(fileProps, "WECOM_API_HOST", "https://qyapi.weixin.qq.com");
        String wecomChatDataPath = getConfigOrDefault(fileProps, "WECOM_CHATDATA_PATH", "/cgi-bin/msgaudit/get_chatdata");
        int wecomHttpConnectTimeoutMs = Integer.parseInt(getConfigOrDefault(fileProps, "WECOM_HTTP_CONNECT_TIMEOUT_MS", "5000"));
        int wecomHttpReadTimeoutMs = Integer.parseInt(getConfigOrDefault(fileProps, "WECOM_HTTP_READ_TIMEOUT_MS", "30000"));
        int wecomPullLimit = Integer.parseInt(getConfigOrDefault(fileProps, "WECOM_PULL_LIMIT", "200"));
        int wecomSeqOverlap = Math.max(0, Integer.parseInt(getConfigOrDefault(fileProps, "WECOM_SEQ_OVERLAP", "200")));
        int wecomMaxPagesPerPoll = Math.max(1, Integer.parseInt(getConfigOrDefault(fileProps, "WECOM_MAX_PAGES_PER_POLL", "5")));
        String wecomArchiveProxy = getConfigOrDefault(fileProps, "WECOM_ARCHIVE_PROXY", "");
        String wecomArchiveProxyPassword = getConfigOrDefault(fileProps, "WECOM_ARCHIVE_PROXY_PASSWORD", "");
        int wecomArchiveTimeoutSeconds = Integer.parseInt(getConfigOrDefault(fileProps, "WECOM_ARCHIVE_TIMEOUT_SECONDS", "5"));
        boolean wecomMediaFetchEnabled = Boolean.parseBoolean(getConfigOrDefault(fileProps, "WECOM_MEDIA_FETCH_ENABLED", "true"));
        int wecomMediaFetchBatchSize = Math.max(1, Integer.parseInt(getConfigOrDefault(fileProps, "WECOM_MEDIA_FETCH_BATCH_SIZE", "20")));
        String crmAttachmentBaseDir = getConfigOrDefault(fileProps, "CRM_ATTACHMENT_BASE_DIR", "/opt/cordys/data/files");
        String wecomPrivateKeyPath = getConfigOrDefault(fileProps, "WECOM_PRIVATE_KEY_PATH", "");
        String wecomPrivateKeyPem = getConfigOrDefault(fileProps, "WECOM_PRIVATE_KEY_PEM",
                getConfigOrDefault(fileProps, "WECOM_PRIVATE_KEY", ""));
        return new AppConfig(organizationId, corpId, pollSeconds, dbEnabled, dbJdbcUrl, dbUsername, dbPassword,
                crmIngestionDbEnabled, crmIngestionJdbcUrl, crmIngestionUsername, crmIngestionPassword,
                wecomCorpSecret, wecomApiHost, wecomChatDataPath,
                wecomHttpConnectTimeoutMs, wecomHttpReadTimeoutMs, wecomPullLimit,
                wecomSeqOverlap, wecomMaxPagesPerPoll,
                wecomArchiveProxy, wecomArchiveProxyPassword, wecomArchiveTimeoutSeconds,
                wecomMediaFetchEnabled, wecomMediaFetchBatchSize, crmAttachmentBaseDir,
                wecomPrivateKeyPath, wecomPrivateKeyPem);
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

    private static Properties loadFileProperties() {
        Properties props = new Properties();
        String[] paths = new String[]{"./config.properties", "./src/config.properties", "../config.properties"};
        for (String path : paths) {
            try (FileInputStream inputStream = new FileInputStream(path)) {
                props.load(inputStream);
                System.out.println("[BOOT] loaded config file: " + path);
                return props;
            } catch (Exception ignored) {
            }
        }
        return props;
    }

    public String getOrganizationId() {
        return organizationId;
    }

    public String getCorpId() {
        return corpId;
    }

    public int getPollSeconds() {
        return pollSeconds;
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

    public boolean isCrmIngestionDbEnabled() {
        return crmIngestionDbEnabled;
    }

    public String getCrmIngestionJdbcUrl() {
        return crmIngestionJdbcUrl;
    }

    public String getCrmIngestionUsername() {
        return crmIngestionUsername;
    }

    public String getCrmIngestionPassword() {
        return crmIngestionPassword;
    }

    public String getWecomCorpSecret() {
        return wecomCorpSecret;
    }

    public String getWecomApiHost() {
        return wecomApiHost;
    }

    public String getWecomChatDataPath() {
        return wecomChatDataPath;
    }

    public int getWecomHttpConnectTimeoutMs() {
        return wecomHttpConnectTimeoutMs;
    }

    public int getWecomHttpReadTimeoutMs() {
        return wecomHttpReadTimeoutMs;
    }

    public int getWecomPullLimit() {
        return wecomPullLimit;
    }

    public int getWecomSeqOverlap() {
        return wecomSeqOverlap;
    }

    public int getWecomMaxPagesPerPoll() {
        return wecomMaxPagesPerPoll;
    }

    public String getWecomArchiveProxy() {
        return wecomArchiveProxy;
    }

    public String getWecomArchiveProxyPassword() {
        return wecomArchiveProxyPassword;
    }

    public int getWecomArchiveTimeoutSeconds() {
        return wecomArchiveTimeoutSeconds;
    }

    public boolean isWecomMediaFetchEnabled() {
        return wecomMediaFetchEnabled;
    }

    public int getWecomMediaFetchBatchSize() {
        return wecomMediaFetchBatchSize;
    }

    public String getCrmAttachmentBaseDir() {
        return crmAttachmentBaseDir;
    }

    public String getWecomPrivateKeyPath() {
        return wecomPrivateKeyPath;
    }

    public String getWecomPrivateKeyPem() {
        return wecomPrivateKeyPem;
    }
}
