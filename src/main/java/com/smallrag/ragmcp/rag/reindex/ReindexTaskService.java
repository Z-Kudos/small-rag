package com.smallrag.ragmcp.rag.reindex;

import com.smallrag.ragmcp.rag.IndexActivityGuard;

import com.smallrag.ragmcp.rag.RagService;

import com.smallrag.ragmcp.config.QdrantProperties;
import com.smallrag.ragmcp.config.RagProperties;
import com.smallrag.ragmcp.rag.archive.DocumentArchive;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Deque;
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
 * 异步重建任务：提交后立即返回 taskId，由后台线程执行，进度与任务锁持久化到 Qdrant。
 *
 * <p>同一时刻只允许一个重建任务（{@code rag_reindex} 全量 / {@code rag_rebuild}），
 * 重复提交会返回错误。任务状态机：
 * {@code queued → clearing? → reindexing → completed|aborted|cancelled|failed}；
 * 进程重启时未完成的任务进入 {@code interrupted} 并<b>继续持有任务锁</b>，
 * 由 {@code resume-on-startup} 自动续接（或 {@code rag_task_resume} 手动续接、
 * {@code rag_task_cancel} 放弃）。
 *
 * <p>持久化（{@link ReindexStateStore}）：任务快照与任务锁写入 Qdrant 的独立集合
 * {@code <rag.qdrant.collection-name>_reindex_state}，跟随 Qdrant 的持久化，
 * 不依赖应用容器的文件系统；快照保留最近 {@value #MAX_RETAINED_TASKS} 个。
 * Qdrant 暂时不可用时读操作按“无历史任务”处理，写操作由调用方降级告警。
 *
 * <p>看门狗（{@code rag.reindex.*}）：
 * <ul>
 *   <li>心跳：每次进度事件刷新 {@code lastProgressAt}；</li>
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

    private static final Set<String> TERMINAL_STATUSES =
            Set.of("completed", "aborted", "cancelled", "failed");

    static final String STATUS_INTERRUPTED = "interrupted";

    private final RagService ragService;
    private final DocumentArchive documentArchive;
    private final RagProperties properties;
    private final QdrantProperties qdrantProperties;
    private final ReindexStateStore stateStore;
    private final IndexActivityGuard activity;
    private final ScheduledExecutorService watchdog;
    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private final Deque<String> taskOrder = new ConcurrentLinkedDeque<>();
    private final AtomicReference<String> runningTaskId = new AtomicReference<>();

    /**
     * 重启后恢复出来的、仍持有任务锁的未完成任务；由 {@link #onApplicationReady()} 处分。
     */
    private volatile Task recoveredTask;

    public ReindexTaskService(RagService ragService, DocumentArchive documentArchive,
            RagProperties properties, QdrantProperties qdrantProperties, ReindexStateStore stateStore,
            IndexActivityGuard activity) {
        this.ragService = ragService;
        this.documentArchive = documentArchive;
        this.properties = properties;
        this.qdrantProperties = qdrantProperties;
        this.stateStore = stateStore;
        this.activity = activity;
        this.watchdog = Executors.newSingleThreadScheduledExecutor(
                runnable -> daemonThread(runnable, "rag-reindex-watchdog"));
        RagProperties.Reindex config = properties.getReindex();
        if (config.getStallTimeoutSeconds() > 0 || config.getHardTimeoutSeconds() > 0) {
            long interval = config.effectiveCheckIntervalSeconds();
            watchdog.scheduleWithFixedDelay(this::checkStalled, interval, interval, TimeUnit.SECONDS);
        }
        restore();
    }

    /**
     * 从状态存储恢复任务快照与任务锁。
     *
     * <p>锁持有者若已撤销/已请求取消，则直接进入终态并释放锁；否则标记 {@code interrupted}
     * 并继续持有锁，等 {@link #onApplicationReady()} 续接。
     */
    private void restore() {
        for (ReindexStateStore.PersistedTask persisted : stateStore.loadAll()) {
            Task task = new Task(persisted);
            tasks.put(task.taskId, task);
            taskOrder.addLast(task.taskId);
        }
        trim();

        String owner = stateStore.readOwner().orElse(null);
        if (owner == null) {
            recoverWithoutOwner();
            return;
        }

        Task task = tasks.get(owner);
        if (task == null || task.isTerminal()) {
            stateStore.clearOwner(owner);
            return;
        }
        if (task.isRevoked()) {
            task.abortRevoked();
            stateStore.clearOwner(owner);
            log.warn("重启前重建任务写权限已撤销，直接置为 failed 并释放任务锁: taskId={}", owner);
            return;
        }
        if (task.isCancelRequested()) {
            task.abandon();
            stateStore.clearOwner(owner);
            log.info("重启前已请求取消重建任务，直接置为 cancelled 并释放任务锁: taskId={}", owner);
            return;
        }

        runningTaskId.set(owner);
        if (!activity.tryAcquire(activityLabel(owner))) {
            log.warn("恢复重建任务时其他索引活动仍在运行，将在其结束后再操作: owner={}",
                    activity.owner().orElse("unknown"));
        }
        task.markInterrupted();
        recoveredTask = task;
        log.warn("恢复未完成的重建任务并续接任务锁: taskId={}, mode={}, processed={}/{}",
                owner, task.mode, task.processed, task.total);
    }

    /**
     * 锁 point 丢失但任务快照仍在时的恢复：按单实例假设，由最近一个未撤销/未取消的
     * 未完成任务重新持有锁，避免任务永久卡在 interrupted 且无法 resume。
     */
    private void recoverWithoutOwner() {
        Task latest = null;
        for (String taskId : taskOrder) {
            Task candidate = tasks.get(taskId);
            if (candidate == null || candidate.isTerminal()
                    || candidate.isRevoked() || candidate.isCancelRequested()) {
                continue;
            }
            latest = candidate;
        }
        if (latest != null) {
            runningTaskId.set(latest.taskId);
            if (!activity.tryAcquire(activityLabel(latest.taskId))) {
                log.warn("恢复重建任务锁失败，其他索引活动正在运行: owner={}",
                        activity.owner().orElse("unknown"));
            }
            latest.markInterrupted();
            recoveredTask = latest;
            persistOwner(latest.taskId);
            log.warn("任务锁缺失，按单实例假设重建任务锁: taskId={}", latest.taskId);
        }
        Task recovered = latest;
        tasks.values().stream()
                .filter(task -> task != recovered && !task.isTerminal())
                .forEach(Task::markInterrupted);
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
            log.error("自动续接重建任务失败，尝试取消并释放任务锁: taskId={}, 原因={}",
                    task.taskId, e.toString());
            try {
                cancel(task.taskId);
            } catch (Exception cancelError) {
                log.warn("自动续接失败后取消任务也失败: taskId={}, 原因={}",
                        task.taskId, cancelError.toString());
            }
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
            String owner = activity.owner().orElse("unknown");
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

        Task task = new Task(taskId, mode, total);
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
     * @param taskId 任务 id；为空表示最近一次任务
     * @return 续接后的任务快照
     * @throws IllegalArgumentException 没有可续接的任务
     * @throws IllegalStateException    任务不在 {@code interrupted} 状态或任务锁已丢失
     */
    public ReindexTaskView resume(String taskId) {
        Task task = resolve(taskId)
                .orElseThrow(() -> new IllegalArgumentException("没有可续接的重建任务"));
        if (!task.taskId.equals(runningTaskId.get())) {
            throw new IllegalStateException("任务锁未被该任务持有，无法续接: " + task.taskId);
        }
        if (!task.beginResume()) {
            throw new IllegalStateException("只有 interrupted 状态的任务可以续接，当前: "
                    + task.snapshot().status());
        }
        boolean clearFirst = "rebuild".equals(task.mode) && !task.isCleared();
        log.info("续接重建任务: taskId={}, mode={}, 跳过已完成 {} 篇, clearFirst={}",
                task.taskId, task.mode, task.completedCount(), clearFirst);
        startWorker(task, clearFirst);
        return task.snapshot();
    }

    /**
     * 已占用锁时的提示：区分“正常执行中”“重启待续接”“已撤销、正在释放写锁”。
     */
    private String busyMessage(String ownerTaskId) {
        Task owner = ownerTaskId == null ? null : tasks.get(ownerTaskId);
        if (owner != null && owner.isInterrupted()) {
            return "上一个重建任务在重启时中断，已保留任务锁；请用 rag_task_resume 继续或 "
                    + "rag_task_cancel 放弃: " + ownerTaskId;
        }
        if (owner != null && owner.isRevoked() && !owner.isTerminal()) {
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
     * @param taskId 任务 id；为空表示最近一次任务
     * @return 收到取消请求后的任务快照
     * @throws IllegalArgumentException 没有可取消的任务
     * @throws IllegalStateException    任务已经结束
     */
    public ReindexTaskView cancel(String taskId) {
        Task task = resolve(taskId)
                .orElseThrow(() -> new IllegalArgumentException("没有可取消的重建任务"));
        if (task.isTerminal()) {
            throw new IllegalStateException("任务已结束，无法取消: " + task.taskId);
        }
        if (task.isInterrupted()) {
            task.abandon();
            releaseLock(task);
            trim();
            log.info("已放弃重启时中断的重建任务并释放任务锁: taskId={}", task.taskId);
            return task.snapshot();
        }
        task.requestCancel();
        log.info("已请求取消重建任务（协作式，文档边界生效）: taskId={}", task.taskId);
        return task.snapshot();
    }

    /**
     * 查询任务进度；taskId 为空时返回最近一次任务（含已结束的）。
     *
     * @param taskId 任务 id，可为空
     * @return 任务快照；从未提交过任务或任务已被清理时为空
     */
    public Optional<ReindexTaskView> status(String taskId) {
        return resolve(taskId).map(Task::snapshot);
    }

    private Optional<Task> resolve(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            String latest = taskOrder.peekLast();
            return latest == null ? Optional.empty() : Optional.ofNullable(tasks.get(latest));
        }
        return Optional.ofNullable(tasks.get(taskId));
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
                ragService.reindexArchived(clearFirst, task, task.completedDocumentIds());
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
     * 释放任务锁（内存 + 磁盘）；按 taskId 内容比较，避免误放后续任务的锁。
     *
     * <p>不能用 {@link AtomicReference#compareAndSet}：恢复出来的持有者与任务快照里的
     * taskId 是不同 String 实例，CAS 按引用比较会失败。
     */
    private synchronized void releaseLock(Task task) {
        if (task.isSuspendedForRestart()) {
            // 服务关闭挂起：保留任务锁，重启后续接
            return;
        }
        if (Objects.equals(runningTaskId.get(), task.taskId)) {
            runningTaskId.set(null);
            task.markLockReleased();
            stateStore.clearOwner(task.taskId);
        }
        activity.release(activityLabel(task.taskId));
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
     * 持久化任务锁持有者；失败只告警。
     */
    private void persistOwner(String taskId) {
        try {
            stateStore.writeOwner(taskId);
        } catch (Exception e) {
            log.warn("重建任务锁持久化失败: taskId={}, 原因={}", taskId, e.toString());
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
            stateStore.clearOwner(task.taskId);
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
        String taskId = runningTaskId.get();
        Task task = taskId == null ? null : tasks.get(taskId);
        if (task == null || task.isTerminal() || task.isRevoked()) {
            // 已撤销/已结束的任务按原状态落盘：重启后不会被续接
            return;
        }
        task.suspendForRestart();
        try {
            task.awaitTerminal(TimeUnit.SECONDS.toMillis(2));
        } catch (Exception e) {
            log.debug("等待重建线程退出失败: taskId={}, 原因={}", task.taskId, e.toString());
        }
        // 工作线程可能已清理锁/写入终态：以“中断待续接”状态覆盖，并保留任务锁
        persist(task);
        persistOwner(task.taskId);
        log.warn("服务关闭：重建任务已保存为 interrupted 并保留任务锁，重启后自动续接: taskId={}",
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
            String lockReleaseAt) {
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
            if (persisted.completedDocumentIds() != null) {
                this.completedDocumentIds.addAll(persisted.completedDocumentIds());
            }
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
            // 分布式模式下租约丢失后，即使本线程尚未被中断也必须立刻停止写入
            return !revoked && !suspendedForRestart && activity.isHeldBy(activityLabel(taskId));
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
                    false, null);
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
                    revoked, releaseAt);
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
