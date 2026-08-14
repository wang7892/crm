package cn.cordys.crm.integration.mls.service;

final class MlsMirrorSyncProtection {

    static final int REQUIRED_CONSECUTIVE_MISSING_PASSES = 2;

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
