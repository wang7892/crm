package mailmonitoring.repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class DynamicMailboxAccount {
    private final String ownerId;
    private final String sourceMailbox;
    private final String imapUser;
    private final String imapAuthCode;
    private final List<String> targetMailboxes;

    public DynamicMailboxAccount(String ownerId, String sourceMailbox, String imapUser, String imapAuthCode,
                                 List<String> targetMailboxes) {
        this.ownerId = ownerId;
        this.sourceMailbox = normalize(sourceMailbox);
        this.imapUser = normalize(imapUser);
        this.imapAuthCode = imapAuthCode == null ? "" : imapAuthCode.trim();
        this.targetMailboxes = normalizeTargets(targetMailboxes);
    }

    public String getOwnerId() {
        return ownerId;
    }

    public String getSourceMailbox() {
        return sourceMailbox;
    }

    public String getImapUser() {
        return imapUser;
    }

    public String getImapAuthCode() {
        return imapAuthCode;
    }

    public List<String> getTargetMailboxes() {
        return targetMailboxes;
    }

    public String identityKey() {
        return sourceMailbox;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static List<String> normalizeTargets(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String value : values) {
            String normalized = normalize(value);
            if (!normalized.isBlank() && !result.contains(normalized)) {
                result.add(normalized);
            }
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DynamicMailboxAccount)) {
            return false;
        }
        DynamicMailboxAccount that = (DynamicMailboxAccount) other;
        return Objects.equals(ownerId, that.ownerId)
                && Objects.equals(sourceMailbox, that.sourceMailbox)
                && Objects.equals(imapUser, that.imapUser)
                && Objects.equals(imapAuthCode, that.imapAuthCode)
                && Objects.equals(targetMailboxes, that.targetMailboxes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ownerId, sourceMailbox, imapUser, imapAuthCode, targetMailboxes);
    }
}
