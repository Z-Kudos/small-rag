package com.smallrag.ragmcp.rag;

import com.smallrag.ragmcp.config.DistributedProperties;
import com.smallrag.ragmcp.distributed.DistributedLock;
import com.smallrag.ragmcp.distributed.LockHandle;
import com.smallrag.ragmcp.distributed.LocalDistributedLock;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 索引写入活动的互斥标记。
 *
 * <p>重建任务（{@code reindex:<taskId>}）与目录导入（{@code sources}）都会写入
 * Qdrant 与归档；两者并发会互相删除/覆盖对方刚写入的片段，因此用同一把活动锁
 * 串行化。
 *
 * <p>standalone 模式使用进程内锁；distributed 模式通过 {@link DistributedLock}
 * 使用 Redisson {@code RFencedLock} 跨副本互斥。进程存活期间由 watchdog 自动续租；
 * distributed 下 {@link #shutdown()} 会主动释放锁，崩溃时靠 Redisson 停止续租 + TTL 接管。
 * distributed 模式下 {@code renew} 失败或异常表示远程校验失败，立即
 * 标记锁丢失并停止本地持有权，调用方通过 {@link #isHeldBy(String)} 感知并停止写入。
 *
 * @author zjk
 */
@Component
@Slf4j
public class IndexActivityGuard {

    /**
     * 目录导入活动标识。
     */
    public static final String SOURCES = "sources";

    private static final String LOCK_SUFFIX = "index-activity";

    private final AtomicReference<String> owner = new AtomicReference<>();
    private final DistributedLock lock;
    private final DistributedProperties properties;
    private final ScheduledExecutorService renewer;

    private volatile LockHandle handle;
    private volatile boolean lockLost;
    private volatile ScheduledFuture<?> renewal;

    /**
     * Spring 注入入口。
     *
     * @param lock       本地或 Redisson 锁实现
     * @param properties 分布式配置
     */
    @Autowired
    public IndexActivityGuard(DistributedLock lock, DistributedProperties properties) {
        this.lock = lock;
        this.properties = properties;
        this.renewer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "index-activity-lock-renewer");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 测试与 standalone 默认入口：进程内锁、默认租约配置。
     */
    public IndexActivityGuard() {
        this(new LocalDistributedLock(), new DistributedProperties());
    }

    /**
     * 重建任务活动标识。
     *
     * @param taskId 任务 id
     * @return 活动标识
     */
    public static String reindex(String taskId) {
        return "reindex:" + taskId;
    }

    /**
     * 尝试获取活动锁。
     *
     * @param label 活动标识，例如 {@code reindex:<taskId>} 或 {@code sources}
     * @return true 表示获取成功
     */
    public synchronized boolean tryAcquire(String label) {
        if (label == null || owner.get() != null) {
            return false;
        }
        try {
            Optional<LockHandle> acquired = lock.tryAcquire(lockKey(), label, effectiveLease());
            if (acquired.isEmpty()) {
                return false;
            }
            handle = acquired.get();
            lockLost = false;
            owner.set(label);
            scheduleRenewal();
            return true;
        } catch (Exception e) {
            log.warn("获取索引活动锁失败: label={}, 原因={}", label, e.toString());
            return false;
        }
    }

    /**
     * 释放活动锁；只有当前持有者匹配时才释放。
     *
     * <p>按内容比较：调用方每次拼接出来的 label 可能是不同的 String 实例。
     *
     * @param label 活动标识
     */
    public synchronized void release(String label) {
        if (!Objects.equals(owner.get(), label)) {
            return;
        }
        cancelRenewal();
        LockHandle current = handle;
        handle = null;
        owner.set(null);
        lockLost = false;
        if (current != null) {
            try {
                lock.release(current);
            } catch (Exception e) {
                log.warn("释放索引活动锁失败: label={}, 原因={}", label, e.toString());
            }
        }
    }

    /**
     * 当前实例是否仍安全持有该活动锁。
     *
     * <p>重建循环与目录导入在每次写入前检查；除了本地续租状态，还会向锁存储
     * 校验 owner + token，避免续租线程尚未发现租约丢失时旧副本继续写入。
     * 返回 false 表示必须立即停止写入。
     *
     * @param label 活动标识
     * @return true 表示本地持有且远程校验通过
     */
    public synchronized boolean isHeldBy(String label) {
        if (lockLost || !Objects.equals(owner.get(), label)) {
            return false;
        }
        LockHandle current = handle;
        if (current == null) {
            return false;
        }
        try {
            if (lock.isHeld(current)) {
                return true;
            }
            markLost(current, "远程校验失败（锁已过期或被其他副本接管）");
        } catch (Exception e) {
            markLost(current, "远程校验异常: " + e);
        }
        return false;
    }

    /**
     * @return 当前活动标识
     */
    public Optional<String> owner() {
        return Optional.ofNullable(owner.get());
    }

    /**
     * 当前活动锁的持有者（本地优先，其次查询锁存储）。
     *
     * <p>多副本下本实例可能只是“抢锁失败者”，本地 {@link #owner()} 为空；
     * 用于把真正持有者写进错误信息/日志。
     *
     * @return 持有者标识；无法确定时为空
     */
    public Optional<String> activeOwner() {
        String local = owner.get();
        if (local != null) {
            return Optional.of(local);
        }
        try {
            return lock.currentOwner(lockKey());
        } catch (Exception e) {
            log.debug("查询索引活动锁持有者失败: {}", e.toString());
            return Optional.empty();
        }
    }

    /**
     * 续租一次；失败则标记锁丢失。包级可见，便于单元测试直接触发。
     */
    synchronized void renewLock() {
        LockHandle current = handle;
        if (current == null) {
            return;
        }
        try {
            if (!lock.renew(current, effectiveLease())) {
                markLost(current, "续租失败（锁已被其他副本获取或已过期）");
            }
        } catch (Exception e) {
            markLost(current, e.toString());
        }
    }

    private void markLost(LockHandle current, String reason) {
        lockLost = true;
        handle = null;
        owner.set(null);
        cancelRenewal();
        log.error("索引活动锁已丢失，停止继续写入: key={}, owner={}, 原因={}",
                current.key(), current.owner(), reason);
    }

    private void scheduleRenewal() {
        cancelRenewal();
        long interval = Math.max(10, properties.getLock().getRenewInterval().toMillis());
        renewal = renewer.scheduleAtFixedRate(this::renewLock, interval, interval,
                TimeUnit.MILLISECONDS);
    }

    private void cancelRenewal() {
        ScheduledFuture<?> current = renewal;
        renewal = null;
        if (current != null) {
            current.cancel(false);
        }
    }

    private String lockKey() {
        return properties.getLock().getKeyPrefix() + LOCK_SUFFIX;
    }

    private Duration effectiveLease() {
        long interval = Math.max(10, properties.getLock().getRenewInterval().toMillis());
        long lease = properties.getLock().getLease().toMillis();
        return Duration.ofMillis(Math.max(lease, interval * 3));
    }

    /**
     * 应用关闭时停止续租；不主动释放租约，避免优雅停机期间另一副本过早接管。
     */
    @PreDestroy
    void shutdown() {
        cancelRenewal();
        LockHandle current = handle;
        handle = null;
        owner.set(null);
        if (current != null) {
            try {
                lock.release(current);
            } catch (Exception e) {
                log.warn("释放索引活动锁失败: key={}, owner={}, 原因={}",
                        current.key(), current.owner(), e.toString());
            }
        }
        renewer.shutdownNow();
    }
}
