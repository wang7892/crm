package cn.cordys.crm.integration.mls.service;

final class MlsMirrorSyncProtection {

    static final int REQUIRED_CONSECUTIVE_MISSING_PASSES = 2;
    /** Orders confirmed absent in a complete pass are removed in that pass. */
    static final int REQUIRED_ORDER_MISSING_PASSES = 1;

    private MlsMirrorSyncProtection() {
    }

    static boolean allowsMirrorPass(long externalCount, long mappedCount) {
        if (externalCount <= 0) {
            return false;
        }
        if (mappedCount <= 0) {
            return true;
        }
        long minimumSafeCount = Math.floorDiv(Math.multiplyExact(mappedCount, 9) + 9, 10);
        return externalCount >= minimumSafeCount;
    }

    static boolean isComplete(SourceSnapshot start, SourceSnapshot end, long rowsRead, long failedRows) {
        return start != null
                && start.rowCount() > 0
                && start.equals(end)
                && rowsRead == start.rowCount()
                && failedRows == 0;
    }

    static boolean allowsDelete(int consecutiveMissingPasses) {
        return consecutiveMissingPasses >= REQUIRED_CONSECUTIVE_MISSING_PASSES;
    }

    record SourceSnapshot(long rowCount, long maxId) {
    }
}
