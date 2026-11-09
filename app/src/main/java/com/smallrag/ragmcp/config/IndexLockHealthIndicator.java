package com.smallrag.ragmcp.config;

import com.smallrag.ragmcp.rag.IndexActivityGuard;
import com.smallrag.ragmcp.rag.QueryCircuitBreaker;
import com.smallrag.ragmcp.rag.archive.DocumentArchive;
import com.smallrag.ragmcp.rag.reindex.ReindexTaskService;
import com.smallrag.ragmcp.rag.sources.SourcesLeaderElection;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 索引/检索健康信息：实例标识、当前活动锁、重建任务与归档卷、熔断状态与运行 profile。
 *
 * <p>多副本排障时用它确认“当前哪个副本持有索引写锁/任务租约”以及熔断是否打开。
 * 只暴露非敏感字段。
 *
 * @author zjk
 */
@Component
@RequiredArgsConstructor
public class IndexLockHealthIndicator implements HealthIndicator {

    private final DistributedProperties distributedProperties;
    private final ArchiveProperties archiveProperties;
    private final IndexActivityGuard activityGuard;
    private final SourcesLeaderElection leaderElection;
    private final QueryCircuitBreaker queryCircuitBreaker;
    private final DocumentArchive documentArchive;
    private final ReindexTaskService reindexTaskService;
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
                .withDetail("archive", archiveDetails())
                .withDetail("reindexTask", reindexTaskDetails())
                .withDetail("queryCircuit", queryCircuitBreaker.state())
                .withDetail("queryFailureRate", queryCircuitBreaker.failureRate())
                .build();
    }

    private Map<String, Object> archiveDetails() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("enabled", documentArchive.isEnabled());
        details.put("writable", documentArchive.isWritable());
        details.put("dir", documentArchive.directory().toString());
        details.put("shared", archiveProperties.isShared());
        return details;
    }

    private Map<String, Object> reindexTaskDetails() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("stateStore", reindexTaskService.stateStoreLocation());
        details.put("status", "idle");
        reindexTaskService.runningTask().ifPresent(task -> {
            details.put("status", task.status());
            details.put("taskId", task.taskId());
            putIfPresent(details, "ownerInstance", task.ownerInstance());
            details.put("epoch", task.epoch());
            putIfPresent(details, "lastHeartbeat", task.heartbeatAt());
        });
        reindexTaskService.lockMirror().ifPresent(mirror -> {
            Map<String, Object> lock = new LinkedHashMap<>();
            lock.put("taskId", mirror.taskId());
            putIfPresent(lock, "ownerInstance", mirror.ownerInstance());
            lock.put("epoch", mirror.epoch());
            putIfPresent(lock, "expiresAt", mirror.expiresAt());
            details.put("lockMirror", lock);
        });
        return details;
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }
}
