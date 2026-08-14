package cn.cordys.crm.task.service;

import cn.cordys.common.exception.GenericException;
import cn.cordys.crm.task.config.TaskAttachmentProperties;
import cn.cordys.crm.task.constants.TaskAttachmentScene;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.DigestInputStream;
import java.util.HexFormat;
import java.util.Locale;

@Service
public class TaskAttachmentStorageService {

    private final TaskAttachmentProperties properties;

    public TaskAttachmentStorageService(TaskAttachmentProperties properties) {
        this.properties = properties;
    }

    public void validateFiles(java.util.List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new GenericException("请选择要上传的附件");
        }
        if (files.size() > properties.getMaxFiles()) {
            throw new GenericException("一次最多上传 " + properties.getMaxFiles() + " 个附件");
        }
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                throw new GenericException("附件内容为空");
            }
            if (file.getSize() > properties.getMaxFileSize().toBytes()) {
                throw new GenericException("单个附件不能超过 " + properties.getMaxFileSize().toMegabytes() + "MB");
            }
            if (safeOriginalName(file.getOriginalFilename()).length() > 512) {
                throw new GenericException("附件名称不能超过 512 个字符");
            }
        }
    }

    public StoredFile store(MultipartFile file, String organizationId, String taskId,
                            TaskAttachmentScene scene, String attachmentId) {
        String originalName = safeOriginalName(file.getOriginalFilename());
        String extension = safeExtension(originalName);
        String storedName = attachmentId + extension;
        Path relativePath = Path.of(organizationId, taskId, scene.name().toLowerCase(Locale.ROOT), storedName);
        Path target = resolveSafe(relativePath.toString());
        try {
            Files.createDirectories(target.getParent());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = file.getInputStream(); DigestInputStream digestInput = new DigestInputStream(input, digest)) {
                Files.copy(digestInput, target, StandardCopyOption.REPLACE_EXISTING);
            }
            String contentType = StringUtils.abbreviate(StringUtils.defaultIfBlank(file.getContentType(),
                    Files.probeContentType(target)), 255);
            return new StoredFile(normalizeRelative(relativePath), originalName, contentType,
                    file.getSize(), HexFormat.of().formatHex(digest.digest()));
        } catch (IOException | NoSuchAlgorithmException e) {
            delete(normalizeRelative(relativePath));
            throw new GenericException("任务附件保存失败", e);
        }
    }

    public ResponseEntity<org.springframework.core.io.Resource> resource(String storagePath, String originalName,
                                                                         String contentType, long size,
                                                                         boolean inline) {
        Path file = resolveSafe(storagePath);
        if (!Files.isRegularFile(file)) {
            throw new GenericException("附件不存在或已被删除");
        }
        try {
            MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM;
            if (StringUtils.isNotBlank(contentType)) {
                try {
                    mediaType = MediaType.parseMediaType(contentType);
                } catch (Exception ignored) {
                    // Fall back to a safe binary content type.
                }
            }
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            (inline ? "inline" : "attachment") + "; filename*=UTF-8''" + encodeName(originalName))
                    .contentLength(size)
                    .contentType(mediaType)
                    .body(new InputStreamResource(Files.newInputStream(file)));
        } catch (IOException e) {
            throw new GenericException("任务附件读取失败", e);
        }
    }

    public void delete(String storagePath) {
        if (StringUtils.isBlank(storagePath)) {
            return;
        }
        try {
            Files.deleteIfExists(resolveSafe(storagePath));
        } catch (IOException e) {
            throw new GenericException("任务附件删除失败", e);
        }
    }

    private Path resolveSafe(String storagePath) {
        Path root = Path.of(properties.getRoot()).toAbsolutePath().normalize();
        Path resolved = root.resolve(storagePath).normalize();
        if (!resolved.startsWith(root)) {
            throw new GenericException("非法的任务附件路径");
        }
        return resolved;
    }

    private String normalizeRelative(Path relativePath) {
        return relativePath.toString().replace('\\', '/');
    }

    private String safeOriginalName(String fileName) {
        String normalized = StringUtils.defaultIfBlank(fileName, "attachment").replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        return StringUtils.defaultIfBlank(name, "attachment");
    }

    private String safeExtension(String fileName) {
        String extension = FilenameUtils.getExtension(fileName);
        return extension.matches("[A-Za-z0-9]{1,10}") ? "." + extension.toLowerCase(Locale.ROOT) : "";
    }

    private String encodeName(String fileName) {
        return URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public record StoredFile(String storagePath, String originalName, String contentType,
                             long sizeBytes, String sha256Hex) {
    }
}
