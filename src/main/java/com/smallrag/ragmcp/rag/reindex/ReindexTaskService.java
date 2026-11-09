package com.smallrag.ragmcp.rag.reindex;

import com.smallrag.ragmcp.rag.IndexActivityGuard;

import com.smallrag.ragmcp.rag.RagService;

import com.smallrag.ragmcp.config.QdrantProperties;
import com.smallrag.ragmcp.config.RagProperties;
import com.smallrag.ragmcp.config.DistributedProperties;
import com.smallrag.ragmcp.distributed.DistributedLock;
import com.smallrag.ragmcp.distributed.FencedLease;
import com.smallrag.ragmcp.rag.archive.DocumentArchive;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 异步重建任务：提交后立即返回 taskId，由后台线程执行，进度快照持久化到 Qdrant。
 *
 * <p>同一时刻只允许一个重建任务（{@code rag_reindex} 全量 / {@code rag_rebuild}），
 * 重复提交会返回错误。任务状态机：
 * {@code queued → clearing? → reindexing → completed|aborted|cancelled|failed}；
 * 进程重启时未完成的任务进入 {@code interrupted} 并<b>重新持有任务锁</b>，
 * 由 {@code resume-on-startup} 自动续接（或 {@code rag_task_resume} 手动续接、
 * {@code rag_task_cancel} 放弃）。
 *
 * <p><b>任务所有权</b>：权威锁是 {@code <smallrag.distributed.lock.key-prefix>reindex-task}
 * 租约（standalone 进程内、distributed Redis），并带单调 {@code epoch} 栅栏；每次
 * {@code delete/add} 前由 {@link ReindexListener#isWritable()} 校验“活动锁仍持有 + 任务锁
 * 仍持有 + epoch 仍最新”。distributed 模式下 Redis 不可用时拒绝写索引（fail-closed）。
 *
 * <p>持久化（{@link ReindexStateStore}）：任务快照与任务锁<b>诊断镜像</b>写入 Qdrant 的
 * 独立集合 {@code <rag.qdrant.collection-name>_reindex_state}，跟随 Qdrant 的持久化，
 * 不依赖应用容器的文件系统；快照保留最近 {@value #MAX_RETAINED_TASKS} 个。
 * Qdrant 没有 CAS，镜像不参与互斥。
 *
 * <p>看门狗（{@code rag.reindex.*}）：
 * <ul>
 *   <li>心跳：每次进度事件刷新 {@code lastProgressAt}；任务锁续租成功刷新 {@code heartbeatAt}；</li>
 *   <li>停滞：超过 {@code stall-timeout-seconds} 无进度只标记 {@code stalled} 并告警，<b>保留任务锁</b>；</li>
 *   <li>硬超时：超过 {@code hard-timeout-seconds} 无进度则<b>撤销写权限</b>
 *       （{@link ReindexListener#isWritable()} 变 false）、取消在途 Embedding 调用并中断线程；</li>
 *   <li>释放锁：线程在 {@code join-timeout-seconds} 内退出则立即释放；否则进入
 *       {@code recovery-grace-seconds} 宽限期，到期强制释放任务锁；</li>
 *   <li>取消：{@link #cancel(String)} 为协作式，在下一篇文档开始前生效。</li>
 * </ul>
 *
 * <p>线程模型：每个任务独立线程（daemon），避免卡死的线程占住队列导致后续任务永远排不上。
 *
 * <p>任务参数（归档列表）在提交时做一次校验；真正的列表由
 * {@link RagService#reindexArchived(boolean, ReindexListener, Set)} 在清空后重新读取，
 * 因此提交后新入库的文档也能被本次重建覆盖。
 *
 * @author zjk
 */
@Component
@Slf4j
public class ReindexTaskService {

    static final int MAX_RETAINED_TASKS = 20;

    /**
     * 权威任务锁在 {@code smallrag.distributed.lock.key-prefix} 下的后缀。
     */
    static final String TASK_LOCK_SUFFIX = "reindex-task";

    private static final Set<String> TERMINAL_STATUSES =
            Set.of("completed", "aborted", "cancelled", "failed");

    static final String STATUS_INTERRUPTED = "interrupted";

    private final RagService ragService;
    private final DocumentArchive documentArchive;
    private final RagProperties properties;
    private final QdrantProperties qdrantProperties;
    private final ReindexStateStore stateStore;
    private final IndexActivityGuard activity;
    private final DistributedLock lock;
    private final DistributedProperties distributed;
    private final ScheduledExecutorService watchdog;
    private final ScheduledExecutorService leaseRenewer;
    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private final Deque<String> taskOrder = new ConcurrentLinkedDeque<>();
    private final AtomicReference<String> runningTaskId = new AtomicReference<>();

    /**
     * 跨副本状态读穿缓存：本地没有该任务时，短时间内的重复查询不反复打 Qdrant。
     */
    private final Map<String, CachedStatus> statusCache = new ConcurrentHashMap<>();

    private static final String LATEST_CACHE_KEY = "\u0000latest";

    /**
     * 权威任务锁：{@code <prefix>reindex-task} 租约 + fencing epoch；同一时刻只有一个副本持有。
     */
    private volatile FencedLease taskLease;

    /**
     * 重启后恢复出来的、仍持有任务锁的未完成任务；由 {@link #onApplicationReady()} 处分。
     */
    private volatile Task recoveredTask;

    public ReindexTaskService(RagService ragService, DocumentArchive documentArchive,
            RagProperties properties, QdrantProperties qdrantProperties, ReindexStateStore stateStore,
            IndexActivityGuard activity, DistributedLock lock, DistributedProperties distributed) {
        this.ragService = ragService;
        this.documentArchive = documentArchive;
        this.properties = properties;
        this.qdrantProperties = qdrantProperties;
        this.stateStore = stateStore;
        this.activity = activity;
        this.lock = lock;
        this.distributed = distributed;
        this.watchdog = Executors.newSingleThreadScheduledExecutor(
                runnable -> daemonThread(runnable, "rag-reindex-watchdog"));
        this.leaseRenewer = Executors.newSingleThreadScheduledExecutor(
                runnable -> daemonThread(runnable, "rag-reindex-lease-renewer"));
        RagProperties.Reindex config = properties.getReindex();
        if (config.getStallTimeoutSeconds() > 0 || config.getHardTimeoutSeconds() > 0) {
            long interval = config.effectiveCheckIntervalSeconds();
            watchdog.scheduleWithFixedDelay(this::checkStalled, interval, interval, TimeUnit.SECONDS);
        }
        long renewMillis = Math.max(10, distributed.getLock().getRenewInterval().toMillis());
        leaseRenewer.scheduleWithFixedDelay(this::renewTaskLease, renewMillis, renewMillis,
                TimeUnit.MILLISECONDS);
        restore();
    }

    /**
     * 从状态存储恢复任务快照，并尝试接管未完成任务的权威锁。
     *
     * <p>任务锁以 {@code <prefix>reindex-task} 租约为准（standalone 进程内、distributed
     * Redis）；Qdrant 里的锁 point 只是诊断镜像。接管成功才把任务标记为 {@code interrupted}
     * 并等待续接；接管失败说明其他副本仍在执行，本实例不改写任何快照。
     */
    private void restore() {
        verifyLockMirror();
        for (ReindexStateStore.PersistedTask persisted : stateStore.loadAll()) {
            Task task = new Task(persisted);
            tasks.put(task.taskId, task);
            taskOrder.addLast(task.taskId);
        }
        trim();

        Task candidate = latestUnfinished();
        if (candidate == null) {
            clearStaleMirror();
            return;
        }
        if (candidate.isRevoked()) {
            candidate.abortRevoked();
            stateStore.clearLock(candidate.taskId);
            log.warn("重启前重建任务写权限已撤销，直接置为 failed: taskId={}", candidate.taskId);
            return;
        }
        if (candidate.isCancelRequested()) {
            candidate.abandon();
            stateStore.clearLock(candidate.taskId);
            log.info("重启前已请求取消重建任务，直接置为 cancelled: taskId={}", candidate.taskId);
            return;
        }

        try {
            if (!acquireOwnership(candidate)) {
                log.warn("重建任务锁由其他副本持有，本实例不接管: taskId={}, owner={}",
                        candidate.taskId, taskLockOwner().orElse("unknown"));
                return;
            }
        } catch (RuntimeException e) {
            log.error("接管重建任务锁失败（Redis 不可用时不接管，避免双写）: taskId={}, 原因={}",
                    candidate.taskId, e.toString());
            return;
        }
        candidate.markInterrupted();
        recoveredTask = candidate;
        log.warn("恢复未完成的重建任务并续接任务锁: taskId={}, mode={}, processed={}/{}, epoch={}",
                candidate.taskId, candidate.mode, candidate.processed, candidate.total,
                taskLease == null ? 0L : taskLease.epoch());
    }

    /**
     * @return 最近一个未进入终态的任务；没有时为空
     */
    private Task latestUnfinished() {
        Iterator<String> iterator = taskOrder.descendingIterator();
        while (iterator.hasNext()) {
            Task candidate = tasks.get(iterator.next());
            if (candidate != null && !candidate.isTerminal()) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 镜像指向的任务在本实例已进入终态时清掉，避免诊断信息长期误导；
     * 未知任务可能属于其他副本，保留镜像。
     */
    private void clearStaleMirror() {
        stateStore.readLock()
                .filter(lock -> {
                    Task known = tasks.get(lock.taskId());
                    return known != null && known.isTerminal();
                })
                .ifPresent(lock -> stateStore.clearLock(lock.taskId()));
    }

    /**
     * 启动一致性校验：Qdrant 镜像必须与权威任务锁一致，以 Redis / 进程内锁为准。
     *
     * <p>镜像只用于诊断，但长期指向错误的持有者会误导排障。权威锁为空（持有者已
     * 退出或租约过期）或持有者 taskId 不一致时清除镜像；锁存储不可用时跳过。
     */
    private void verifyLockMirror() {
        Optional<ReindexStateStore.LockMirror> mirror = stateStore.readLock();
        if (mirror.isEmpty()) {
            return;
        }
        String taskId = mirror.get().taskId();
        try {
            Optional<String> authority = lock.currentOwner(taskLockKey());
            if (authority.isEmpty()) {
                stateStore.clearLock(taskId);
                log.warn("启动一致性校验：权威任务锁为空，已清除过期镜像: taskId={}", taskId);
            } else if (!authority.get().equals(taskId)) {
                stateStore.clearLock(taskId);
                log.warn("启动一致性校验：镜像与权威任务锁不一致，以权威锁为准并清除镜像: "
                        + "mirror={}, authority={}", taskId, authority.get());
            }
        } catch (Exception e) {
            log.debug("启动一致性校验无法读取权威任务锁，跳过: {}", e.toString());
        }
    }

    /**
     * @return 是否存在重启时恢复出来、等待续接的重建任务
     */
    public boolean hasRecoveredTask() {
        return recoveredTask != null;
    }

    /**
     * 应用就绪后按配置自动续接重启前未完成的任务。
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(0)
    public void onApplicationReady() {
        Task task = recoveredTask;
        if (task == null) {
            return;
        }
        if (!properties.getReindex().isResumeOnStartup()) {
            log.warn("重启前未完成的重建任务已保留（status=interrupted, 持有任务锁），"
                    + "可调用 rag_task_resume 继续或 rag_task_cancel 放弃: taskId={}", task.taskId);
            return;
        }
        try {
            resume(task.taskId);
        } catch (Exception e) {
            // 不取消任务：租约仍在续租，用户可以稍后手动 rag_task_resume 或 rag_task_cancel
            log.error("自动续接重建任务失败，任务保持 interrupted（可手动续接或放弃）: taskId={}, 原因={}",
                    task.taskId, e.toString());
        }
    }

    /**
     * 提交重建任务；归档不可用或全量重建时归档为空会同步抛错，不会产生任务。
     *
     * @param mode       任务类型：{@code reindex} 或 {@code rebuild}
     * @param clearFirst 是否先清空本服务写入的片段
     * @return 刚创建的任务快照（通常为 {@code queued}，执行很快时可能已完成）
     */
    public ReindexTaskView submit(String mode, boolean clearFirst) {
        if (!documentArchive.isEnabled()) {
            throw new IllegalStateException("原始文档归档不可用，无法执行该管理操作");
        }
        int total = documentArchive.listSummaries().size();
        if (total == 0 && clearFirst) {
            throw new IllegalStateException("没有归档文档，拒绝清空向量库");
        }

        String taskId = UUID.randomUUID().toString();
        String label = activityLabel(taskId);
        if (!activity.tryAcquire(label)) {
            String owner = activity.activeOwner().orElse("unknown");
            if (owner.equals(IndexActivityGuard.SOURCES)) {
                throw new IllegalStateException("目录导入正在执行，请稍后重试");
            }
            throw new IllegalStateException(busyMessage(
                    owner.startsWith("reindex:") ? owner.substring("reindex:".length()) : owner));
        }
        if (!runningTaskId.compareAndSet(null, taskId)) {
            activity.release(label);
            throw new IllegalStateException(busyMessage(runningTaskId.get()));
        }
        Optional<FencedLease> acquired;
        try {
            acquired = acquireTaskLease(taskId);
        } catch (RuntimeException e) {
            runningTaskId.set(null);
            activity.release(label);
            throw new IllegalStateException("获取重建任务锁失败（distributed 模式 Redis 不可用时拒绝写索引）: "
                    + e.getMessage(), e);
        }
        if (acquired.isEmpty()) {
            runningTaskId.set(null);
            activity.release(label);
            throw new IllegalStateException(busyMessage(taskLockOwner().orElse("unknown")));
        }
        taskLease = acquired.get();

        Task task = new Task(taskId, mode, total);
        task.markOwned(distributed.getInstanceId(), taskLease.epoch());
        tasks.put(taskId, task);
        taskOrder.addLast(taskId);
        trim();
        persistOwner(taskId);
        persist(task);

        startWorker(task, clearFirst);
        return task.snapshot();
    }

    /**
     * 续接 {@code interrupted} 状态的任务：从上次成功的文档之后继续，不重新清空。
     *
     * <p>任意副本都可调用：本地内存没有该任务时从 Qdrant 状态集合读取；随后尝试
     * 抢占权威任务锁（epoch 递增），抢到才接管。持有副本仍在续租时返回持有者信息。
     *
     * @param taskId 任务 id；为空表示最近一次任务
     * @return 续接后的任务快照
     * @throws IllegalArgumentException 没有可续接的任务
     * @throws IllegalStateException    任务已结束、正在本实例执行或任务锁由其他副本持有
     */
    public ReindexTaskView resume(String taskId) {
        Task task = resolveForOperation(taskId)
                .orElseThrow(() -> new IllegalArgumentException("没有可续接的重建任务"));
        if (task.isTerminal()) {
            throw new IllegalStateException("任务已结束，无法续接: " + task.taskId);
        }
        if (Objects.equals(runningTaskId.get(), task.taskId) && !task.isInterrupted()) {
            throw new IllegalStateException("任务正在本实例执行，无需续接: " + task.taskId);
        }
        if (!acquireOwnership(task)) {
            throw new IllegalStateException("任务由 " + ownerDescription(task) + " 持有，无法续接");
        }
        if (!task.isInterrupted()) {
            // 持有者已失联、租约过期：先落为 interrupted，再按续接流程执行
            task.markInterrupted();
        }
        ensureActivity(task.taskId);
        if (!task.beginResume()) {
            throw new IllegalStateException("只有 interrupted 状态的任务可以续接，当前: "
                    + task.snapshot().status());
        }
        boolean clearFirst = "rebuild".equals(task.mode) && !task.isCleared();
        log.info("续接重建任务: taskId={}, mode={}, 跳过已完成 {} 篇, clearFirst={}, epoch={}",
                task.taskId, task.mode, task.completedCount(), clearFirst,
                taskLease == null ? 0L : taskLease.epoch());
        startWorker(task, clearFirst);
        return task.snapshot();
    }

    /**
     * 确保本实例持有重建任务的权威锁（租约 + epoch）；已持有同一任务时直接返回。
     *
     * @param task 目标任务
     * @return true 表示持有锁；false 表示锁被其他副本持有
     */
    private synchronized boolean acquireOwnership(Task task) {
        FencedLease current = taskLease;
        if (current != null && Objects.equals(runningTaskId.get(), task.taskId)) {
            return true;
        }
        Optional<FencedLease> acquired = acquireTaskLease(task.taskId);
        if (acquired.isEmpty()) {
            return false;
        }
        taskLease = acquired.get();
        runningTaskId.set(task.taskId);
        task.markOwned(distributed.getInstanceId(), taskLease.epoch());
        persistOwner(task.taskId);
        return true;
    }

    /**
     * 续接前确保索引活动锁可用（与目录导入互斥）；不持有则尝试获取。
     */
    private void ensureActivity(String taskId) {
        String label = activityLabel(taskId);
        if (activity.owner().filter(label::equals).isPresent()) {
            return;
        }
        if (!activity.tryAcquire(label)) {
            throw new IllegalStateException("其他索引活动正在执行，请稍后重试: "
                    + activity.activeOwner().orElse("unknown"));
        }
    }

    /**
     * 获取任务锁租约；distributed 模式下 Redis 不可用时抛异常（fail-closed）。
     */
    private Optional<FencedLease> acquireTaskLease(String taskId) {
        return FencedLease.acquire(lock, taskLockKey(), taskId, effectiveLease());
    }

    private String taskLockKey() {
        return distributed.getLock().getKeyPrefix() + TASK_LOCK_SUFFIX;
    }

    private Optional<String> taskLockOwner() {
        try {
            return lock.currentOwner(taskLockKey());
        } catch (Exception e) {
            log.debug("查询重建任务锁持有者失败: {}", e.toString());
            return Optional.empty();
        }
    }

    /**
     * 租约时长与索引活动锁一致：至少为续租间隔的 3 倍，避免续租抖动导致误丢锁。
     */
    private Duration effectiveLease() {
        long interval = Math.max(10, distributed.getLock().getRenewInterval().toMillis());
        long lease = distributed.getLock().getLease().toMillis();
        return Duration.ofMillis(Math.max(lease, interval * 3));
    }

    /**
     * 已占用锁时的提示：区分“正常执行中”“重启待续接”“已撤销、正在释放写锁”。
     */
    private String busyMessage(String ownerTaskId) {
        Task owner = ownerTaskId == null ? null : tasks.get(ownerTaskId);
        if (owner == null) {
            return "重建任务锁由其他副本持有，请稍后重试: " + ownerTaskId;
        }
        if (owner.isInterrupted()) {
            return "上一个重建任务在重启时中断，已保留任务锁；请用 rag_task_resume 继续或 "
                    + "rag_task_cancel 放弃: " + ownerTaskId;
        }
        if (owner.isRevoked() && !owner.isTerminal()) {
            return "上一个重建任务已撤销写权限，正在释放任务锁（最长 "
                    + properties.getReindex().effectiveRecoveryGraceSeconds(
                            qdrantProperties.getRequestTimeoutMs())
                    + " 秒），请稍后重试: " + ownerTaskId;
        }
        return "已有重建任务正在执行，请先用 rag_task_status 查询: " + ownerTaskId;
    }

    /**
     * 请求取消任务：运行中在文档边界停止；{@code interrupted} 任务立即置为 cancelled 并释放锁。
     *
     * <p>任意副本都可调用：本地没有该任务时先从状态集合读取；随后尝试抢占任务锁。
     * 原持有者仍在续租时无法跨副本中断其线程，返回持有者信息；持有者失联（租约过期）
     * 时可接管并直接置为 cancelled。
     *
     * @param taskId 任务 id；为空表示最近一次任务
     * @return 收到取消请求后的任务快照
     * @throws IllegalArgumentException 没有可取消的任务
     * @throws IllegalStateException    任务已经结束或任务锁由其他副本持有
     */
    public ReindexTaskView cancel(String taskId) {
        Task task = resolveForOperation(taskId)
                .orElseThrow(() -> new IllegalArgumentException("没有可取消的重建任务"));
        if (task.isTerminal()) {
            throw new IllegalStateException("任务已结束，无法取消: " + task.taskId);
        }
        if (Objects.equals(runningTaskId.get(), task.taskId) && !task.isInterrupted()) {
            task.requestCancel();
            log.info("已请求取消重建任务（协作式，文档边界生效）: taskId={}", task.taskId);
            return task.snapshot();
        }
        if (!acquireOwnership(task)) {
            throw new IllegalStateException("任务由 " + ownerDescription(task)
                    + " 持有，无法在非持有副本取消；请等待任务结束或在对该副本操作");
        }
        if (!task.isInterrupted()) {
            task.markInterrupted();
        }
        task.abandon();
        releaseLock(task);
        trim();
        log.info("已放弃重建任务并释放任务锁: taskId={}, epoch={}", task.taskId,
                taskLease == null ? 0L : taskLease.epoch());
        return task.snapshot();
    }

    /**
     * 查询任务进度；taskId 为空时返回最近一次任务（含已结束的）。
     *
     * <p>本地内存没有时回读 Qdrant 状态集合（短缓存），因此任意副本都能看到
     * 当前持有者与其他副本提交的任务；运行中进度仍以持有者持久化的快照为准。
     *
     * @param taskId 任务 id，可为空
     * @return 任务快照；从未提交过任务或任务已被清理时为空
     */
    public Optional<ReindexTaskView> status(String taskId) {
        if (taskId != null && !taskId.isBlank()) {
            Task local = tasks.get(taskId);
            if (local != null) {
                return Optional.of(local.snapshot());
            }
            return remoteStatus(taskId, taskId);
        }
        Task latestLocal = latestLocalTask();
        ReindexTaskView localView = latestLocal == null ? null : latestLocal.snapshot();
        ReindexTaskView remoteView = remoteStatus(LATEST_CACHE_KEY, null).orElse(null);
        return Optional.ofNullable(newer(localView, remoteView));
    }

    /**
     * 读取本地最近一次任务（含终态）。
     */
    private Task latestLocalTask() {
        String latest = taskOrder.peekLast();
        return latest == null ? null : tasks.get(latest);
    }

    /**
     * 选择 {@code startedAt} 较晚的快照；任意一方为空时返回另一方。
     */
    private static ReindexTaskView newer(ReindexTaskView left, ReindexTaskView right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        String leftStarted = String.valueOf(left.startedAt());
        String rightStarted = String.valueOf(right.startedAt());
        return rightStarted.compareTo(leftStarted) > 0 ? right : left;
    }

    /**
     * 回读 Qdrant 中的任务快照，使用读穿缓存避免高频查询打爆状态集合。
     */
    private Optional<ReindexTaskView> remoteStatus(String cacheKey, String taskId) {
        long now = System.currentTimeMillis();
        long ttl = Math.max(0, distributed.getTaskStatusCacheMs().toMillis());
        CachedStatus cached = statusCache.get(cacheKey);
        if (cached != null && now - cached.fetchedAtMillis() < ttl) {
            return Optional.of(cached.view());
        }
        Optional<ReindexStateStore.PersistedTask> persisted = taskId == null
                ? stateStore.loadLatest()
                : stateStore.load(taskId);
        if (persisted.isEmpty()) {
            statusCache.remove(cacheKey);
            return Optional.empty();
        }
        ReindexTaskView view = persisted.get().view();
        statusCache.put(cacheKey, new CachedStatus(view, now));
        return Optional.of(view);
    }

    /**
     * 查询任务（含跨副本）：本地优先；本地没有时从状态集合读取并纳入本地视图。
     */
    private Optional<Task> resolveForOperation(String taskId) {
        if (taskId != null && !taskId.isBlank()) {
            Task local = tasks.get(taskId);
            if (local != null) {
                return Optional.of(local);
            }
        }
        Task local = taskId == null || taskId.isBlank() ? latestLocalTask() : null;
        Optional<ReindexStateStore.PersistedTask> remote = taskId == null || taskId.isBlank()
                ? stateStore.loadLatest()
                : stateStore.load(taskId);
        if (remote.isEmpty()) {
            return Optional.ofNullable(local);
        }
        Task remoteTask = adopt(remote.get());
        if (local == null) {
            return Optional.of(remoteTask);
        }
        return isNewer(remoteTask.snapshot(), local.snapshot())
                ? Optional.of(remoteTask)
                : Optional.of(local);
    }

    private static boolean isNewer(ReindexTaskView candidate, ReindexTaskView current) {
        if (candidate == null) {
            return false;
        }
        if (current == null) {
            return true;
        }
        return String.valueOf(candidate.startedAt())
                .compareTo(String.valueOf(current.startedAt())) > 0;
    }

    /**
     * 把状态集合中的任务纳入本地视图；已存在同一 taskId 时复用本地实例。
     */
    private synchronized Task adopt(ReindexStateStore.PersistedTask persisted) {
        String taskId = persisted.view().taskId();
        Task existing = tasks.get(taskId);
        if (existing != null) {
            return existing;
        }
        Task task = new Task(persisted);
        tasks.put(taskId, task);
        taskOrder.addLast(taskId);
        trim();
        return task;
    }

    /**
     * 错误信息中的持有者描述：优先用锁镜像的实例 id / epoch，回退到权威锁 owner。
     */
    private String ownerDescription(Task task) {
        Optional<ReindexStateStore.LockMirror> mirror = stateStore.readLock();
        if (mirror.isPresent() && task.taskId.equals(mirror.get().taskId())) {
            ReindexStateStore.LockMirror lockMirror = mirror.get();
            return (lockMirror.ownerInstance() == null ? "其他副本" : lockMirror.ownerInstance())
                    + "（taskId=" + task.taskId + ", epoch=" + lockMirror.epoch() + "）";
        }
        String owner = taskLockOwner().orElse("其他副本");
        return owner + "（taskId=" + task.taskId + "）";
    }

    private void startWorker(Task task, boolean clearFirst) {
        Thread worker = daemonThread(() -> execute(task, clearFirst), "rag-reindex-" + shortId(task.taskId));
        task.bindWorker(worker);
        try {
            worker.start();
        } catch (RuntimeException | Error e) {
            log.error("重建任务启动失败: taskId={}, 原因={}", task.taskId, e.toString());
            task.fail(e);
            releaseLock(task);
            trim();
        }
    }

    private void execute(Task task, boolean clearFirst) {
        try (CancellationScope scope = CancellationScope.open()) {
            task.bindScope(scope);
            try {
                RagService.RebuildResult result =
                        ragService.reindexArchived(clearFirst, task, task.completedDocumentIds());
                if ("rebuild".equals(task.mode) && !result.aborted() && !task.isCancelled()) {
                    cleanupOrphans(task);
                }
                task.finish();
            } catch (Exception e) {
                log.error("重建任务失败: taskId={}, 原因={}", task.taskId, e.toString());
                task.fail(e);
            } catch (Throwable t) {
                // Error（如 OOM）也必须让任务进入终态，否则状态会永久停在 reindexing
                log.error("重建任务异常终止: taskId={}, 原因={}", task.taskId, t.toString());
                task.fail(t);
                throw t;
            }
        } finally {
            releaseLock(task);
            Thread.interrupted();
            trim();
        }
    }

    /**
     * 全量重建完成后的孤儿清理；失败只告警，不影响已成功的重建。
     */
    private void cleanupOrphans(Task task) {
        try {
            RagService.CleanupResult cleanup = ragService.cleanupOrphans();
            log.info("重建任务孤儿清理完成: taskId={}, parentPoints={}, orphanParents={}",
                    task.taskId, cleanup.parentPoints(), cleanup.orphanParents());
        } catch (Exception e) {
            log.warn("重建任务孤儿清理失败（不影响重建结果）: taskId={}, 原因={}",
                    task.taskId, e.toString());
        }
    }

    /**
     * 释放任务锁（权威租约 + 诊断镜像 + 活动锁）；按 taskId 内容比较，避免误放后续任务的锁。
     *
     * <p>不能用 {@link AtomicReference#compareAndSet}：恢复出来的持有者与任务快照里的
     * taskId 是不同 String 实例，CAS 按引用比较会失败。
     */
    private synchronized void releaseLock(Task task) {
        if (task.isSuspendedForRestart()) {
            // 服务关闭挂起：由 shutdown() 统一处理租约，避免工作线程提前释放
            return;
        }
        if (Objects.equals(runningTaskId.get(), task.taskId)) {
            runningTaskId.set(null);
            task.markLockReleased();
            releaseLease();
            stateStore.clearLock(task.taskId);
        }
        activity.release(activityLabel(task.taskId));
    }

    /**
     * 释放权威任务锁；幂等。
     */
    private void releaseLease() {
        FencedLease lease = taskLease;
        taskLease = null;
        if (lease == null) {
            return;
        }
        try {
            lease.release();
        } catch (Exception e) {
            log.warn("释放重建任务锁失败: owner={}, 原因={}", lease.owner(), e.toString());
        }
    }

    /**
     * 续租任务锁并刷新心跳；失败说明锁已被其他副本接管或已过期，停止写入。
     */
    private void renewTaskLease() {
        FencedLease lease = taskLease;
        if (lease == null) {
            return;
        }
        boolean renewed;
        try {
            renewed = lease.renew(effectiveLease());
        } catch (Exception e) {
            log.error("重建任务锁续租异常，停止写入: taskId={}, 原因={}", lease.owner(), e.toString());
            markLeaseLost(lease, "续租异常: " + e);
            return;
        }
        if (!renewed) {
            markLeaseLost(lease, "续租失败（锁已被其他副本接管或已过期）");
            return;
        }
        Task task = tasks.get(runningTaskId.get());
        if (task != null) {
            task.heartbeat();
        }
    }

    /**
     * 租约丢失：清空本地持有权并让运行中的任务失败；{@code interrupted} 任务保持可续接。
     */
    private synchronized void markLeaseLost(FencedLease lease, String reason) {
        if (taskLease != lease) {
            return;
        }
        taskLease = null;
        String taskId = runningTaskId.get();
        runningTaskId.set(null);
        log.error("重建任务锁已丢失，本实例停止写索引: taskId={}, epoch={}, 原因={}",
                taskId, lease.epoch(), reason);
        Task task = taskId == null ? null : tasks.get(taskId);
        if (task != null && !task.isTerminal() && !task.isInterrupted()) {
            task.revoke("重建任务锁已丢失: " + reason);
        }
        if (taskId != null) {
            activity.release(activityLabel(taskId));
        }
    }

    /**
     * 持久化任务状态；失败只告警，不打断重建。
     */
    private void persist(Task task) {
        try {
            ReindexTaskView view = task.isSuspendedForRestart()
                    ? task.interruptedSnapshot()
                    : task.snapshot();
            stateStore.save(new ReindexStateStore.PersistedTask(
                    view, task.isCleared(), task.completedDocumentIds()));
        } catch (Exception e) {
            log.warn("重建任务状态持久化失败: taskId={}, 原因={}", task.taskId, e.toString());
        }
    }

    /**
     * 写入任务锁诊断镜像（权威锁在 {@link DistributedLock}）；失败只告警。
     */
    private void persistOwner(String taskId) {
        FencedLease lease = taskLease;
        try {
            stateStore.writeLock(new ReindexStateStore.LockMirror(
                    taskId,
                    distributed.getInstanceId(),
                    lease == null ? 0L : lease.epoch(),
                    Instant.now().plus(effectiveLease()).toString()));
        } catch (Exception e) {
            log.warn("重建任务锁镜像持久化失败: taskId={}, 原因={}", taskId, e.toString());
        }
    }

    /**
     * 看门狗：只做“标记停滞”，硬超时才撤销写权限并中断；任何异常都不能让定时任务停止。
     */
    private void checkStalled() {
        try {
            String taskId = runningTaskId.get();
            if (taskId == null) {
                return;
            }
            Task task = tasks.get(taskId);
            if (task == null || task.isTerminal() || task.isRevoked() || task.isInterrupted()) {
                return;
            }

            RagProperties.Reindex config = properties.getReindex();
            long idleSeconds = task.idleSeconds();
            if (config.getStallTimeoutSeconds() <= 0) {
                task.clearStalled();
            } else if (idleSeconds < config.getStallTimeoutSeconds()) {
                task.clearStalled();
            } else if (task.markStalled()) {
                ReindexTaskView view = task.snapshot();
                log.warn("重建任务疑似停滞: taskId={}, status={}, 当前文档={}, 已 {} 秒无进度（阈值 {} 秒）",
                        taskId, view.status(), view.currentDocumentId(), idleSeconds,
                        config.getStallTimeoutSeconds());
            }
            if (config.getHardTimeoutSeconds() > 0 && idleSeconds >= config.getHardTimeoutSeconds()) {
                forceTimeout(task, idleSeconds, config);
            }
        } catch (Exception e) {
            log.warn("重建看门狗检查失败: {}", e.toString());
        }
    }

    /**
     * 硬超时：撤销写权限 → 取消在途调用 → 中断线程 → 等退出 → 必要时宽限期后强制释放锁。
     */
    private void forceTimeout(Task task, long idleSeconds, RagProperties.Reindex config) {
        if (!task.taskId.equals(runningTaskId.get())) {
            return;
        }
        String reason = "看门狗：连续 " + idleSeconds + " 秒无进度，已强制终止";
        if (!task.revoke(reason)) {
            return;
        }
        log.error("重建任务硬超时，已撤销写权限、取消在途调用并中断线程: taskId={}, 已 {} 秒无进度",
                task.taskId, idleSeconds);

        long joinMs = TimeUnit.SECONDS.toMillis(Math.max(1, config.getJoinTimeoutSeconds()));
        if (task.awaitTerminal(joinMs)) {
            releaseLock(task);
            log.error("重建任务已被看门狗终止: taskId={}", task.taskId);
            trim();
            return;
        }

        long graceSeconds = config.effectiveRecoveryGraceSeconds(qdrantProperties.getRequestTimeoutMs());
        long graceMs = TimeUnit.SECONDS.toMillis(graceSeconds);
        task.markLockReleaseAt(System.currentTimeMillis() + graceMs);
        log.error("重建工作线程未在 {} 秒内退出，写权限已撤销；{} 秒宽限期后强制释放任务锁: taskId={}",
                config.getJoinTimeoutSeconds(), graceSeconds, task.taskId);
        try {
            watchdog.schedule(() -> releaseAfterGrace(task), graceMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            releaseAfterGrace(task);
        }
    }

    /**
     * 宽限期到期：强制释放任务锁。旧任务已无写权限，此时新任务可安全接管。
     */
    private void releaseAfterGrace(Task task) {
        try {
            if (Objects.equals(runningTaskId.get(), task.taskId)) {
                runningTaskId.set(null);
                log.error("重建任务宽限期已过，已强制释放任务锁: taskId={}", task.taskId);
            }
            task.markLockReleased();
            task.abortRevoked();
            releaseLease();
            stateStore.clearLock(task.taskId);
            activity.release(activityLabel(task.taskId));
            trim();
        } catch (Exception e) {
            log.warn("重建任务宽限期释放锁失败: taskId={}, 原因={}", task.taskId, e.toString());
        }
    }

    /**
     * 只清理已结束的任务（内存 + 磁盘）；最老的未终态任务会让清理暂停，避免误删正在执行的任务。
     */
    private synchronized void trim() {
        while (taskOrder.size() > MAX_RETAINED_TASKS) {
            String oldest = taskOrder.peekFirst();
            if (oldest == null) {
                return;
            }
            Task task = tasks.get(oldest);
            if (task != null && !task.isTerminal()) {
                return;
            }
            taskOrder.removeFirst();
            if (task != null) {
                tasks.remove(oldest, task);
            }
            stateStore.delete(oldest);
        }
    }

    @PreDestroy
    void shutdown() {
        watchdog.shutdownNow();
        leaseRenewer.shutdownNow();
        String taskId = runningTaskId.get();
        Task task = taskId == null ? null : tasks.get(taskId);
        if (task == null || task.isTerminal() || task.isRevoked()) {
            // 已撤销/已结束的任务按原状态落盘：重启后不会被续接
            releaseLease();
            if (taskId != null) {
                activity.release(activityLabel(taskId));
            }
            return;
        }
        task.suspendForRestart();
        try {
            task.awaitTerminal(TimeUnit.SECONDS.toMillis(2));
        } catch (Exception e) {
            log.debug("等待重建线程退出失败: taskId={}, 原因={}", task.taskId, e.toString());
        }
        // 工作线程可能已清理锁/写入终态：以“中断待续接”状态覆盖
        persist(task);
        persistOwner(task.taskId);
        // 优雅停机：立即让出租约，重启或另一副本可以马上续接；崩溃时靠租约过期接管
        releaseLease();
        activity.release(activityLabel(task.taskId));
        stateStore.clearLock(task.taskId);
        log.warn("服务关闭：重建任务已保存为 interrupted 并让出任务锁，重启后自动续接: taskId={}",
                task.taskId);
    }

    private static Thread daemonThread(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private static String shortId(String taskId) {
        return taskId.length() <= 8 ? taskId : taskId.substring(0, 8);
    }

    private static String activityLabel(String taskId) {
        return IndexActivityGuard.reindex(taskId);
    }

    /**
     * 当前实例正在执行的任务快照（用于健康检查与排障）。
     *
     * @return 运行中任务；空闲时为空
     */
    public Optional<ReindexTaskView> runningTask() {
        String taskId = runningTaskId.get();
        Task task = taskId == null ? null : tasks.get(taskId);
        return task == null ? Optional.empty() : Optional.of(task.snapshot());
    }

    /**
     * @return 任务状态集合位置（用于健康检查）
     */
    public String stateStoreLocation() {
        return stateStore.location();
    }

    /**
     * @return 任务锁诊断镜像（用于健康检查）
     */
    public Optional<ReindexStateStore.LockMirror> lockMirror() {
        return stateStore.readLock();
    }

    /**
     * 跨副本任务状态读穿缓存项。
     *
     * @param view           缓存的快照
     * @param fetchedAtMillis 回读时间（毫秒时间戳）
     */
    private record CachedStatus(ReindexTaskView view, long fetchedAtMillis) {
    }

    /**
     * 任务快照（JSON 字段名即 record 组件名）。
     *
     * @param taskId            任务 id
     * @param mode              {@code reindex} 或 {@code rebuild}
     * @param status            queued / clearing / reindexing / interrupted / completed / aborted / cancelled / failed
     * @param total             待重切文档总数
     * @param processed         已处理数
     * @param succeeded         成功数
     * @param failed            失败数
     * @param percent           进度百分比（0-100，按 processed/total 估算）
     * @param currentDocumentId 当前/最近处理的文档 id
     * @param lastProgressAt    最近一次进度事件时间（ISO-8601），用于判断停滞
     * @param stalled           是否超过停滞阈值但仍在运行
     * @param cancelRequested   是否已收到取消请求（协作式，当前文档处理完后停止）
     * @param startedAt         任务创建时间（ISO-8601）
     * @param finishedAt        结束时间（ISO-8601），未结束为 null
     * @param aborted           是否因连续失败中止
     * @param error             失败原因，成功时为 null
     * @param revoked           写权限是否已被看门狗撤销（撤销后旧任务不能再写索引）
     * @param lockReleaseAt     宽限期结束时间（ISO-8601）；只有“已撤销但线程未退出”时非空
     * @param ownerInstance     当前/最近持有任务锁的实例标识
     * @param epoch             任务锁栅栏 epoch；每次接管严格递增
     * @param heartbeatAt       最近一次成功续租任务锁的时间（ISO-8601）
     */
    public record ReindexTaskView(
            String taskId,
            String mode,
            String status,
            int total,
            int processed,
            int succeeded,
            int failed,
            int percent,
            String currentDocumentId,
            String lastProgressAt,
            boolean stalled,
            boolean cancelRequested,
            String startedAt,
            String finishedAt,
            boolean aborted,
            String error,
            boolean revoked,
            String lockReleaseAt,
            String ownerInstance,
            long epoch,
            String heartbeatAt) {
    }

    /**
     * 可变任务状态 + 进度监听器实现（{@link ReindexListener}）。
     */
    private final class Task implements ReindexListener {

        private final String taskId;
        private final String mode;
        private final String startedAt;
        private final Set<String> completedDocumentIds = new LinkedHashSet<>();
        private String status = "queued";
        private int total;
        private int processed;
        private int succeeded;
        private int failed;
        private String currentDocumentId;
        private long lastProgressAtMillis = System.currentTimeMillis();
        private String finishedAt;
        private String error;
        private boolean terminal;
        private boolean stalled;
        private boolean cancelRequested;
        private boolean hardTimeoutTriggered;
        private boolean revoked;
        private boolean cleared;
        private boolean suspendedForRestart;
        private long lockReleaseAtMillis;
        private String ownerInstance;
        private long epoch;
        private long heartbeatAtMillis;
        private Thread workerThread;
        private CancellationScope scope;

        private Task(String taskId, String mode, int total) {
            this.taskId = taskId;
            this.mode = mode;
            this.total = total;
            this.startedAt = Instant.now().toString();
        }

        private Task(ReindexStateStore.PersistedTask persisted) {
            ReindexTaskView view = persisted.view();
            this.taskId = view.taskId();
            this.mode = view.mode();
            this.startedAt = view.startedAt();
            this.status = view.status();
            this.total = view.total();
            this.processed = view.processed();
            this.succeeded = view.succeeded();
            this.failed = view.failed();
            this.currentDocumentId = view.currentDocumentId();
            this.lastProgressAtMillis = parseMillis(view.lastProgressAt());
            this.finishedAt = view.finishedAt();
            this.error = view.error();
            this.terminal = TERMINAL_STATUSES.contains(view.status());
            this.revoked = view.revoked();
            this.cancelRequested = view.cancelRequested();
            this.cleared = persisted.cleared();
            this.ownerInstance = view.ownerInstance();
            this.epoch = view.epoch();
            this.heartbeatAtMillis = parseMillis(view.heartbeatAt());
            if (persisted.completedDocumentIds() != null) {
                this.completedDocumentIds.addAll(persisted.completedDocumentIds());
            }
        }

        /**
         * 记录任务锁持有者信息（接管时调用）。
         */
        private synchronized void markOwned(String instanceId, long ownedEpoch) {
            this.ownerInstance = instanceId;
            this.epoch = ownedEpoch;
            this.heartbeatAtMillis = System.currentTimeMillis();
        }

        /**
         * 续租成功时刷新心跳。
         */
        private synchronized void heartbeat() {
            this.heartbeatAtMillis = System.currentTimeMillis();
        }

        @Override
        public synchronized void onProgress(ReindexProgress progress) {
            if (terminal) {
                return;
            }
            lastProgressAtMillis = System.currentTimeMillis();
            stalled = false;
            if (progress.total() > 0) {
                total = progress.total();
            }
            processed = progress.processed();
            succeeded = progress.succeeded();
            failed = progress.failed();
            currentDocumentId = progress.currentDocumentId();
            switch (progress.stage()) {
                case CLEARING -> status = "clearing";
                case REINDEXING -> status = "reindexing";
                case COMPLETED -> markTerminal(hardTimeoutTriggered ? "failed" : "completed");
                case ABORTED -> markTerminal("aborted");
                case CANCELLED -> markTerminal(hardTimeoutTriggered ? "failed" : "cancelled");
            }
        }

        @Override
        public synchronized void onCleared() {
            if (terminal) {
                return;
            }
            cleared = true;
            persist(this);
        }

        @Override
        public synchronized void onDocumentFinished(String documentId, boolean success) {
            if (terminal) {
                return;
            }
            if (success) {
                completedDocumentIds.add(documentId);
            }
            persist(this);
        }

        @Override
        public synchronized boolean isCancelled() {
            return cancelRequested;
        }

        @Override
        public synchronized boolean isWritable() {
            // 分布式模式下租约丢失后，即使本线程尚未被中断也必须立刻停止写入；
            // 同时校验活动锁（与目录导入互斥）与任务锁 epoch（防止旧持有者写入）
            return !revoked && !suspendedForRestart
                    && activity.isHeldBy(activityLabel(taskId))
                    && ownsTaskLease();
        }

        /**
         * 本任务是否仍持有权威任务锁（租约 + 最新 epoch）。
         */
        private boolean ownsTaskLease() {
            FencedLease lease = taskLease;
            return lease != null && taskId.equals(lease.owner()) && lease.isValid();
        }

        private synchronized void bindWorker(Thread thread) {
            workerThread = thread;
        }

        private synchronized void bindScope(CancellationScope cancellationScope) {
            scope = cancellationScope;
        }

        private synchronized long idleSeconds() {
            return Math.max(0, (System.currentTimeMillis() - lastProgressAtMillis) / 1000);
        }

        private synchronized boolean markStalled() {
            if (stalled) {
                return false;
            }
            stalled = true;
            return true;
        }

        private synchronized void clearStalled() {
            stalled = false;
        }

        private synchronized void requestCancel() {
            cancelRequested = true;
            persist(this);
        }

        private synchronized boolean isCancelRequested() {
            return cancelRequested;
        }

        private synchronized boolean isInterrupted() {
            return STATUS_INTERRUPTED.equals(status);
        }

        private synchronized void markInterrupted() {
            if (terminal) {
                return;
            }
            status = STATUS_INTERRUPTED;
            stalled = false;
            cancelRequested = false;
            hardTimeoutTriggered = false;
            lastProgressAtMillis = System.currentTimeMillis();
            workerThread = null;
            scope = null;
            persist(this);
        }

        /**
         * 服务关闭时挂起任务：停止写入并中断线程，但把状态落盘为 {@code interrupted}，
         * 同时保留任务锁，重启后自动/手动续接。
         *
         * <p>与 {@link #revoke(String)} 的区别：撤销是单向失败，不期望续接。
         */
        private synchronized void suspendForRestart() {
            if (terminal) {
                return;
            }
            suspendedForRestart = true;
            status = STATUS_INTERRUPTED;
            finishedAt = null;
            error = null;
            stalled = false;
            cancelRequested = false;
            revoked = false;
            hardTimeoutTriggered = false;
            lastProgressAtMillis = System.currentTimeMillis();
            if (scope != null) {
                scope.cancel();
            }
            if (workerThread != null) {
                workerThread.interrupt();
            }
            persist(this);
        }

        private synchronized boolean isSuspendedForRestart() {
            return suspendedForRestart;
        }

        /**
         * 供 {@code persist(Task)} 使用：无论工作线程随后写入什么终态，
         * 关停期间落盘的都是可续接的 {@code interrupted} 快照。
         */
        private synchronized ReindexTaskView interruptedSnapshot() {
            int percent = total <= 0 ? 0 : (int) Math.round(processed * 100.0 / total);
            return new ReindexTaskView(taskId, mode, STATUS_INTERRUPTED, total, processed,
                    succeeded, failed, percent, currentDocumentId,
                    Instant.now().toString(), false, false, startedAt, null, false, null,
                    false, null, ownerInstance, epoch, heartbeatAt());
        }

        /**
         * 从 interrupted 转入可执行状态；返回 false 表示不能续接。
         */
        private synchronized boolean beginResume() {
            if (terminal || !STATUS_INTERRUPTED.equals(status)) {
                return false;
            }
            status = "queued";
            finishedAt = null;
            error = null;
            stalled = false;
            cancelRequested = false;
            revoked = false;
            suspendedForRestart = false;
            hardTimeoutTriggered = false;
            lastProgressAtMillis = System.currentTimeMillis();
            persist(this);
            return true;
        }

        /**
         * 撤销写权限：单向操作，成功后旧任务不可能恢复写入。
         *
         * @param reason 失败原因
         * @return true 表示本次调用完成了撤销
         */
        private synchronized boolean revoke(String reason) {
            if (terminal || revoked) {
                return false;
            }
            revoked = true;
            cancelRequested = true;
            hardTimeoutTriggered = true;
            if (error == null) {
                error = reason;
            }
            if (scope != null) {
                scope.cancel();
            }
            if (workerThread != null) {
                workerThread.interrupt();
            }
            persist(this);
            return true;
        }

        private synchronized boolean isRevoked() {
            return revoked;
        }

        private synchronized boolean isCleared() {
            return cleared;
        }

        private synchronized int completedCount() {
            return completedDocumentIds.size();
        }

        private synchronized Set<String> completedDocumentIds() {
            return Set.copyOf(completedDocumentIds);
        }

        private synchronized void markLockReleaseAt(long millis) {
            lockReleaseAtMillis = millis;
        }

        private synchronized void markLockReleased() {
            lockReleaseAtMillis = 0L;
        }

        private synchronized void finish() {
            if (terminal) {
                return;
            }
            markTerminal(hardTimeoutTriggered ? "failed" : "completed");
        }

        private synchronized void fail(Throwable throwable) {
            if (terminal) {
                return;
            }
            if (error == null) {
                error = rootMessage(throwable);
            }
            markTerminal("failed");
        }

        /**
         * 宽限期结束：无论线程是否退出，任务都必须进入 failed 终态。
         */
        private synchronized void abortRevoked() {
            if (terminal) {
                return;
            }
            if (error == null) {
                error = "重建任务已撤销写权限";
            }
            markTerminal("failed");
        }

        /**
         * 放弃（重启时中断的任务）：直接进入 cancelled 终态。
         */
        private synchronized void abandon() {
            if (terminal) {
                return;
            }
            markTerminal("cancelled");
        }

        private void markTerminal(String finalStatus) {
            status = finalStatus;
            finishedAt = Instant.now().toString();
            terminal = true;
            notifyAll();
            persist(this);
        }

        private synchronized boolean isTerminal() {
            return terminal;
        }

        private synchronized boolean awaitTerminal(long millis) {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1, millis));
            while (!terminal) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(this, remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }

        private synchronized ReindexTaskView snapshot() {
            int percent = total <= 0 ? 0 : (int) Math.round(processed * 100.0 / total);
            String releaseAt = lockReleaseAtMillis <= 0
                    ? null
                    : Instant.ofEpochMilli(lockReleaseAtMillis).toString();
            return new ReindexTaskView(taskId, mode, status, total, processed, succeeded, failed,
                    percent, currentDocumentId, Instant.ofEpochMilli(lastProgressAtMillis).toString(),
                    stalled, cancelRequested, startedAt, finishedAt, "aborted".equals(status), error,
                    revoked, releaseAt, ownerInstance, epoch, heartbeatAt());
        }

        /**
         * @return 最近一次续租时间；从未持有时为 null
         */
        private String heartbeatAt() {
            return heartbeatAtMillis <= 0 ? null : Instant.ofEpochMilli(heartbeatAtMillis).toString();
        }

        private static long parseMillis(String instant) {
            if (instant == null || instant.isBlank()) {
                return System.currentTimeMillis();
            }
            try {
                return Instant.parse(instant).toEpochMilli();
            } catch (RuntimeException e) {
                return System.currentTimeMillis();
            }
        }

        private static String rootMessage(Throwable throwable) {
            Throwable cause = throwable;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            String message = cause.getMessage();
            return (message == null || message.isBlank()) ? cause.getClass().getSimpleName() : message;
        }
    }
}
