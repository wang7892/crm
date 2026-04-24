package mailmonitoring.client;

import mailmonitoring.model.WebhookPushRequest;
import mailmonitoring.model.WebhookPushResponse;
import mailmonitoring.model.WebhookAttachment;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class HttpCrmClient implements CrmClient {
    private final String baseUrl;
    private final String organizationId;
    private final String accessKey;
    private final String secretKey;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final int maxAttempts;
    private final int backoffMs;
    private final String webhookPath;

    public HttpCrmClient(String baseUrl, String organizationId, String accessKey, String secretKey,
                         int connectTimeoutMs, int readTimeoutMs, int maxAttempts, int backoffMs,
                         String webhookPath) {
        this.baseUrl = removeTrailingSlash(baseUrl);
        this.organizationId = organizationId;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.backoffMs = Math.max(0, backoffMs);
        this.webhookPath = normalizePath(webhookPath);
    }

    @Override
    public WebhookPushResponse pushMailEvent(WebhookPushRequest request) throws Exception {
        String lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return sendWebhook(request);
            } catch (Exception ex) {
                lastError = ex.getMessage();
                if (attempt < maxAttempts) {
                    Thread.sleep(backoffMs * (long) attempt);
                }
            }
        }
        throw new IllegalStateException("CRM API failed after retries: " + lastError);
    }

    private WebhookPushResponse sendWebhook(WebhookPushRequest request) throws Exception {
        URL url = new URL(baseUrl + webhookPath);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(connectTimeoutMs);
        conn.setReadTimeout(readTimeoutMs);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        if (organizationId != null && !organizationId.isBlank()) {
            conn.setRequestProperty("Organization-Id", organizationId);
        }
        if (accessKey != null && !accessKey.isBlank() && secretKey != null && !secretKey.isBlank()) {
            conn.setRequestProperty("Authorization", CrmAuthHelper.buildAuthorization(accessKey, secretKey));
        }

        String body = toJson(request);
        try (OutputStream outputStream = conn.getOutputStream()) {
            outputStream.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        String responseBody = readResponse(conn);
        String diag = buildDiag(url, conn, code, responseBody);
        if (code >= 200 && code < 300) {
            // CRM should return JSON like: {"success":true,"eventId":"..."}.
            // Treat missing/invalid response as failure so we can retry and surface the issue.
            boolean success = extractSuccessFromJson(responseBody);
            String eventId = extractEventIdFromJson(responseBody);
            if (!success) {
                // Put diagnostics into eventId so upper layer can print it.
                String detail = (!eventId.isBlank() ? ("eventId=" + eventId + ", ") : "") + diag;
                return new WebhookPushResponse(false, detail);
            }
            return new WebhookPushResponse(true, eventId);
        }
        throw new IllegalStateException("CRM API failed, " + diag);
    }

    private String toJson(WebhookPushRequest request) {
        return "{"
                + "\"organizationId\":\"" + escape(request.getOrganizationId()) + "\","
                + "\"sourceMailbox\":\"" + escape(request.getSourceMailbox()) + "\","
                + "\"messageId\":\"" + escape(request.getMessageId()) + "\","
                + "\"threadId\":\"" + escape(request.getThreadId()) + "\","
                + "\"fromAddress\":\"" + escape(request.getFromAddress()) + "\","
                + "\"toAddresses\":" + toJsonArray(request.getToAddresses()) + ","
                + "\"ccAddresses\":" + toJsonArray(request.getCcAddresses()) + ","
                + "\"subject\":\"" + escape(request.getSubject()) + "\","
                + "\"contentText\":\"" + escape(request.getContentText()) + "\","
                + "\"sendTime\":" + request.getSendTime() + ","
                + "\"matchedTargetMailbox\":\"" + escape(request.getMatchedTargetMailbox()) + "\","
                + "\"attachments\":" + toJsonAttachments(request.getAttachments())
                + "}";
    }
    
    private String toJsonArray(java.util.List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("\"").append(escape(values.get(i))).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }

    private String toJsonAttachments(java.util.List<WebhookAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < attachments.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            WebhookAttachment a = attachments.get(i);
            if (a == null) {
                sb.append("null");
                continue;
            }
            sb.append("{")
                    .append("\"fileName\":\"").append(escape(a.getFileName())).append("\",")
                    .append("\"contentType\":\"").append(escape(a.getContentType())).append("\",")
                    .append("\"sizeBytes\":").append(a.getSizeBytes()).append(",")
                    .append("\"downloadUrl\":\"").append(escape(a.getDownloadUrl())).append("\"")
                    .append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    private String readResponse(HttpURLConnection conn) {
        try (InputStream stream = conn.getErrorStream() != null ? conn.getErrorStream() : conn.getInputStream()) {
            if (stream == null) {
                return "";
            }
            byte[] data = stream.readAllBytes();
            String body = new String(data, StandardCharsets.UTF_8).trim();
            return body;
        } catch (Exception ignored) {
            return "";
        }
    }

    private String extractEventIdFromJson(String json) {
        if (json == null || json.isBlank()) {
            return "";
        }
        // Very small JSON parser: find "eventId":"..."
        int idx = json.indexOf("\"eventId\"");
        if (idx < 0) {
            return json; // fallback: keep raw body for debugging
        }
        int colon = json.indexOf(':', idx);
        if (colon < 0) {
            return json;
        }
        int firstQuote = json.indexOf('"', colon + 1);
        if (firstQuote < 0) {
            return json;
        }
        int secondQuote = json.indexOf('"', firstQuote + 1);
        if (secondQuote < 0) {
            return json;
        }
        return json.substring(firstQuote + 1, secondQuote);
    }

    private boolean extractSuccessFromJson(String json) {
        if (json == null || json.isBlank()) {
            return false;
        }
        int idx = json.indexOf("\"success\"");
        if (idx < 0) {
            return false;
        }
        int colon = json.indexOf(':', idx);
        if (colon < 0) {
            return false;
        }
        String tail = json.substring(colon + 1).trim();
        return tail.startsWith("true");
    }

    private String buildDiag(URL requestUrl, HttpURLConnection conn, int code, String responseBody) {
        String contentType = safeHeader(conn, "Content-Type");
        String server = safeHeader(conn, "Server");
        String location = safeHeader(conn, "Location");
        String authStatus = safeHeader(conn, "Authentication-Status");
        String poweredBy = safeHeader(conn, "X-Powered-By");
        String bodyPreview = responseBody == null ? "" : responseBody.trim();
        int bodyLength = responseBody == null ? 0 : responseBody.length();
        if (bodyPreview.length() > 500) {
            bodyPreview = bodyPreview.substring(0, 500) + "...";
        }
        return "url=" + (requestUrl == null ? "" : requestUrl)
                + ", status=" + code
                + ", contentType=" + contentType
                + ", server=" + server
                + (poweredBy.isBlank() ? "" : (", poweredBy=" + poweredBy))
                + (authStatus.isBlank() ? "" : (", authStatus=" + authStatus))
                + (location.isBlank() ? "" : (", location=" + location))
                + ", bodyLength=" + bodyLength
                + ", body=" + bodyPreview;
    }

    private String safeHeader(HttpURLConnection conn, String name) {
        try {
            String v = conn.getHeaderField(name);
            return v == null ? "" : v.trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private String removeTrailingSlash(String input) {
        if (input == null) {
            return "";
        }
        String result = input.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/api/webhook/email-log";
        }
        String trimmed = path.trim();
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }

    private String escape(String input) {
        if (input == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(input.length() + 16);
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            switch (ch) {
                case '\\':
                    sb.append("\\\\");
                    break;
                case '"':
                    sb.append("\\\"");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
            }
        }
        return sb.toString();
    }
}
