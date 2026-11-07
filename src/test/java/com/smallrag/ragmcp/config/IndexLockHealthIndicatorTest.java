package com.smallrag.ragmcp.config;

import com.smallrag.ragmcp.rag.IndexActivityGuard;
import com.smallrag.ragmcp.rag.QueryCircuitBreaker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.mock.env.MockEnvironment;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IndexLockHealthIndicatorTest {

    @Test
    void reportsInstanceLockAndCircuitState() {
        DistributedProperties properties = new DistributedProperties();
        properties.setEnabled(true);
        properties.setInstanceId("replica-1");
        IndexActivityGuard guard = mock(IndexActivityGuard.class);
        when(guard.owner()).thenReturn(Optional.of(IndexActivityGuard.SOURCES));

        QueryCircuitBreaker breaker = new QueryCircuitBreaker(new RagProperties());
        IndexLockHealthIndicator indicator = new IndexLockHealthIndicator(
                properties, guard, breaker, new MockEnvironment());

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("instanceId", "replica-1")
                .containsEntry("distributed", true)
                .containsEntry("activeLock", IndexActivityGuard.SOURCES)
                .containsEntry("queryCircuit", "CLOSED");
    }
}
