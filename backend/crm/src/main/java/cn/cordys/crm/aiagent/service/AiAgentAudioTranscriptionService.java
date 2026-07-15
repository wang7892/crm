package cn.cordys.crm.aiagent.service;

import cn.cordys.common.exception.GenericException;
import cn.cordys.common.response.result.CrmHttpResultCode;
import cn.cordys.common.util.JSON;
import cn.cordys.crm.aiagent.config.AiAgentLlmProperties;
import cn.cordys.crm.aiagent.config.AiAgentSpeechProperties;
import cn.cordys.crm.aiagent.dto.response.AiAgentAudioTranscriptionResponse;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class AiAgentAudioTranscriptionService {

    private static final Logger log = LoggerFactory.getLogger(AiAgentAudioTranscriptionService.class);
    private static final String LINE_SEPARATOR = "\r\n";
    private static final String PROVIDER_BAIDU = "baidu";
    private static final String LANGUAGE_ENGLISH = "en";

    private final AiAgentLlmProperties llmProperties;
    private final AiAgentSpeechProperties speechProperties;
    private final AiAgentBosAudioStorageService bosAudioStorageService;
    private volatile BaiduAccessToken baiduAccessToken;

    public AiAgentAudioTranscriptionService(AiAgentLlmProperties llmProperties,
                                            AiAgentSpeechProperties speechProperties,
                                            AiAgentBosAudioStorageService bosAudioStorageService) {
        this.llmProperties = llmProperties;
        this.speechProperties = speechProperties;
        this.bosAudioStorageService = bosAudioStorageService;
    }

    public AiAgentAudioTranscriptionResponse transcribe(MultipartFile file) {
        return transcribe(file, null);
    }

    public AiAgentAudioTranscriptionResponse transcribe(MultipartFile file, String language) {
        validateFile(file);
        try {
            if (StringUtils.equalsIgnoreCase(speechProperties.getProvider(), PROVIDER_BAIDU)) {
                return transcribeWithBaidu(file, language);
            }
            return transcribeWithOpenAiCompatibleProvider(file);
        } catch (GenericException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Audio transcription request failed", e);
            throw new GenericException(CrmHttpResultCode.FAILED,
                    "\u97f3\u9891\u8bc6\u522b\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5\u8bed\u97f3\u8bc6\u522b\u670d\u52a1\u914d\u7f6e\u6216\u7a0d\u540e\u91cd\u8bd5");
        }
    }

    public AiAgentAudioTranscriptionResponse query(String taskId) {
        return query(taskId, null);
    }

    public AiAgentAudioTranscriptionResponse query(String taskId, String language) {
        if (!StringUtils.equalsIgnoreCase(speechProperties.getProvider(), PROVIDER_BAIDU)) {
            throw new GenericException(CrmHttpResultCode.VALIDATE_FAILED,
                    "\u5f53\u524d\u8bed\u97f3\u8bc6\u522b\u670d\u52a1\u4e0d\u652f\u6301\u4efb\u52a1\u67e5\u8be2");
        }
        if (StringUtils.isBlank(taskId)) {
            throw new GenericException(CrmHttpResultCode.VALIDATE_FAILED,
                    "\u8bf7\u4f20\u5165\u8bed\u97f3\u8bc6\u522b\u4efb\u52a1 ID");
        }
        try {
            AiAgentSpeechProperties.Baidu baidu = speechProperties.getBaidu();
            validateBaiduConfig(baidu);
            return queryBaiduLongAudioTask(baidu, taskId, language);
        } catch (GenericException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Audio transcription query failed, taskId={}", taskId, e);
            throw new GenericException(CrmHttpResultCode.FAILED,
                    "\u67e5\u8be2\u97f3\u9891\u8bc6\u522b\u7ed3\u679c\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5");
        }
    }

    private AiAgentAudioTranscriptionResponse transcribeWithOpenAiCompatibleProvider(MultipartFile file)
            throws IOException, InterruptedException {
        SpeechProvider provider = resolveProvider();
        HttpRequest request = buildOpenAiCompatibleRequest(provider, file);
        HttpResponse<String> response = newHttpClient(provider.timeoutSeconds())
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String providerMessage = extractProviderErrorMessage(response.body());
            log.warn("Audio transcription request failed, status={}, model={}, url={}, body={}",
                    response.statusCode(), provider.model(),
                    resolveTranscriptionsUrl(provider.baseUrl(), provider.transcriptionsPath()),
                    StringUtils.abbreviate(response.body(), 1000));
            throw new GenericException(CrmHttpResultCode.FAILED,
                    buildProviderFailureMessage(response.statusCode(), provider.model(), providerMessage));
        }
        return parseOpenAiCompatibleResponse(response.body());
    }

    private AiAgentAudioTranscriptionResponse transcribeWithBaidu(MultipartFile file, String language)
            throws IOException, InterruptedException {
        AiAgentSpeechProperties.Baidu baidu = speechProperties.getBaidu();
        validateBaiduConfig(baidu);

        if (StringUtils.equalsIgnoreCase(StringUtils.defaultIfBlank(baidu.getMode(), "long"), "long")) {
            return createBaiduLongAudioTask(file, baidu, language);
        }

        String format = resolveBaiduAudioFormat(file);
        String accessToken = resolveBaiduAccessToken(baidu);
        byte[] audioBytes = file.getBytes();
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("format", format);
        requestBody.put("rate", baidu.getRate());
        requestBody.put("channel", 1);
        requestBody.put("cuid", StringUtils.defaultIfBlank(baidu.getCuid(), "cordys-crm"));
        requestBody.put("token", accessToken);
        requestBody.put("dev_pid", baidu.getDevPid());
        requestBody.put("len", audioBytes.length);
        requestBody.put("speech", Base64.getEncoder().encodeToString(audioBytes));

        String serverUrl = StringUtils.defaultIfBlank(baidu.getServerUrl(), "https://vop.baidu.com/server_api");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(serverUrl))
                .timeout(Duration.ofSeconds(resolveTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(requestBody), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = newHttpClient(resolveTimeoutSeconds())
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.warn("Baidu audio transcription request failed, status={}, body={}",
                    response.statusCode(), StringUtils.abbreviate(response.body(), 1000));
            throw new GenericException(CrmHttpResultCode.FAILED,
                    "\u767e\u5ea6\u8bed\u97f3\u8bc6\u522b\u8bf7\u6c42\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5");
        }
        return parseBaiduResponse(response.body());
    }

    private AiAgentAudioTranscriptionResponse createBaiduLongAudioTask(MultipartFile file,
                                                                       AiAgentSpeechProperties.Baidu baidu,
                                                                       String language)
            throws IOException, InterruptedException {
        AiAgentBosAudioStorageService.StoredAudio storedAudio = bosAudioStorageService.uploadAndSign(file);
        String format = resolveBaiduLongAudioFormat(file);
        String accessToken = resolveBaiduAccessToken(baidu);
        int pid = resolveBaiduLongAudioPid(baidu, language);
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("speech_url", storedAudio.signedUrl());
        requestBody.put("format", format);
        requestBody.put("pid", pid);
        requestBody.put("rate", baidu.getRate());
        putIfNotNull(requestBody, "role_num", baidu.getRoleNum());
        if (isBaiduChineseLongAudioPid(pid)) {
            putIfNotNull(requestBody, "smooth_text", baidu.getSmoothText());
        }
        putIfNotNull(requestBody, "filter_sensitive", baidu.getFilterSensitive());

        String createUrl = appendAccessToken(
                StringUtils.defaultIfBlank(baidu.getCreateUrl(), "https://aip.baidubce.com/rpc/2.0/aasr/v1/create"),
                accessToken);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(createUrl))
                .timeout(Duration.ofSeconds(resolveTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(requestBody), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = newHttpClient(resolveTimeoutSeconds())
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.warn("Baidu long audio task creation failed, status={}, objectKey={}, body={}",
                    response.statusCode(), storedAudio.objectKey(), StringUtils.abbreviate(response.body(), 1000));
            throw new GenericException(CrmHttpResultCode.FAILED,
                    "\u63d0\u4ea4\u767e\u5ea6\u957f\u97f3\u9891\u8f6c\u5199\u4efb\u52a1\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5");
        }
        return parseBaiduCreateTaskResponse(response.body(), storedAudio.objectKey(), pid);
    }

    private AiAgentAudioTranscriptionResponse queryBaiduLongAudioTask(AiAgentSpeechProperties.Baidu baidu,
                                                                      String taskId,
                                                                      String language)
            throws IOException, InterruptedException {
        String accessToken = resolveBaiduAccessToken(baidu);
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("task_ids", List.of(taskId));
        String queryUrl = appendAccessToken(
                StringUtils.defaultIfBlank(baidu.getQueryUrl(), "https://aip.baidubce.com/rpc/2.0/aasr/v1/query"),
                accessToken);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(queryUrl))
                .timeout(Duration.ofSeconds(resolveTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(requestBody), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = newHttpClient(resolveTimeoutSeconds())
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.warn("Baidu long audio task query failed, status={}, taskId={}, body={}",
                    response.statusCode(), taskId, StringUtils.abbreviate(response.body(), 1000));
            throw new GenericException(CrmHttpResultCode.FAILED,
                    "\u67e5\u8be2\u767e\u5ea6\u957f\u97f3\u9891\u8f6c\u5199\u7ed3\u679c\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5");
        }
        return parseBaiduQueryTaskResponse(response.body(), taskId, resolveBaiduLongAudioPid(baidu, language));
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new GenericException(CrmHttpResultCode.VALIDATE_FAILED,
                    "\u8bf7\u9009\u62e9\u97f3\u9891\u6587\u4ef6");
        }
        long maxBytes = Math.max(1, speechProperties.getMaxFileSizeMb()) * 1024L * 1024L;
        if (file.getSize() > maxBytes) {
            throw new GenericException(CrmHttpResultCode.VALIDATE_FAILED,
                    "\u97f3\u9891\u6587\u4ef6\u4e0d\u80fd\u8d85\u8fc7 "
                            + Math.max(1, speechProperties.getMaxFileSizeMb()) + "MB");
        }
        String contentType = StringUtils.defaultString(file.getContentType()).toLowerCase();
        String filename = StringUtils.defaultString(file.getOriginalFilename()).toLowerCase();
        if (!contentType.startsWith("audio/")
                && !filename.matches(".*\\.(mp3|mp4|mpeg|mpga|m4a|wav|webm|aac|ogg|oga|flac|amr|pcm)$")) {
            throw new GenericException(CrmHttpResultCode.VALIDATE_FAILED,
                    "\u8bf7\u9009\u62e9\u97f3\u9891\u6587\u4ef6");
        }
    }

    private SpeechProvider resolveProvider() {
        boolean enabled = speechProperties.getEnabled() == null
                ? llmProperties.isEnabled()
                : speechProperties.getEnabled();
        if (!enabled) {
            throw new GenericException(CrmHttpResultCode.FAILED,
                    "\u97f3\u9891\u8bc6\u522b\u670d\u52a1\u672a\u542f\u7528");
        }
        String baseUrl = StringUtils.defaultIfBlank(speechProperties.getBaseUrl(), llmProperties.getBaseUrl());
        String apiKey = StringUtils.defaultIfBlank(speechProperties.getApiKey(), llmProperties.getApiKey());
        String model = StringUtils.defaultIfBlank(speechProperties.getModel(), "whisper-1");
        int timeoutSeconds = speechProperties.getTimeoutSeconds() > 0
                ? speechProperties.getTimeoutSeconds()
                : Math.max(1, llmProperties.getTimeoutSeconds());
        if (StringUtils.isAnyBlank(baseUrl, model)) {
            throw new GenericException(CrmHttpResultCode.FAILED,
                    "\u97f3\u9891\u8bc6\u522b\u670d\u52a1\u672a\u914d\u7f6e");
        }
        return new SpeechProvider(baseUrl, speechProperties.getTranscriptionsPath(), apiKey, model, timeoutSeconds);
    }

    private void validateBaiduConfig(AiAgentSpeechProperties.Baidu baidu) {
        if (baidu == null) {
            throw new GenericException(CrmHttpResultCode.FAILED,
                    "\u767e\u5ea6\u8bed\u97f3\u8bc6\u522b\u670d\u52a1\u672a\u914d\u7f6e");
        }
        if (StringUtils.isBlank(baidu.getAccessToken())
                && (StringUtils.isBlank(baidu.getApiKey()) || StringUtils.isBlank(baidu.getSecretKey()))) {
            throw new GenericException(CrmHttpResultCode.FAILED,
                    "\u767e\u5ea6\u8bed\u97f3\u8bc6\u522b\u670d\u52a1\u672a\u914d\u7f6e\uff0c\u8bf7\u586b\u5199 crm.ai-agent.speech.baidu.api-key \u548c secret-key");
        }
    }

    private String resolveBaiduAudioFormat(MultipartFile file) {
        String filename = StringUtils.defaultString(file.getOriginalFilename()).toLowerCase();
        String contentType = StringUtils.defaultString(file.getContentType()).toLowerCase();
        if (filename.endsWith(".pcm") || contentType.contains("pcm")) {
            return "pcm";
        }
        if (filename.endsWith(".wav") || contentType.contains("wav")) {
            return "wav";
        }
        if (filename.endsWith(".amr") || contentType.contains("amr")) {
            return "amr";
        }
        if (filename.endsWith(".m4a") || contentType.contains("m4a") || contentType.contains("mp4")) {
            return "m4a";
        }
        throw new GenericException(CrmHttpResultCode.VALIDATE_FAILED,
                "\u767e\u5ea6\u77ed\u8bed\u97f3\u8bc6\u522b\u4ec5\u652f\u6301 pcm\u3001wav\u3001amr\u3001m4a \u683c\u5f0f\uff0c\u8bf7\u8f6c\u6362\u97f3\u9891\u540e\u518d\u4e0a\u4f20");
    }

    private String resolveBaiduLongAudioFormat(MultipartFile file) {
        String filename = StringUtils.defaultString(file.getOriginalFilename()).toLowerCase();
        String contentType = StringUtils.defaultString(file.getContentType()).toLowerCase();
        if (filename.endsWith(".mp3") || contentType.contains("mpeg") || contentType.contains("mp3")) {
            return "mp3";
        }
        if (filename.endsWith(".wav") || contentType.contains("wav")) {
            return "wav";
        }
        if (filename.endsWith(".pcm") || contentType.contains("pcm")) {
            return "pcm";
        }
        if (filename.endsWith(".m4a") || contentType.contains("m4a") || contentType.contains("mp4")) {
            return "m4a";
        }
        if (filename.endsWith(".amr") || contentType.contains("amr")) {
            return "amr";
        }
        throw new GenericException(CrmHttpResultCode.VALIDATE_FAILED,
                "\u767e\u5ea6\u97f3\u9891\u6587\u4ef6\u8f6c\u5199\u4ec5\u652f\u6301 mp3\u3001wav\u3001pcm\u3001m4a\u3001amr \u683c\u5f0f\uff0c\u8bf7\u8f6c\u6362\u97f3\u9891\u540e\u518d\u4e0a\u4f20");
    }

    private String resolveBaiduAccessToken(AiAgentSpeechProperties.Baidu baidu)
            throws IOException, InterruptedException {
        String configuredToken = StringUtils.trimToNull(baidu.getAccessToken());
        if (configuredToken != null) {
            return configuredToken;
        }
        BaiduAccessToken cachedToken = baiduAccessToken;
        if (cachedToken != null && cachedToken.expiresAt().isAfter(Instant.now().plusSeconds(60))) {
            return cachedToken.token();
        }
        synchronized (this) {
            cachedToken = baiduAccessToken;
            if (cachedToken != null && cachedToken.expiresAt().isAfter(Instant.now().plusSeconds(60))) {
                return cachedToken.token();
            }
            baiduAccessToken = fetchBaiduAccessToken(baidu);
            return baiduAccessToken.token();
        }
    }

    private BaiduAccessToken fetchBaiduAccessToken(AiAgentSpeechProperties.Baidu baidu)
            throws IOException, InterruptedException {
        String tokenUrl = StringUtils.defaultIfBlank(baidu.getTokenUrl(), "https://aip.baidubce.com/oauth/2.0/token")
                + "?grant_type=client_credentials"
                + "&client_id=" + urlEncode(baidu.getApiKey())
                + "&client_secret=" + urlEncode(baidu.getSecretKey());
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(tokenUrl))
                .timeout(Duration.ofSeconds(resolveTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = newHttpClient(resolveTimeoutSeconds())
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.warn("Baidu access token request failed, status={}, body={}",
                    response.statusCode(), StringUtils.abbreviate(response.body(), 1000));
            throw new GenericException(CrmHttpResultCode.FAILED,
                    "\u767e\u5ea6\u8bed\u97f3\u8bc6\u522b\u9274\u6743\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5 API Key \u548c Secret Key");
        }
        Map<String, Object> body = JSON.parseToMap(response.body());
        String accessToken = StringUtils.trimToNull(
                body.get("access_token") == null ? null : String.valueOf(body.get("access_token")));
        if (accessToken == null) {
            log.warn("Baidu access token response missing access_token, body={}",
                    StringUtils.abbreviate(response.body(), 1000));
            throw new GenericException(CrmHttpResultCode.FAILED,
                    "\u767e\u5ea6\u8bed\u97f3\u8bc6\u522b\u9274\u6743\u5931\u8d25\uff0c\u672a\u83b7\u53d6\u5230 access_token");
        }
        long expiresIn = parseLong(body.get("expires_in"), 2_592_000L);
        return new BaiduAccessToken(accessToken, Instant.now().plusSeconds(Math.max(60, expiresIn - 60)));
    }

    private HttpRequest buildOpenAiCompatibleRequest(SpeechProvider provider, MultipartFile file) throws IOException {
        String boundary = "----CordysAudioBoundary" + UUID.randomUUID().toString().replace("-", "");
        byte[] body = buildMultipartBody(boundary, provider.model(), file);
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(resolveTranscriptionsUrl(provider.baseUrl(), provider.transcriptionsPath())))
                .timeout(Duration.ofSeconds(Math.max(1, provider.timeoutSeconds())))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (StringUtils.isNotBlank(provider.apiKey())) {
            requestBuilder.header("Authorization", "Bearer " + provider.apiKey());
        }
        return requestBuilder.build();
    }

    private byte[] buildMultipartBody(String boundary, String model, MultipartFile file) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeTextPart(output, boundary, "model", model);
        writeTextPart(output, boundary, "response_format", "json");
        writeFilePart(output, boundary, file);
        output.write(("--" + boundary + "--" + LINE_SEPARATOR).getBytes(StandardCharsets.UTF_8));
        return output.toByteArray();
    }

    private void writeTextPart(ByteArrayOutputStream output, String boundary, String name, String value)
            throws IOException {
        output.write(("--" + boundary + LINE_SEPARATOR).getBytes(StandardCharsets.UTF_8));
        output.write(("Content-Disposition: form-data; name=\"" + name + "\"" + LINE_SEPARATOR)
                .getBytes(StandardCharsets.UTF_8));
        output.write(LINE_SEPARATOR.getBytes(StandardCharsets.UTF_8));
        output.write(StringUtils.defaultString(value).getBytes(StandardCharsets.UTF_8));
        output.write(LINE_SEPARATOR.getBytes(StandardCharsets.UTF_8));
    }

    private void writeFilePart(ByteArrayOutputStream output, String boundary, MultipartFile file) throws IOException {
        String filename = StringUtils.defaultIfBlank(file.getOriginalFilename(), "audio");
        String contentType = StringUtils.defaultIfBlank(file.getContentType(), "application/octet-stream");
        output.write(("--" + boundary + LINE_SEPARATOR).getBytes(StandardCharsets.UTF_8));
        output.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + sanitizeFilename(filename) + "\""
                + LINE_SEPARATOR).getBytes(StandardCharsets.UTF_8));
        output.write(("Content-Type: " + contentType + LINE_SEPARATOR).getBytes(StandardCharsets.UTF_8));
        output.write(LINE_SEPARATOR.getBytes(StandardCharsets.UTF_8));
        output.write(file.getBytes());
        output.write(LINE_SEPARATOR.getBytes(StandardCharsets.UTF_8));
    }

    private HttpClient newHttpClient(int timeoutSeconds) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                .build();
    }

    private int resolveTimeoutSeconds() {
        return speechProperties.getTimeoutSeconds() > 0 ? speechProperties.getTimeoutSeconds() : 120;
    }

    private String sanitizeFilename(String filename) {
        return filename.replace("\\", "_").replace("\"", "_").replace("\r", "_").replace("\n", "_");
    }

    private String resolveTranscriptionsUrl(String baseUrl, String transcriptionsPath) {
        String url = StringUtils.removeEnd(StringUtils.defaultString(baseUrl).trim(), "/");
        String path = StringUtils.trimToNull(transcriptionsPath);
        if (path != null) {
            return url + (path.startsWith("/") ? path : "/" + path);
        }
        if (url.endsWith("/audio/transcriptions")) {
            return url;
        }
        if (url.endsWith("/v1")) {
            return url + "/audio/transcriptions";
        }
        return url + "/v1/audio/transcriptions";
    }

    private AiAgentAudioTranscriptionResponse parseOpenAiCompatibleResponse(String responseBody) {
        Map<String, Object> body = JSON.parseToMap(responseBody);
        String text = StringUtils.trimToEmpty(body.get("text") == null ? null : String.valueOf(body.get("text")));
        if (StringUtils.isBlank(text)) {
            throw new GenericException(CrmHttpResultCode.FAILED,
                    "\u672a\u8bc6\u522b\u5230\u97f3\u9891\u5185\u5bb9");
        }
        Object languageValue = body.get("language");
        String language = languageValue == null ? null : StringUtils.trimToNull(String.valueOf(languageValue));
        return new AiAgentAudioTranscriptionResponse(text, language);
    }

    private AiAgentAudioTranscriptionResponse parseBaiduResponse(String responseBody) {
        Map<String, Object> body = JSON.parseToMap(responseBody);
        int errNo = parseInt(body.get("err_no"), -1);
        if (errNo != 0) {
            String errMsg = StringUtils.trimToNull(
                    body.get("err_msg") == null ? null : String.valueOf(body.get("err_msg")));
            log.warn("Baidu audio transcription failed, errNo={}, errMsg={}, body={}",
                    errNo, errMsg, StringUtils.abbreviate(responseBody, 1000));
            throw new GenericException(CrmHttpResultCode.FAILED, buildBaiduFailureMessage(errNo, errMsg));
        }
        Object resultValue = body.get("result");
        String text = null;
        if (resultValue instanceof List<?> resultList && !resultList.isEmpty()) {
            text = String.valueOf(resultList.get(0));
        } else if (resultValue != null) {
            text = String.valueOf(resultValue);
        }
        text = StringUtils.trimToEmpty(text);
        if (StringUtils.isBlank(text)) {
            throw new GenericException(CrmHttpResultCode.FAILED,
                    "\u672a\u8bc6\u522b\u5230\u97f3\u9891\u5185\u5bb9");
        }
        return new AiAgentAudioTranscriptionResponse(text, resolveBaiduLanguage(speechProperties.getBaidu().getDevPid()));
    }

    private AiAgentAudioTranscriptionResponse parseBaiduCreateTaskResponse(String responseBody, String objectKey,
                                                                          int pid) {
        Map<String, Object> body = JSON.parseToMap(responseBody);
        Integer errNo = findInt(body, "err_no", "err_no");
        if (errNo == null) {
            errNo = findInt(body, "error_code", "error_code");
        }
        if (errNo != null && errNo != 0) {
            String errMsg = firstNonBlank(findString(body, "err_msg", "err_msg"),
                    findString(body, "error_msg", "error_msg"));
            log.warn("Baidu long audio task creation failed, errNo={}, errMsg={}, objectKey={}, body={}",
                    errNo, errMsg, objectKey, StringUtils.abbreviate(responseBody, 1000));
            throw new GenericException(CrmHttpResultCode.FAILED, buildBaiduLongAudioFailureMessage(errNo, errMsg));
        }
        String taskId = firstNonBlank(
                findString(body, "task_id", "task_id"),
                findString(body, "taskId", "taskId"),
                findString(body, "data.task_id", "data.task_id"));
        if (StringUtils.isBlank(taskId)) {
            log.warn("Baidu long audio task response missing task_id, objectKey={}, body={}",
                    objectKey, StringUtils.abbreviate(responseBody, 1000));
            throw new GenericException(CrmHttpResultCode.FAILED,
                    "\u63d0\u4ea4\u767e\u5ea6\u957f\u97f3\u9891\u8f6c\u5199\u4efb\u52a1\u5931\u8d25\uff0c\u672a\u83b7\u53d6\u5230\u4efb\u52a1 ID");
        }
        return new AiAgentAudioTranscriptionResponse(
                taskId,
                "RUNNING",
                null,
                resolveBaiduLanguage(pid));
    }

    private AiAgentAudioTranscriptionResponse parseBaiduQueryTaskResponse(String responseBody, String requestedTaskId,
                                                                         int pid) {
        Map<String, Object> body = JSON.parseToMap(responseBody);
        Integer errNo = findInt(body, "err_no", "err_no");
        if (errNo == null) {
            errNo = findInt(body, "error_code", "error_code");
        }
        if (errNo != null && errNo != 0) {
            String errMsg = firstNonBlank(findString(body, "err_msg", "err_msg"),
                    findString(body, "error_msg", "error_msg"));
            log.warn("Baidu long audio task query failed, errNo={}, errMsg={}, taskId={}, body={}",
                    errNo, errMsg, requestedTaskId, StringUtils.abbreviate(responseBody, 1000));
            throw new GenericException(CrmHttpResultCode.FAILED, buildBaiduLongAudioFailureMessage(errNo, errMsg));
        }
        Map<String, Object> taskInfo = extractTaskInfo(body, requestedTaskId);
        if (taskInfo == null || taskInfo.isEmpty()) {
            log.warn("Baidu long audio task query response missing task info, taskId={}, body={}",
                    requestedTaskId, StringUtils.abbreviate(responseBody, 1000));
            return new AiAgentAudioTranscriptionResponse(requestedTaskId, "RUNNING", null,
                    resolveBaiduLanguage(pid));
        }

        String taskId = firstNonBlank(findString(taskInfo, "task_id", "task_id"),
                findString(taskInfo, "taskId", "taskId"),
                requestedTaskId);
        String providerStatus = firstNonBlank(
                findString(taskInfo, "task_status", "task_status"),
                findString(taskInfo, "status", "status"),
                findString(taskInfo, "taskStatus", "taskStatus"));
        String normalizedStatus = normalizeBaiduTaskStatus(providerStatus);
        if (StringUtils.equals(normalizedStatus, "FAILED")) {
            String errMsg = firstNonBlank(findString(taskInfo, "err_msg", "err_msg"),
                    findString(taskInfo, "task_result.err_msg", "task_result.err_msg"),
                    findString(taskInfo, "error_msg", "error_msg"));
            throw new GenericException(CrmHttpResultCode.FAILED,
                    appendDetail("\u767e\u5ea6\u957f\u97f3\u9891\u8f6c\u5199\u5931\u8d25", errMsg));
        }
        if (!StringUtils.equals(normalizedStatus, "SUCCESS")) {
            return new AiAgentAudioTranscriptionResponse(taskId, normalizedStatus, null,
                    resolveBaiduLanguage(pid));
        }

        String text = extractBaiduLongAudioText(taskInfo);
        if (StringUtils.isBlank(text)) {
            log.warn("Baidu long audio task succeeded without text, taskId={}, body={}",
                    taskId, StringUtils.abbreviate(responseBody, 1000));
            throw new GenericException(CrmHttpResultCode.FAILED,
                    "\u672a\u8bc6\u522b\u5230\u97f3\u9891\u5185\u5bb9");
        }
        return new AiAgentAudioTranscriptionResponse(taskId, "SUCCESS", text,
                resolveBaiduLanguage(pid));
    }

    private String extractProviderErrorMessage(String responseBody) {
        try {
            Map<String, Object> body = JSON.parseToMap(responseBody);
            Object error = body.get("error");
            if (error instanceof Map<?, ?> errorMap) {
                Object message = errorMap.get("message");
                Object code = errorMap.get("code");
                String detail = StringUtils.trimToNull(message == null ? null : String.valueOf(message));
                String errorCode = StringUtils.trimToNull(code == null ? null : String.valueOf(code));
                if (StringUtils.isNotBlank(errorCode) && StringUtils.isNotBlank(detail)) {
                    return errorCode + ": " + detail;
                }
                if (StringUtils.isNotBlank(detail)) {
                    return detail;
                }
                if (StringUtils.isNotBlank(errorCode)) {
                    return errorCode;
                }
            }
            Object message = body.get("message");
            return StringUtils.trimToNull(message == null ? null : String.valueOf(message));
        } catch (Exception e) {
            return null;
        }
    }

    private String buildProviderFailureMessage(int statusCode, String model, String providerMessage) {
        String detail = StringUtils.abbreviate(StringUtils.trimToNull(providerMessage), 300);
        if (statusCode == 401 || statusCode == 403) {
            return appendDetail(
                    "\u97f3\u9891\u8bc6\u522b\u670d\u52a1\u9274\u6743\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5 crm.ai-agent.speech.api-key",
                    detail);
        }
        if (StringUtils.containsIgnoreCase(detail, "model_not_found")
                || StringUtils.containsIgnoreCase(detail, "model not found")
                || StringUtils.contains(detail, "\u6a21\u578b")) {
            return appendDetail(
                    "\u97f3\u9891\u8bc6\u522b\u6a21\u578b\u4e0d\u53ef\u7528\uff0c\u8bf7\u68c0\u67e5 crm.ai-agent.speech.model\uff08\u5f53\u524d\uff1a"
                            + model + "\uff09\u6216\u66f4\u6362\u652f\u6301\u8bed\u97f3\u8bc6\u522b\u7684\u670d\u52a1\u5546",
                    detail);
        }
        return appendDetail(
                "\u97f3\u9891\u8bc6\u522b\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5\u8bed\u97f3\u8bc6\u522b\u670d\u52a1\u914d\u7f6e\u6216\u7a0d\u540e\u91cd\u8bd5",
                detail);
    }

    private String buildBaiduFailureMessage(int errNo, String errMsg) {
        String detail = StringUtils.abbreviate(StringUtils.trimToNull(errMsg), 300);
        String message = switch (errNo) {
            case 3301, 3307 ->
                    "\u767e\u5ea6\u8bed\u97f3\u8bc6\u522b\u5931\u8d25\uff0c\u97f3\u9891\u8d28\u91cf\u53ef\u80fd\u8f83\u5dee\u6216\u97f3\u91cf\u8fc7\u4f4e";
            case 3302 ->
                    "\u767e\u5ea6\u8bed\u97f3\u8bc6\u522b\u9274\u6743\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5 API Key\u3001Secret Key \u6216 access_token";
            case 3303 ->
                    "\u767e\u5ea6\u8bed\u97f3\u8bc6\u522b\u670d\u52a1\u7aef\u9519\u8bef\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5";
            case 3304 ->
                    "\u767e\u5ea6\u8bed\u97f3\u8bc6\u522b\u8bf7\u6c42\u91cf\u8d85\u9650\uff0c\u8bf7\u68c0\u67e5\u914d\u989d";
            case 3314, 3315 ->
                    "\u767e\u5ea6\u8bed\u97f3\u8bc6\u522b\u5931\u8d25\uff0c\u97f3\u9891\u6587\u4ef6\u957f\u5ea6\u6216\u5927\u5c0f\u4e0d\u7b26\u5408\u8981\u6c42";
            case 3316 ->
                    "\u767e\u5ea6\u8bed\u97f3\u8bc6\u522b\u5931\u8d25\uff0c\u97f3\u9891\u683c\u5f0f\u4e0d\u652f\u6301";
            default -> "\u767e\u5ea6\u8bed\u97f3\u8bc6\u522b\u5931\u8d25";
        };
        return appendDetail(message, detail);
    }

    private String buildBaiduLongAudioFailureMessage(int errNo, String errMsg) {
        String detail = StringUtils.abbreviate(StringUtils.trimToNull(errMsg), 300);
        String message = switch (errNo) {
            case 3302, 3309, 336000 ->
                    "\u767e\u5ea6\u8bed\u97f3\u8bc6\u522b\u9274\u6743\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5 API Key\u3001Secret Key \u6216 access_token";
            case 3314, 3315, 336100 ->
                    "\u767e\u5ea6\u957f\u97f3\u9891\u8f6c\u5199\u5931\u8d25\uff0c\u97f3\u9891\u5927\u5c0f\u6216 URL \u4e0d\u7b26\u5408\u8981\u6c42";
            case 3316, 336101 ->
                    "\u767e\u5ea6\u957f\u97f3\u9891\u8f6c\u5199\u5931\u8d25\uff0c\u97f3\u9891\u683c\u5f0f\u4e0d\u652f\u6301";
            default -> "\u767e\u5ea6\u957f\u97f3\u9891\u8f6c\u5199\u5931\u8d25";
        };
        return appendDetail(message, detail);
    }

    private String appendDetail(String message, String detail) {
        if (StringUtils.isBlank(detail)) {
            return message;
        }
        return message + "\uff1a" + detail;
    }

    private String resolveBaiduLanguage(int devPid) {
        return switch (devPid) {
            case 1737 -> "en";
            case 80006, 80001, 8953 -> "zh";
            case 1637 -> "yue";
            case 1837 -> "sc";
            default -> "zh";
        };
    }

    private int resolveBaiduLongAudioPid(AiAgentSpeechProperties.Baidu baidu, String language) {
        if (StringUtils.equalsIgnoreCase(StringUtils.trimToEmpty(language), LANGUAGE_ENGLISH)) {
            return 1737;
        }
        return baidu.getPid();
    }

    private boolean isBaiduChineseLongAudioPid(int pid) {
        return pid == 80001 || pid == 80006 || pid == 8953;
    }

    private String appendAccessToken(String url, String accessToken) {
        String separator = StringUtils.contains(url, "?") ? "&" : "?";
        return url + separator + "access_token=" + urlEncode(accessToken);
    }

    private void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private String normalizeBaiduTaskStatus(String status) {
        String normalized = StringUtils.trimToEmpty(status);
        if (StringUtils.equalsAnyIgnoreCase(normalized, "Success", "Succeeded", "Finished", "Finish")) {
            return "SUCCESS";
        }
        if (StringUtils.equalsAnyIgnoreCase(normalized, "Failure", "Failed", "Error")) {
            return "FAILED";
        }
        return "RUNNING";
    }

    private Map<String, Object> extractTaskInfo(Map<String, Object> body, String requestedTaskId) {
        Object tasksInfo = body.get("tasks_info");
        if (!(tasksInfo instanceof List<?> tasks) || tasks.isEmpty()) {
            tasksInfo = body.get("tasks");
        }
        if (tasksInfo instanceof List<?> tasks) {
            Map<String, Object> firstTask = null;
            for (Object task : tasks) {
                if (!(task instanceof Map<?, ?> taskMap)) {
                    continue;
                }
                Map<String, Object> normalizedTask = normalizeMap(taskMap);
                if (firstTask == null) {
                    firstTask = normalizedTask;
                }
                String taskId = firstNonBlank(findString(normalizedTask, "task_id", "task_id"),
                        findString(normalizedTask, "taskId", "taskId"));
                if (StringUtils.equals(taskId, requestedTaskId)) {
                    return normalizedTask;
                }
            }
            return firstTask;
        }
        Object taskInfo = body.get("task_info");
        if (taskInfo instanceof Map<?, ?> taskMap) {
            return normalizeMap(taskMap);
        }
        return body;
    }

    String extractBaiduLongAudioText(Map<String, Object> taskInfo) {
        Set<String> candidates = new LinkedHashSet<>();
        addTextCandidate(candidates, taskInfo.get("task_result"));
        addTextCandidate(candidates, taskInfo.get("result"));
        addTextCandidate(candidates, findValue(taskInfo, "task_result.detailed_result"));
        return StringUtils.trimToNull(String.join("\n", candidates));
    }

    private void addTextCandidate(Set<String> candidates, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof List<?> list) {
            for (Object item : list) {
                addTextCandidate(candidates, item);
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            Object directResult = map.get("result");
            Object resultText = map.get("result_text");
            Object text = map.get("text");
            Object sentence = map.get("sentence");
            Object oneBest = map.get("onebest");
            addTextCandidate(candidates, directResult);
            addTextCandidate(candidates, resultText);
            addTextCandidate(candidates, text);
            addTextCandidate(candidates, sentence);
            addTextCandidate(candidates, oneBest);
            return;
        }
        String text = StringUtils.trimToNull(String.valueOf(value));
        if (text != null && !StringUtils.equalsAny(text, "[]", "{}")) {
            candidates.add(text);
        }
    }

    private Map<String, Object> normalizeMap(Map<?, ?> source) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        source.forEach((key, value) -> normalized.put(String.valueOf(key), value));
        return normalized;
    }

    private Object findValue(Map<String, Object> map, String path) {
        Object current = map;
        for (String part : StringUtils.split(path, '.')) {
            if (!(current instanceof Map<?, ?> currentMap)) {
                return null;
            }
            current = currentMap.get(part);
        }
        return current;
    }

    private String findString(Map<String, Object> map, String path, String fallbackKey) {
        Object value = findValue(map, path);
        if (value == null && StringUtils.isNotBlank(fallbackKey)) {
            value = map.get(fallbackKey);
        }
        return StringUtils.trimToNull(value == null ? null : String.valueOf(value));
    }

    private Integer findInt(Map<String, Object> map, String path, String fallbackKey) {
        Object value = findValue(map, path);
        if (value == null && StringUtils.isNotBlank(fallbackKey)) {
            value = map.get(fallbackKey);
        }
        if (value == null) {
            return null;
        }
        return parseInt(value, Integer.MIN_VALUE) == Integer.MIN_VALUE ? null : parseInt(value, Integer.MIN_VALUE);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(StringUtils.defaultString(value), StandardCharsets.UTF_8);
    }

    private long parseLong(Object value, long defaultValue) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private int parseInt(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private record SpeechProvider(String baseUrl, String transcriptionsPath, String apiKey, String model,
                                  int timeoutSeconds) {
    }

    private record BaiduAccessToken(String token, Instant expiresAt) {
    }
}
