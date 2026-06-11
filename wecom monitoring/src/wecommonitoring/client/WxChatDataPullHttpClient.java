package wecommonitoring.client;

import wecommonitoring.model.ArchivePullBatch;
import wecommonitoring.model.WeComNormalizedMessage;
import wecommonitoring.repository.RawMessageRepository;
import wecommonitoring.util.SimpleJson;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 调用企业微信会话存档拉取接口，落原始加密包，并在可用时通过企业微信 Finance SDK 解密为 CRM 可消费的标准消息。
 */
public class WxChatDataPullHttpClient implements ArchivePullClient {
    private static final String DEFAULT_API_HOST = "https://qyapi.weixin.qq.com";

    private final String corpId;
    private final String corpSecret;
    private final String apiHost;
    private final String chatDataPath;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final int limit;
    private final String archiveProxy;
    private final String archiveProxyPassword;
    private final int archiveTimeoutSeconds;
    private final RawMessageRepository rawMessageRepository;
    private final FinanceSdkBridge financeSdkBridge;
    private final WeComRsaDecryptor randomKeyDecryptor;
    private final WeComMessageNormalizer normalizer;
    private String cachedAccessToken;
    private long accessTokenExpireAtMillis;

    public WxChatDataPullHttpClient(String corpId, String corpSecret, String apiHost, String chatDataPath,
                                    int connectTimeoutMs, int readTimeoutMs, int limit,
                                    String archiveProxy, String archiveProxyPassword, int archiveTimeoutSeconds,
                                    String privateKeyPem, String privateKeyPath,
                                    RawMessageRepository rawMessageRepository,
                                    List<String> monitoredUserids) {
        this.corpId = corpId;
        this.corpSecret = corpSecret;
        this.apiHost = apiHost == null || apiHost.isBlank() ? DEFAULT_API_HOST : apiHost.trim().replaceAll("/+$", "");
        this.chatDataPath = chatDataPath == null || chatDataPath.isBlank()
                ? "/cgi-bin/msgaudit/get_chatdata"
                : (chatDataPath.startsWith("/") ? chatDataPath : "/" + chatDataPath);
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.limit = Math.max(1, Math.min(limit, 1000));
        this.archiveProxy = archiveProxy == null ? "" : archiveProxy.trim();
        this.archiveProxyPassword = archiveProxyPassword == null ? "" : archiveProxyPassword;
        this.archiveTimeoutSeconds = Math.max(1, archiveTimeoutSeconds);
        this.rawMessageRepository = rawMessageRepository;
        this.financeSdkBridge = FinanceSdkBridge.create(corpId, corpSecret);
        this.randomKeyDecryptor = WeComRsaDecryptor.create(privateKeyPem, privateKeyPath);
        this.normalizer = new WeComMessageNormalizer(Set.copyOf(monitoredUserids == null ? List.of() : monitoredUserids));
        if (!financeSdkBridge.available()) {
            throw new IllegalStateException("Finance SDK unavailable: " + financeSdkBridge.disabledReason());
        }
        if (!randomKeyDecryptor.available()) {
            throw new IllegalStateException("WeCom RSA private key unavailable: " + randomKeyDecryptor.disabledReason());
        }
        if (!financeSdkBridge.canPullChatData()) {
            System.err.println("[BOOT] Finance SDK GetChatData unavailable, will try HTTP archive endpoint fallback.");
        }
    }

    @Override
    public ArchivePullBatch pull(long lastSeq) throws Exception {
        String response = pullArchivePayload(lastSeq);
        int errcode = extractIntField(response, "errcode", -1);
        if (errcode != 0) {
            System.err.printf("[WECOM_PULL] get_chatdata errcode=%d, body=%s%n", errcode, truncate(response, 800));
            return new ArchivePullBatch(lastSeq, List.of());
        }
        long nextSeq = extractLongField(response, "next_seq", lastSeq);
        Map<String, Object> root = SimpleJson.asObject(SimpleJson.parse(response));
        List<Object> chatChunks = SimpleJson.getArray(root, "chatdata");
        List<WeComNormalizedMessage> normalized = new ArrayList<>();
        int decodeFailures = 0;
        for (Object item : chatChunks) {
            Map<String, Object> chunk = SimpleJson.asObject(item);
            if (chunk.isEmpty()) {
                continue;
            }
            String chunkJson = SimpleJson.toJson(chunk);
            long seq = defaultLong(SimpleJson.asLong(chunk.get("seq")), -1);
            String rawId = defaultString(SimpleJson.getString(chunk, "msgid"), "seq-" + seq);
            rawMessageRepository.insertRaw(corpId, rawId, seq, chunkJson);
            try {
                String decryptedPayload = decryptChunk(chunk);
                Optional<WeComNormalizedMessage> msg = normalizer.normalize(decryptedPayload, chunk);
                if (msg.isPresent()) {
                    normalized.add(msg.get());
                    System.out.printf("[WECOM_HTTP] decrypted seq=%d, msgId=%s, msgType=%s%n",
                            seq, msg.get().getWecomMsgId(), msg.get().getMsgType());
                } else {
                    System.err.printf("[WECOM_HTTP] decrypted but not normalized, seq=%d%n", seq);
                }
            } catch (Exception ex) {
                decodeFailures++;
                System.err.printf("[WECOM_HTTP] stored raw chat chunk seq=%d, decrypt/normalize skipped: %s%n",
                        seq, ex.getMessage());
            }
            if (seq >= 0) {
                nextSeq = Math.max(nextSeq, seq + 1);
            }
        }
        if (chatChunks.isEmpty()) {
            System.out.printf("[WECOM_PULL] pull ok, next_seq=%d, chatdata_count=0%n", nextSeq);
        }
        return new ArchivePullBatch(nextSeq, normalized, chatChunks.size(), decodeFailures);
    }

    private String pullArchivePayload(long lastSeq) throws Exception {
        if (financeSdkBridge.canPullChatData()) {
            return financeSdkBridge.getChatData(lastSeq, limit, archiveProxy, archiveProxyPassword, archiveTimeoutSeconds);
        }
        String token = ensureAccessToken();
        String urlStr = apiHost + chatDataPath + "?access_token=" + urlEncode(token);
        String body = "{\"seq\":" + lastSeq + ",\"limit\":" + limit + ",\"corpid\":\"" + jsonEscape(corpId) + "\"}";
        return httpPostJson(urlStr, body);
    }

    private String decryptChunk(Map<String, Object> chunk) throws Exception {
        String encryptRandomKey = SimpleJson.getString(chunk, "encrypt_random_key");
        String encryptChatMsg = SimpleJson.getString(chunk, "encrypt_chat_msg");
        if ((encryptRandomKey == null || encryptRandomKey.isBlank()) && (encryptChatMsg == null || encryptChatMsg.isBlank())) {
            // 允许历史补导时直接给解密后的消息 JSON。
            return SimpleJson.toJson(chunk);
        }
        if (!financeSdkBridge.available()) {
            throw new IllegalStateException("Finance SDK unavailable: " + financeSdkBridge.disabledReason());
        }
        if (!randomKeyDecryptor.available()) {
            throw new IllegalStateException("WeCom RSA private key unavailable: " + randomKeyDecryptor.disabledReason());
        }
        if (encryptRandomKey == null || encryptRandomKey.isBlank() || encryptChatMsg == null || encryptChatMsg.isBlank()) {
            throw new IllegalStateException("missing encrypt_random_key or encrypt_chat_msg");
        }
        String randomKey = randomKeyDecryptor.decryptRandomKey(encryptRandomKey);
        return financeSdkBridge.decrypt(randomKey, encryptChatMsg);
    }

    private String ensureAccessToken() throws Exception {
        long now = System.currentTimeMillis();
        if (cachedAccessToken != null && now < accessTokenExpireAtMillis - 60_000L) {
            return cachedAccessToken;
        }
        String urlStr = apiHost + "/cgi-bin/gettoken?corpid=" + urlEncode(corpId) + "&corpsecret=" + urlEncode(corpSecret);
        String response = httpGet(urlStr);
        int errcode = extractIntField(response, "errcode", -1);
        if (errcode != 0) {
            throw new IllegalStateException("gettoken failed errcode=" + errcode + " body=" + truncate(response, 500));
        }
        String token = extractStringField(response, "access_token");
        if (token.isBlank()) {
            throw new IllegalStateException("gettoken missing access_token");
        }
        int expiresIn = extractIntField(response, "expires_in", 7200);
        this.cachedAccessToken = token;
        this.accessTokenExpireAtMillis = now + expiresIn * 1000L;
        return token;
    }

    private String httpGet(String urlStr) throws Exception {
        URL url = URI.create(urlStr).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(connectTimeoutMs);
        conn.setReadTimeout(readTimeoutMs);
        int code = conn.getResponseCode();
        String body = readStream(conn.getErrorStream() != null && code >= 400 ? conn.getErrorStream() : conn.getInputStream());
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP " + code + " " + body);
        }
        return body;
    }

    private String httpPostJson(String urlStr, String jsonBody) throws Exception {
        URL url = URI.create(urlStr).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(connectTimeoutMs);
        conn.setReadTimeout(readTimeoutMs);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        String body = readStream(conn.getErrorStream() != null && code >= 400 ? conn.getErrorStream() : conn.getInputStream());
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP " + code + " " + body);
        }
        return body;
    }

    private static String readStream(InputStream stream) throws Exception {
        if (stream == null) {
            return "";
        }
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }

    private static String urlEncode(String s) {
        if (s == null) {
            return "";
        }
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String jsonEscape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static long defaultLong(Long value, long defaultValue) {
        return value == null ? defaultValue : value;
    }

    private static String defaultString(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static int extractIntField(String json, String field, int defaultValue) {
        Long v = extractLongFlexible(json, field);
        return v == null ? defaultValue : v.intValue();
    }

    private static long extractLongField(String json, String field, long defaultValue) {
        Long v = extractLongFlexible(json, field);
        return v == null ? defaultValue : v;
    }

    private static Long extractLongFlexible(String json, String field) {
        if (json == null || json.isBlank()) {
            return null;
        }
        String key = "\"" + field + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) {
            return null;
        }
        int colon = json.indexOf(':', idx + key.length());
        if (colon < 0) {
            return null;
        }
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        int start = i;
        while (i < json.length() && (Character.isDigit(json.charAt(i)) || json.charAt(i) == '-')) {
            i++;
        }
        if (start == i) {
            return null;
        }
        try {
            return Long.parseLong(json.substring(start, i));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String extractStringField(String json, String field) {
        if (json == null || json.isBlank()) {
            return "";
        }
        String key = "\"" + field + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) {
            return "";
        }
        int colon = json.indexOf(':', idx);
        if (colon < 0) {
            return "";
        }
        int firstQuote = json.indexOf('"', colon + 1);
        if (firstQuote < 0) {
            return "";
        }
        int secondQuote = json.indexOf('"', firstQuote + 1);
        if (secondQuote < 0) {
            return "";
        }
        return json.substring(firstQuote + 1, secondQuote);
    }
}
