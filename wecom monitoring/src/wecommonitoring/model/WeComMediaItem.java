package wecommonitoring.model;

public class WeComMediaItem {
    private final int mediaIndex;
    private final String mediaType;
    private final String sdkFileId;
    private final String fileName;
    private final String mimeType;
    private final Long sizeBytes;
    private final Integer durationMs;
    private final String sha256Hex;
    private final String extraJson;

    public WeComMediaItem(int mediaIndex, String mediaType, String sdkFileId, String fileName,
                          String mimeType, Long sizeBytes, Integer durationMs, String sha256Hex,
                          String extraJson) {
        this.mediaIndex = mediaIndex;
        this.mediaType = blankToNull(mediaType);
        this.sdkFileId = blankToNull(sdkFileId);
        this.fileName = blankToNull(fileName);
        this.mimeType = blankToNull(mimeType);
        this.sizeBytes = sizeBytes;
        this.durationMs = durationMs;
        this.sha256Hex = blankToNull(sha256Hex);
        this.extraJson = blankToNull(extraJson);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public int getMediaIndex() {
        return mediaIndex;
    }

    public String getMediaType() {
        return mediaType;
    }

    public String getSdkFileId() {
        return sdkFileId;
    }

    public String getFileName() {
        return fileName;
    }

    public String getMimeType() {
        return mimeType;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public Integer getDurationMs() {
        return durationMs;
    }

    public String getSha256Hex() {
        return sha256Hex;
    }

    public String getExtraJson() {
        return extraJson;
    }
}
