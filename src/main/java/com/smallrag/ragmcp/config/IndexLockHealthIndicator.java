package com.smallrag.ragmcp.config;

import com.smallrag.ragmcp.rag.IndexActivityGuard;
import com.smallrag.ragmcp.rag.QueryCircuitBreaker;
import com.smallrag.ragmcp.rag.sources.SourcesLeaderElection;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 索引/检索健康信息：实例标识、当前活动锁、熔断状态与运行 profile。
 *
 * <p>多副本排障时用它确认“当前哪个副本持有索引写锁”以及熔断是否打开。
 * 只暴露非敏感字段。
 *
 * @author zjk
 */
@Component
@RequiredArgsConstructor
public class IndexLockHealthIndicator implements HealthIndicator {

    private final DistributedProperties distributedProperties;
    private final IndexActivityGuard activityGuard;
    private final SourcesLeaderElection leaderElection;
    private final QueryCircuitBreaker queryCircuitBreaker;
    private final Environment environment;

    @Override
    public Health health() {
        List<String> profiles = Arrays.asList(environment.getActiveProfiles());
        return Health.up()
                .withDetail("instanceId", distributedProperties.getInstanceId())
                .withDetail("distributed", distributedProperties.isEnabled())
                .withDetail("profiles", profiles)
                .withDetail("activeLock", activityGuard.owner().orElse("none"))
                .withDetail("sourcesLeader", leaderElection.isLeader())
                .withDetail("queryCircuit", queryCircuitBreaker.state())
                .withDetail("queryFailureRate", queryCircuitBreaker.failureRate())
                .build();
    }
}
