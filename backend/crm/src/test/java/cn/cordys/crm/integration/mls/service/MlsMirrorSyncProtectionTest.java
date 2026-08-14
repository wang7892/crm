package cn.cordys.crm.integration.mls.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MlsMirrorSyncProtectionTest {

    @Test
    void shouldRejectEmptyOrMoreThanTenPercentSourceDrop() {
        assertThat(MlsMirrorSyncProtection.allowsMirrorPass(0, 100)).isFalse();
        assertThat(MlsMirrorSyncProtection.allowsMirrorPass(89, 100)).isFalse();
        assertThat(MlsMirrorSyncProtection.allowsMirrorPass(90, 100)).isTrue();
        assertThat(MlsMirrorSyncProtection.allowsMirrorPass(1, 0)).isTrue();
    }

    @Test
    void shouldRequireStableFullyProcessedSnapshot() {
        MlsMirrorSyncProtection.SourceSnapshot snapshot =
                new MlsMirrorSyncProtection.SourceSnapshot(100, 108);

        assertThat(MlsMirrorSyncProtection.isComplete(snapshot, snapshot, 100, 0)).isTrue();
        assertThat(MlsMirrorSyncProtection.isComplete(snapshot, snapshot, 99, 0)).isFalse();
        assertThat(MlsMirrorSyncProtection.isComplete(snapshot, snapshot, 100, 1)).isFalse();
        assertThat(MlsMirrorSyncProtection.isComplete(snapshot,
                new MlsMirrorSyncProtection.SourceSnapshot(101, 109), 100, 0)).isFalse();
    }

    @Test
    void shouldDeleteOnlyAfterTwoConsecutiveCompleteMissingPasses() {
        assertThat(MlsMirrorSyncProtection.allowsDelete(0)).isFalse();
        assertThat(MlsMirrorSyncProtection.allowsDelete(1)).isFalse();
        assertThat(MlsMirrorSyncProtection.allowsDelete(2)).isTrue();
        assertThat(MlsMirrorSyncProtection.allowsDelete(3)).isTrue();
    }
}
