package cn.cordys.crm.integration.mls.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MlsAgentDataSyncScheduleConfigTest {

    @Test
    void shouldConvertDailyTimeToQuartzCron() {
        assertThat(MlsAgentDataSyncScheduleConfig.toDailyCron("00:00")).isEqualTo("0 0 0 * * ?");
        assertThat(MlsAgentDataSyncScheduleConfig.toDailyCron("09:30")).isEqualTo("0 30 9 * * ?");
        assertThat(MlsAgentDataSyncScheduleConfig.toDailyCron("23:59")).isEqualTo("0 59 23 * * ?");
    }

    @Test
    void shouldRejectInvalidDailyTime() {
        for (String invalidTime : List.of("9:30", "24:00", "09:60", "09:30:00", "")) {
            assertThatThrownBy(() -> MlsAgentDataSyncScheduleConfig.toDailyCron(invalidTime))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("crm.mls-sync.time")
                    .hasMessageContaining("HH:mm");
        }
    }
}
