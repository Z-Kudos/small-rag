package com.smallrag.ragmcp.rag.sources;

import com.smallrag.ragmcp.config.DistributedProperties;
import com.smallrag.ragmcp.distributed.DistributedLock;
import com.smallrag.ragmcp.distributed.LockHandle;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 目录扫描的 leader 选举。
 *
 * <p>多副本部署时只让一个副本执行启动/定时/watcher 触发的扫描，避免每个副本
 * 都去抢索引活动锁、产生无意义的日志与重复扫描；真正的写入仍由
 * {@link com.smallrag.ragmcp.rag.IndexActivityGuard} 串行化，leader 丢失也不会双写。
 *
 * <p>读取 {@code smallrag.distributed.lock.*}：用与索引活动锁相同的租约与续租间隔，
 * 持有一个独立的 {@code sources-leader} 租约；续租失败立即放弃领导权，下一轮再尝试
 * 重新获取。standalone 下使用进程内锁，永远只有一个实例，自然成为 leader。
 *
 * <p>MCP 手动触发（{@code rag_sync_sources}）不受 leader 限制，任何副本都可以执行。
 *
 * @author zjk
 */
@Component
@Slf4j
public class SourcesLeaderElection implements ApplicationRunner {

    private static final String LEADER_SUFFIX = "sources-leader";

    private final DistributedLock lock;
    private final DistributedProperties properties;
    private final ScheduledExecutorService scheduler;

    private volatile LockHandle handle;
    private volatile boolean leader;
    private volatile boolean closing;

    /**
     * @param lock       本地或 Redisson 锁实现
     * @param properties 分布式配置
     */
    @Autowired
    public SourcesLeaderElection(DistributedLock lock, DistributedProperties properties) {
        this.lock = lock;
        this.properties = properties;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "sources-leader-election");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 应用启动时先选一次，再按续租间隔周期性续租/重选。
     *
     * <p>{@link ApplicationRunner} 在 {@code ApplicationReadyEvent} 之前执行，
     * 因此 {@code SourceSyncService} 的启动扫描能看到选举结果。
     *
     * @param args 启动参数
     */
    @Override
    public void run(ApplicationArguments args) {
        tick();
        long interval = Math.max(10, properties.getLock().getRenewInterval().toMillis());
        scheduler.scheduleWithFixedDelay(this::tick, interval, interval, TimeUnit.MILLISECONDS);
    }

    /**
     * 尝试续租或竞选一次；包级可见，便于单元测试直接触发。
     */
    synchronized void tick() {
        if (closing) {
            return;
        }
        LockHandle current = handle;
        if (current != null) {
            try {
                if (lock.renew(current, lease())) {
                    leader = true;
                    return;
                }
                log.warn("目录扫描 leader 续租失败，放弃领导权: owner={}", current.owner());
            } catch (Exception e) {
                log.warn("目录扫描 leader 续租异常，放弃领导权: owner={}, 原因={}",
                        current.owner(), e.toString());
            }
            handle = null;
            leader = false;
        }
        try {
            Optional<LockHandle> acquired = lock.tryAcquire(
                    key(), properties.getInstanceId(), lease());
            if (acquired.isPresent()) {
                handle = acquired.get();
                leader = true;
                log.info("本实例成为目录扫描 leader: instance={}, key={}",
                        properties.getInstanceId(), key());
            } else {
                leader = false;
                log.debug("目录扫描 leader 由其他副本持有: key={}", key());
            }
        } catch (Exception e) {
            leader = false;
            log.warn("目录扫描 leader 竞选失败，下一轮重试: 原因={}", e.toString());
        }
    }

    /**
     * @return 当前实例是否为目录扫描 leader
     */
    public boolean isLeader() {
        return leader;
    }

    /**
     * 关闭时停止续租并主动释放领导权，让其他副本尽快接管。
     */
    @PreDestroy
    synchronized void shutdown() {
        closing = true;
        leader = false;
        scheduler.shutdownNow();
        LockHandle current = handle;
        handle = null;
        if (current != null) {
            try {
                lock.release(current);
            } catch (Exception e) {
                log.warn("释放目录扫描 leader 锁失败: {}", e.toString());
            }
        }
    }

    private String key() {
        return properties.getLock().getKeyPrefix() + LEADER_SUFFIX;
    }

    private Duration lease() {
        long interval = Math.max(10, properties.getLock().getRenewInterval().toMillis());
        return Duration.ofMillis(Math.max(properties.getLock().getLease().toMillis(), interval * 3));
    }
}
