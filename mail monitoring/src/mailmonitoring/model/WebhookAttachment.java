package mailmonitoring.model;

public class WebhookAttachment {
    private final String fileName;
    private final String contentType;
    private final long sizeBytes;
    private final String downloadUrl;

    public WebhookAttachment(String fileName, String contentType, long sizeBytes, String downloadUrl) {
        this.fileName = fileName == null ? "" : fileName;
        this.contentType = contentType == null ? "application/octet-stream" : contentType;
        this.sizeBytes = Math.max(sizeBytes, 0L);
        this.downloadUrl = downloadUrl == null ? "" : downloadUrl;
    }

    public String getFileName() {
        return fileName;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getDownloadUrl() {
        return downloadUrl;
    }
}

