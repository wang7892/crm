package cn.cordys.crm.integration.mls.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MlsExternalQueryRetryTest {

    @Test
    void shouldRetryConnectionFailuresWithConfiguredDelays() {
        List<Long> delays = new ArrayList<>();
        MlsExternalQueryRetry retry = new MlsExternalQueryRetry(delays::add);
        AtomicInteger attempts = new AtomicInteger();

        String result = retry.execute("customer_info", () -> {
            if (attempts.getAndIncrement() < 3) {
                throw new CannotGetJdbcConnectionException("temporary connection failure");
            }
            return "success";
        });

        assertThat(result).isEqualTo("success");
        assertThat(attempts).hasValue(4);
        assertThat(delays).containsExactly(5L, 15L, 30L);
    }

    @Test
    void shouldStopAfterThreeRetries() {
        List<Long> delays = new ArrayList<>();
        MlsExternalQueryRetry retry = new MlsExternalQueryRetry(delays::add);
        AtomicInteger attempts = new AtomicInteger();
        CannotGetJdbcConnectionException failure =
                new CannotGetJdbcConnectionException("persistent connection failure");

        assertThatThrownBy(() -> retry.execute("contract_info", () -> {
            attempts.incrementAndGet();
            throw failure;
        })).isSameAs(failure);

        assertThat(attempts).hasValue(4);
        assertThat(delays).containsExactly(5L, 15L, 30L);
    }

    @Test
    void shouldNotRetryNonConnectionErrors() {
        List<Long> delays = new ArrayList<>();
        MlsExternalQueryRetry retry = new MlsExternalQueryRetry(delays::add);
        AtomicInteger attempts = new AtomicInteger();
        IllegalStateException failure = new IllegalStateException("invalid query");

        assertThatThrownBy(() -> retry.execute("order_info", () -> {
            attempts.incrementAndGet();
            throw failure;
        })).isSameAs(failure);

        assertThat(attempts).hasValue(1);
        assertThat(delays).isEmpty();
    }
}
