package mailmonitoring.model;

public class FollowRecordResponse {
    private final boolean success;
    private final String followRecordId;
    private final boolean deduplicated;

    public FollowRecordResponse(boolean success, String followRecordId, boolean deduplicated) {
        this.success = success;
        this.followRecordId = followRecordId;
        this.deduplicated = deduplicated;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getFollowRecordId() {
        return followRecordId;
    }

    public boolean isDeduplicated() {
        return deduplicated;
    }
}
