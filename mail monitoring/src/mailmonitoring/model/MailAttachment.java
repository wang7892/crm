package mailmonitoring.model;

public class MailAttachment {
    private final String fileName;
    private final String contentType;
    private final long sizeBytes;
    private final String savedPath;

    public MailAttachment(String fileName, String contentType, long sizeBytes, String savedPath) {
        this.fileName = fileName == null ? "" : fileName;
        this.contentType = contentType == null ? "application/octet-stream" : contentType;
        this.sizeBytes = Math.max(sizeBytes, 0L);
        this.savedPath = savedPath == null ? "" : savedPath;
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

    public String getSavedPath() {
        return savedPath;
    }
}
