package com.smallrag.ragmcp.rag;

import com.smallrag.ragmcp.config.RagProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueryCircuitBreakerTest {

    @Test
    void opensAfterFailureThresholdAndFailsFast() {
        QueryCircuitBreaker breaker = new QueryCircuitBreaker(config(true));

        assertThatThrownBy(() -> breaker.execute(failing()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> breaker.execute(failing()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(breaker.isOpen()).isTrue();
        assertThat(breaker.state()).isEqualTo("OPEN");
        assertThatThrownBy(() -> breaker.execute(failing()))
                .isInstanceOf(QueryUnavailableException.class)
                .hasMessageContaining("熔断");
    }

    @Test
    void successPassesThrough() {
        QueryCircuitBreaker breaker = new QueryCircuitBreaker(config(true));

        assertThat(breaker.execute(() -> "ok")).isEqualTo("ok");
        assertThat(breaker.isOpen()).isFalse();
        assertThat(breaker.state()).isEqualTo("CLOSED");
    }

    @Test
    void disabledBreakerCallsSupplierDirectly() {
        QueryCircuitBreaker breaker = new QueryCircuitBreaker(config(false));

        assertThat(breaker.execute(() -> "ok")).isEqualTo("ok");
        assertThat(breaker.state()).isEqualTo("DISABLED");
        assertThatThrownBy(() -> breaker.execute(failing()))
                .isInstanceOf(IllegalStateException.class);
    }

    private static RagProperties.QueryBreaker config(boolean enabled) {
        RagProperties.QueryBreaker properties = new RagProperties.QueryBreaker();
        properties.setEnabled(enabled);
        properties.setSlidingWindowSize(2);
        properties.setMinimumNumberOfCalls(2);
        properties.setFailureRateThreshold(50f);
        properties.setWaitDurationInOpenState(Duration.ofMinutes(1));
        properties.setSlowCallDurationThreshold(Duration.ofMinutes(1));
        properties.setSlowCallRateThreshold(100f);
        return properties;
    }

    private static java.util.function.Supplier<String> failing() {
        return () -> {
            throw new IllegalStateException("embedding down");
        };
    }
}
