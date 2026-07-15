package cn.cordys.crm.aiagent.service;

import cn.cordys.common.exception.GenericException;
import cn.cordys.common.response.result.CrmHttpResultCode;
import cn.cordys.crm.aiagent.config.AiAgentBosProperties;
import com.baidubce.auth.DefaultBceCredentials;
import com.baidubce.services.bos.BosClient;
import com.baidubce.services.bos.BosClientConfiguration;
import com.baidubce.services.bos.model.ObjectMetadata;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URL;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

@Service
public class AiAgentBosAudioStorageService {

    private static final DateTimeFormatter DAY_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    private final AiAgentBosProperties bosProperties;

    public AiAgentBosAudioStorageService(AiAgentBosProperties bosProperties) {
        this.bosProperties = bosProperties;
    }

    public StoredAudio uploadAndSign(MultipartFile file) throws IOException {
        validateConfig();
        String objectKey = buildObjectKey(file);
        BosClient client = createClient();
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(file.getSize());
        metadata.setContentType(StringUtils.defaultIfBlank(file.getContentType(), "application/octet-stream"));
        client.putObject(bosProperties.getBucket(), objectKey, file.getInputStream(), metadata);
        int expireSeconds = Math.max(1, bosProperties.getSignedUrlExpireMinutes()) * 60;
        URL signedUrl = client.generatePresignedUrl(bosProperties.getBucket(), objectKey, expireSeconds);
        return new StoredAudio(objectKey, signedUrl.toString());
    }

    private BosClient createClient() {
        BosClientConfiguration configuration = new BosClientConfiguration();
        configuration.setCredentials(new DefaultBceCredentials(
                StringUtils.trimToEmpty(bosProperties.getAccessKeyId()),
                StringUtils.trimToEmpty(bosProperties.getSecretAccessKey())));
        configuration.setEndpoint(StringUtils.removeEnd(StringUtils.trimToEmpty(bosProperties.getEndpoint()), "/"));
        int timeoutMillis = Math.max(1, bosProperties.getUploadTimeoutSeconds()) * 1000;
        configuration.setConnectionTimeoutInMillis(timeoutMillis);
        configuration.setSocketTimeoutInMillis(timeoutMillis);
        return new BosClient(configuration);
    }

    private void validateConfig() {
        if (StringUtils.isAnyBlank(
                bosProperties.getEndpoint(),
                bosProperties.getBucket(),
                bosProperties.getAccessKeyId(),
                bosProperties.getSecretAccessKey())) {
            throw new GenericException(CrmHttpResultCode.FAILED,
                    "\u767e\u5ea6 BOS \u672a\u914d\u7f6e\uff0c\u8bf7\u586b\u5199 crm.ai-agent.bos.endpoint\u3001bucket\u3001access-key-id \u548c secret-access-key");
        }
    }

    private String buildObjectKey(MultipartFile file) {
        String prefix = StringUtils.defaultIfBlank(bosProperties.getObjectPrefix(), "crm-audio/");
        prefix = StringUtils.replace(prefix, "\\", "/");
        prefix = StringUtils.removeStart(prefix, "/");
        if (StringUtils.isNotBlank(prefix) && !prefix.endsWith("/")) {
            prefix += "/";
        }
        String extension = resolveExtension(file);
        return prefix
                + LocalDate.now().format(DAY_FORMATTER)
                + "/"
                + UUID.randomUUID().toString().replace("-", "")
                + extension;
    }

    private String resolveExtension(MultipartFile file) {
        String filename = StringUtils.defaultString(file.getOriginalFilename());
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex >= 0 && dotIndex < filename.length() - 1) {
            String extension = filename.substring(dotIndex).toLowerCase(Locale.ROOT);
            if (extension.matches("\\.(mp3|mp4|mpeg|mpga|m4a|wav|webm|aac|ogg|oga|flac|amr|pcm)$")) {
                return extension;
            }
        }
        return ".audio";
    }

    public record StoredAudio(String objectKey, String signedUrl) {
    }
}
