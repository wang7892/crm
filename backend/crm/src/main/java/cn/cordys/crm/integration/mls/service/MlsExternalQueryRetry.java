package cn.cordys.crm.integration.mls.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Slf4j
final class MlsExternalQueryRetry {

    private static final long[] RETRY_DELAYS_SECONDS = {5, 15, 30};

    private final Sleeper sleeper;

    MlsExternalQueryRetry() {
        this(seconds -> TimeUnit.SECONDS.sleep(seconds));
    }

    MlsExternalQueryRetry(Sleeper sleeper) {
        this.sleeper = sleeper;
    }

    <T> T execute(String sourceTable, Supplier<T> query) {
        int retryIndex = 0;
        while (true) {
            try {
                return query.get();
            } catch (CannotGetJdbcConnectionException connectionException) {
                if (retryIndex >= RETRY_DELAYS_SECONDS.length) {
                    throw connectionException;
                }

                long delaySeconds = RETRY_DELAYS_SECONDS[retryIndex];
                log.warn("MLS external query connection failed, sourceTable={}, retry={}/{}, delaySeconds={}",
                        sourceTable, retryIndex + 1, RETRY_DELAYS_SECONDS.length, delaySeconds);
                try {
                    sleeper.sleep(delaySeconds);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    connectionException.addSuppressed(interruptedException);
                    throw connectionException;
                }
                retryIndex++;
            }
        }
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long seconds) throws InterruptedException;
    }
}
