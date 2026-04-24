package mailmonitoring.service;

import java.time.Duration;
import java.util.List;

public class RetryPolicy {
    private static final List<Duration> BACKOFF = List.of(
            Duration.ofMinutes(1),
            Duration.ofMinutes(5),
            Duration.ofMinutes(15),
            Duration.ofHours(1)
    );

    public Duration nextDelay(int currentRetryCount) {
        if (currentRetryCount < 0 || currentRetryCount >= BACKOFF.size()) {
            return null;
        }
        return BACKOFF.get(currentRetryCount);
    }
}
