package wecommonitoring.service;

import wecommonitoring.client.FinanceSdkBridge;
import wecommonitoring.repository.JdbcWeComMediaRepository;
import wecommonitoring.repository.JdbcWeComMediaRepository.PendingMedia;
import wecommonitoring.util.Ids;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

public class WeComMediaLandingService {
    private final String organizationId;
    private final FinanceSdkBridge financeSdkBridge;
    private final JdbcWeComMediaRepository mediaRepository;
    private final Path attachmentBaseDir;
    private final String archiveProxy;
    private final String archiveProxyPassword;
    private final int archiveTimeoutSeconds;
    private final int batchSize;
    private boolean warnedUnavailable;

    public WeComMediaLandingService(String organizationId,
                                    FinanceSdkBridge financeSdkBridge,
                                    JdbcWeComMediaRepository mediaRepository,
                                    String attachmentBaseDir,
                                    String archiveProxy,
                                    String archiveProxyPassword,
                                    int archiveTimeoutSeconds,
                                    int batchSize) {
        this.organizationId = organizationId;
        this.financeSdkBridge = financeSdkBridge;
        this.mediaRepository = mediaRepository;
        this.attachmentBaseDir = Path.of(firstNonBlank(attachmentBaseDir, "/opt/cordys/data/files"));
        this.archiveProxy = archiveProxy == null ? "" : archiveProxy;
        this.archiveProxyPassword = archiveProxyPassword == null ? "" : archiveProxyPassword;
        this.archiveTimeoutSeconds = Math.max(1, archiveTimeoutSeconds);
        this.batchSize = Math.max(1, batchSize);
    }

    public void processPending() {
        if (!financeSdkBridge.available() || !financeSdkBridge.canPullMediaData()) {
            warnUnavailableOnce();
            return;
        }
        List<PendingMedia> pending = mediaRepository.listPendingImageVideo(organizationId, batchSize);
        if (pending.isEmpty()) {
            return;
        }
        int success = 0;
        int fail = 0;
        for (PendingMedia media : pending) {
            try {
                processOne(media);
                success++;
            } catch (Exception ex) {
                fail++;
                mediaRepository.markFail(media.getId());
                System.err.printf("[WECOM_MEDIA] media landing failed, mediaId=%s, type=%s, err=%s%n",
                        media.getId(), media.getMediaType(), ex.getMessage());
            }
        }
        System.out.printf("[WECOM_MEDIA] processed pending media, picked=%d, success=%d, fail=%d%n",
                pending.size(), success, fail);
    }

    private void processOne(PendingMedia media) throws Exception {
        byte[] bytes = financeSdkBridge.getMediaData(
                media.getSdkFileId(),
                archiveProxy,
                archiveProxyPassword,
                archiveTimeoutSeconds
        );
        if (bytes.length == 0) {
            throw new IllegalStateException("downloaded media is empty");
        }
        String attachmentId = Ids.newId();
        String fileName = normalizeFileName(media, bytes);
        String mimeType = firstNonBlank(media.getMimeType(), inferMimeType(fileName, media.getMediaType(), bytes));
        Path file = targetFile(media, attachmentId, fileName);
        Files.createDirectories(file.getParent());
        Files.write(file, bytes, StandardOpenOption.CREATE_NEW);
        try {
            mediaRepository.markSuccess(media, attachmentId, fileName, mimeType, bytes.length, sha256Hex(bytes));
        } catch (RuntimeException ex) {
            deleteQuietly(file);
            throw ex;
        }
    }

    private Path targetFile(PendingMedia media, String attachmentId, String fileName) {
        return attachmentBaseDir
                .resolve(safePathSegment(media.getOrganizationId()))
                .resolve("pic")
                .resolve(safePathSegment(media.getFollowRecordId()))
                .resolve(safePathSegment(attachmentId))
                .resolve(fileName);
    }

    private String normalizeFileName(PendingMedia media, byte[] bytes) {
        String raw = firstNonBlank(media.getFileName(), "wecom-" + firstNonBlank(media.getId(), Ids.newId()));
        String cleaned = raw.replace('\\', '_').replace('/', '_')
                .replaceAll("[<>:\"|?*\\p{Cntrl}]", "_")
                .trim();
        while (cleaned.startsWith(".")) {
            cleaned = cleaned.substring(1);
        }
        while (cleaned.endsWith(".")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        if (cleaned.isBlank()) {
            cleaned = "wecom-" + firstNonBlank(media.getId(), Ids.newId());
        }
        String ext = extensionOf(cleaned);
        if (ext.isBlank()) {
            ext = inferExtension(media.getMediaType(), media.getMimeType(), media.getExtraJson(), bytes);
            cleaned = cleaned + "." + ext;
        }
        return truncateFileName(cleaned, 255);
    }

    private static String truncateFileName(String fileName, int maxLength) {
        if (fileName.length() <= maxLength) {
            return fileName;
        }
        String ext = extensionOf(fileName);
        String suffix = ext.isBlank() ? "" : "." + ext;
        int baseMax = Math.max(1, maxLength - suffix.length());
        String base = suffix.isEmpty() ? fileName : fileName.substring(0, fileName.length() - suffix.length());
        return base.substring(0, Math.min(base.length(), baseMax)) + suffix;
    }

    private static String inferExtension(String mediaType, String mimeType, String extraJson, byte[] bytes) {
        String mime = lower(mimeType);
        if (mime.contains("png")) {
            return "png";
        }
        if (mime.contains("gif")) {
            return "gif";
        }
        if (mime.contains("webp")) {
            return "webp";
        }
        if (mime.contains("jpeg") || mime.contains("jpg")) {
            return "jpg";
        }
        if (mime.contains("quicktime")) {
            return "mov";
        }
        if (mime.contains("mp4")) {
            return "mp4";
        }
        if (mime.contains("mpeg") || mime.contains("mp3")) {
            return "mp3";
        }
        if (mime.contains("wav")) {
            return "wav";
        }
        if (mime.contains("amr")) {
            return "amr";
        }
        if (mime.contains("pdf")) {
            return "pdf";
        }
        if (mime.contains("wordprocessingml")) {
            return "docx";
        }
        if (mime.contains("spreadsheetml")) {
            return "xlsx";
        }
        if (mime.contains("presentationml")) {
            return "pptx";
        }
        if (mime.contains("msword")) {
            return "doc";
        }
        if (mime.contains("excel") || mime.contains("spreadsheet")) {
            return "xls";
        }
        if (mime.contains("powerpoint") || mime.contains("presentation")) {
            return "ppt";
        }
        if (mime.contains("zip")) {
            return "zip";
        }
        if (looksLikePng(bytes)) {
            return "png";
        }
        if (looksLikeGif(bytes)) {
            return "gif";
        }
        if (looksLikeWebp(bytes)) {
            return "webp";
        }
        if (looksLikeJpeg(bytes)) {
            return "jpg";
        }
        if (looksLikeMp4(bytes)) {
            return "mp4";
        }
        if (looksLikeAmr(bytes)) {
            return "amr";
        }
        if ("emotion".equalsIgnoreCase(mediaType)) {
            return emotionType(extraJson) == 2 ? "png" : "gif";
        }
        if ("voice".equalsIgnoreCase(mediaType)) {
            return "amr";
        }
        if ("file".equalsIgnoreCase(mediaType)) {
            return "bin";
        }
        return "video".equalsIgnoreCase(mediaType) ? "mp4" : "jpg";
    }

    private static String inferMimeType(String fileName, String mediaType, byte[] bytes) {
        String ext = extensionOf(fileName).toLowerCase(Locale.ROOT);
        return switch (ext) {
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "jpg", "jpeg" -> "image/jpeg";
            case "mov" -> "video/quicktime";
            case "mp4", "m4v" -> "video/mp4";
            case "amr" -> "audio/amr";
            case "mp3" -> "audio/mpeg";
            case "wav" -> "audio/wav";
            case "m4a" -> "audio/mp4";
            case "pdf" -> "application/pdf";
            case "doc" -> "application/msword";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xls" -> "application/vnd.ms-excel";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "ppt" -> "application/vnd.ms-powerpoint";
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "txt" -> "text/plain";
            case "csv" -> "text/csv";
            case "zip" -> "application/zip";
            case "rar" -> "application/vnd.rar";
            case "7z" -> "application/x-7z-compressed";
            default -> {
                if ("video".equalsIgnoreCase(mediaType)) {
                    yield "video/mp4";
                }
                if ("voice".equalsIgnoreCase(mediaType)) {
                    yield "audio/amr";
                }
                if ("file".equalsIgnoreCase(mediaType)) {
                    yield "application/octet-stream";
                }
                yield "emotion".equalsIgnoreCase(mediaType) ? "image/gif" : "image/jpeg";
            }
        };
    }

    private static int emotionType(String extraJson) {
        if (extraJson == null || extraJson.isBlank()) {
            return 0;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"type\"\\s*:\\s*\"?(\\d+)\"?")
                .matcher(extraJson);
        if (!matcher.find()) {
            return 0;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(bytes));
    }

    private void warnUnavailableOnce() {
        if (warnedUnavailable) {
            return;
        }
        warnedUnavailable = true;
        String reason = financeSdkBridge.available()
                ? "Finance.GetMediaData method group not found"
                : financeSdkBridge.disabledReason();
        System.err.println("[WECOM_MEDIA] media fetch disabled: " + reason);
    }

    private static boolean looksLikeJpeg(byte[] bytes) {
        return bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xFF
                && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF;
    }

    private static boolean looksLikePng(byte[] bytes) {
        return bytes.length >= 8
                && (bytes[0] & 0xFF) == 0x89
                && bytes[1] == 'P'
                && bytes[2] == 'N'
                && bytes[3] == 'G';
    }

    private static boolean looksLikeGif(byte[] bytes) {
        return bytes.length >= 6
                && bytes[0] == 'G'
                && bytes[1] == 'I'
                && bytes[2] == 'F';
    }

    private static boolean looksLikeWebp(byte[] bytes) {
        return bytes.length >= 12
                && bytes[0] == 'R'
                && bytes[1] == 'I'
                && bytes[2] == 'F'
                && bytes[3] == 'F'
                && bytes[8] == 'W'
                && bytes[9] == 'E'
                && bytes[10] == 'B'
                && bytes[11] == 'P';
    }

    private static boolean looksLikeMp4(byte[] bytes) {
        return bytes.length >= 12
                && bytes[4] == 'f'
                && bytes[5] == 't'
                && bytes[6] == 'y'
                && bytes[7] == 'p';
    }

    private static boolean looksLikeAmr(byte[] bytes) {
        return bytes.length >= 6
                && bytes[0] == '#'
                && bytes[1] == '!'
                && bytes[2] == 'A'
                && bytes[3] == 'M'
                && bytes[4] == 'R'
                && bytes[5] == '\n';
    }

    private static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int idx = fileName.lastIndexOf('.');
        if (idx < 0 || idx >= fileName.length() - 1) {
            return "";
        }
        return fileName.substring(idx + 1);
    }

    private static String safePathSegment(String value) {
        String out = firstNonBlank(value, "_")
                .replace('\\', '_')
                .replace('/', '_')
                .replaceAll("[<>:\"|?*\\p{Cntrl}]", "_")
                .trim();
        return out.isBlank() ? "_" : out;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
        }
    }
}
