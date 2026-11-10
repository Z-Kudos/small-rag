package com.smallrag.ragmcp.rag.reindex;

import com.smallrag.ragmcp.rag.IndexActivityGuard;
import com.smallrag.ragmcp.rag.RagService;

import com.smallrag.ragmcp.config.QdrantProperties;
import com.smallrag.ragmcp.config.RagProperties;
import com.smallrag.ragmcp.config.DistributedProperties;
import com.smallrag.ragmcp.distributed.DistributedLock;
import com.smallrag.ragmcp.distributed.FencedLease;
import com.smallrag.ragmcp.distributed.LocalDistributedLock;
import com.smallrag.ragmcp.distributed.LockHandle;
import com.smallrag.ragmcp.rag.archive.ArchivedDocument;
import com.smallrag.ragmcp.rag.archive.ArchivedDocumentSummary;
import com.smallrag.ragmcp.rag.archive.DocumentArchive;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author zjk
 */
class ReindexTaskServiceTest {

    private static final Set<String> TERMINAL = Set.of("completed", "aborted", "cancelled", "failed");

    private final RagService ragService = mock(RagService.class);
    private final DocumentArchive archive = mock(DocumentArchive.class);
    private final RagProperties properties = new RagProperties();
    private final QdrantProperties qdrantProperties = new QdrantProperties();
    private final InMemoryReindexStateBackend stateBackend = new InMemoryReindexStateBackend();
    private final LocalDistributedLock lock = new LocalDistributedLock();
    private final DistributedProperties distributed = new DistributedProperties();
    private ReindexTaskService service;

    private ReindexTaskService newService() {
        return new ReindexTaskService(ragService, archive, properties, qdrantProperties,
                new ReindexStateStore(stateBackend), new IndexActivityGuard(), lock, distributed);
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
    }

    @Test
    void rejectsWhenArchiveUnavailable() {
        when(archive.isEnabled()).thenReturn(false);
        service = newService();

        assertThatThrownBy(() -> service.submit("reindex", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("归档不可用");
    }

    @Test
    void rebuildRejectsEmptyArchive() {
        when(archive.isEnabled()).thenReturn(true);
        when(archive.listSummaries()).thenReturn(List.of());
        service = newService();

        assertThatThrownBy(() -> service.submit("rebuild", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("拒绝清空");
    }

    @Test
    void rejectsWhenDirectoryImportIsRunning() {
        when(archive.isEnabled()).thenReturn(true);
        when(archive.listSummaries()).thenReturn(List.of(ArchivedDocumentSummary.of(document("doc-1"))));
        IndexActivityGuard guard = new IndexActivityGuard();
        guard.tryAcquire(IndexActivityGuard.SOURCES);
        service = new ReindexTaskService(ragService, archive, properties, qdrantProperties,
                new ReindexStateStore(stateBackend), guard, lock, distributed);
        try {
            assertThatThrownBy(() -> service.submit("reindex", false))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("目录导入");
        } finally {
            guard.release(IndexActivityGuard.SOURCES);
        }
    }

    @Test
    void reportsProgressAndRejectsConcurrentTask() throws Exception {
        when(archive.isEnabled()).thenReturn(true);
        when(archive.listSummaries()).thenReturn(List.of(ArchivedDocumentSummary.of(document("doc-1"))));

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(ragService.reindexArchived(eq(true), any(), any())).thenAnswer(invocation -> {
            ReindexListener listener = invocation.getArgument(1);
            listener.onProgress(new ReindexProgress(
                    ReindexProgress.Stage.CLEARING, 0, 0, 0, 0, null, false));
            listener.onProgress(new ReindexProgress(
                    ReindexProgress.Stage.REINDEXING, 1, 0, 0, 0, "doc-1", false));
            started.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            listener.onProgress(new ReindexProgress(
                    ReindexProgress.Stage.REINDEXING, 1, 1, 1, 0, "doc-1", false));
            listener.onProgress(new ReindexProgress(
                    ReindexProgress.Stage.COMPLETED, 1, 1, 1, 0, null, false));
            return new RagService.RebuildResult(1, 1, 0, false);
        });

        service = newService();
        ReindexTaskService.ReindexTaskView initial = service.submit("rebuild", true);

        assertThat(initial.taskId()).isNotBlank();
        assertThat(initial.total()).isEqualTo(1);
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        ReindexTaskService.ReindexTaskView running = service.status(initial.taskId()).orElseThrow();
        assertThat(running.status()).isEqualTo("reindexing");
        assertThat(running.percent()).isZero();
        assertThat(running.currentDocumentId()).isEqualTo("doc-1");
        assertThat(running.lastProgressAt()).isNotBlank();
        assertThat(running.stalled()).isFalse();

        assertThatThrownBy(() -> service.submit("reindex", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已有重建任务");

        release.countDown();
        ReindexTaskService.ReindexTaskView completed = awaitTerminal(initial.taskId(), 5);
        assertThat(completed.status()).isEqualTo("completed");
        assertThat(completed.processed()).isEqualTo(1);
        assertThat(completed.succeeded()).isEqualTo(1);
        assertThat(completed.failed()).isZero();
        assertThat(completed.percent()).isEqualTo(100);
        assertThat(completed.finishedAt()).isNotNull();

        // 不传 taskId 时返回最近一次任务
        assertThat(service.status(null).orElseThrow().taskId()).isEqualTo(initial.taskId());
        assertThatThrownBy(() -> service.cancel(initial.taskId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已结束");
    }

    @Test
    void cancelStopsAtDocumentBoundary() throws Exception {
        when(archive.isEnabled()).thenReturn(true);
        when(archive.listSummaries()).thenReturn(List.of(ArchivedDocumentSummary.of(document("doc-1")), ArchivedDocumentSummary.of(document("doc-2"))));

        CountDownLatch started = new CountDownLatch(1);
        when(ragService.reindexArchived(anyBoolean(), any(), any())).thenAnswer(invocation -> {
            ReindexListener listener = invocation.getArgument(1);
            listener.onProgress(new ReindexProgress(
                    ReindexProgress.Stage.REINDEXING, 2, 0, 0, 0, "doc-1", false));
            started.countDown();
            while (!listener.isCancelled()) {
                Thread.sleep(10);
            }
            listener.onProgress(new ReindexProgress(
                    ReindexProgress.Stage.CANCELLED, 2, 1, 1, 0, null, false));
            return new RagService.RebuildResult(2, 1, 0, false);
        });

        service = newService();
        ReindexTaskService.ReindexTaskView submitted = service.submit("reindex", false);
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        ReindexTaskService.ReindexTaskView cancelling = service.cancel(submitted.taskId());
        assertThat(cancelling.cancelRequested()).isTrue();

        ReindexTaskService.ReindexTaskView cancelled = awaitTerminal(submitted.taskId(), 5);
        assertThat(cancelled.status()).isEqualTo("cancelled");
        assertThat(cancelled.processed()).isEqualTo(1);
    }

    @Test
    void stalledTaskIsMarkedButKeepsLock() throws Exception {
        properties.getReindex().setStallTimeoutSeconds(1);
        properties.getReindex().setCheckIntervalSeconds(1);
        when(archive.isEnabled()).thenReturn(true);
        when(archive.listSummaries()).thenReturn(List.of(ArchivedDocumentSummary.of(document("doc-1"))));

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(ragService.reindexArchived(anyBoolean(), any(), any())).thenAnswer(invocation -> {
            ReindexListener listener = invocation.getArgument(1);
            listener.onProgress(new ReindexProgress(
                    ReindexProgress.Stage.REINDEXING, 1, 0, 0, 0, "doc-1", false));
            started.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            listener.onProgress(new ReindexProgress(
                    ReindexProgress.Stage.COMPLETED, 1, 1, 1, 0, null, false));
            return new RagService.RebuildResult(1, 1, 0, false);
        });

        service = newService();
        ReindexTaskService.ReindexTaskView submitted = service.submit("reindex", false);
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        ReindexTaskService.ReindexTaskView stalled = awaitStalled(submitted.taskId(), 5);
        assertThat(stalled.status()).isEqualTo("reindexing");
        assertThat(stalled.stalled()).isTrue();

        // 停滞只告警，不释放锁
        assertThatThrownBy(() -> service.submit("reindex", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已有重建任务");

        release.countDown();
        assertThat(awaitTerminal(submitted.taskId(), 5).status()).isEqualTo("completed");
    }

    @Test
    void hardTimeoutInterruptsWorkerAndReleasesLock() throws Exception {
        properties.getReindex().setStallTimeoutSeconds(1);
        properties.getReindex().setHardTimeoutSeconds(2);
        properties.getReindex().setCheckIntervalSeconds(1);
        properties.getReindex().setJoinTimeoutSeconds(2);
        when(archive.isEnabled()).thenReturn(true);
        when(archive.listSummaries()).thenReturn(List.of(ArchivedDocumentSummary.of(document("doc-1"))));

        CountDownLatch started = new CountDownLatch(1);
        when(ragService.reindexArchived(anyBoolean(), any(), any())).thenAnswer(invocation -> {
            ReindexListener listener = invocation.getArgument(1);
            listener.onProgress(new ReindexProgress(
                    ReindexProgress.Stage.REINDEXING, 1, 0, 0, 0, "doc-1", false));
            started.countDown();
            Thread.sleep(30_000);
            return new RagService.RebuildResult(1, 1, 0, false);
        });

        service = newService();
        ReindexTaskService.ReindexTaskView submitted = service.submit("reindex", false);
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        ReindexTaskService.ReindexTaskView failed = awaitTerminal(submitted.taskId(), 10);
        assertThat(failed.status()).isEqualTo("failed");
        assertThat(failed.error()).contains("看门狗");
        assertThat(failed.revoked()).isTrue();
        assertThat(failed.lockReleaseAt()).isNull();

        // 锁已释放：仍可在同一实例上提交新任务
        doReturn(new RagService.RebuildResult(0, 0, 0, false))
                .when(ragService).reindexArchived(anyBoolean(), any(), any());
        ReindexTaskService.ReindexTaskView second = service.submit("reindex", false);
        assertThat(awaitTerminal(second.taskId(), 5).status()).isEqualTo("completed");
    }

    @Test
    void revokeReleasesLockAfterGraceEvenIfWorkerIgnoresInterrupt() throws Exception {
        properties.getReindex().setStallTimeoutSeconds(1);
        properties.getReindex().setHardTimeoutSeconds(2);
        properties.getReindex().setCheckIntervalSeconds(1);
        properties.getReindex().setJoinTimeoutSeconds(1);
        properties.getReindex().setRecoveryGraceSeconds(2);
        // Qdrant deadline 500ms → 宽限期下限 1s，允许测试用 2s 宽限期
        qdrantProperties.setRequestTimeoutMs(500);
        when(archive.isEnabled()).thenReturn(true);
        when(archive.listSummaries()).thenReturn(List.of(ArchivedDocumentSummary.of(document("doc-1"))));

        AtomicBoolean releaseFirst = new AtomicBoolean(false);
        AtomicInteger invocations = new AtomicInteger();
        when(ragService.reindexArchived(anyBoolean(), any(), any())).thenAnswer(invocation -> {
            ReindexListener listener = invocation.getArgument(1);
            if (invocations.getAndIncrement() == 0) {
                listener.onProgress(new ReindexProgress(
                        ReindexProgress.Stage.REINDEXING, 1, 0, 0, 0, "doc-1", false));
                while (!releaseFirst.get()) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ignored) {
                        // 模拟不响应中断的阻塞调用
                    }
                }
            }
            return new RagService.RebuildResult(1, 1, 0, false);
        });

        service = newService();
        ReindexTaskService.ReindexTaskView first = service.submit("reindex", false);

        // 硬超时后进入宽限期：任务已撤销，但锁暂未释放
        ReindexTaskService.ReindexTaskView revoked = awaitRevoked(first.taskId(), 10);
        assertThat(revoked.status()).isEqualTo("reindexing");
        assertThat(revoked.revoked()).isTrue();
        assertThat(revoked.error()).contains("看门狗");
        assertThat(revoked.lockReleaseAt()).isNotBlank();
        assertThatThrownBy(() -> service.submit("reindex", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("撤销");

        // 宽限期到期后强制释放锁：无需重启即可提交新任务
        ReindexTaskService.ReindexTaskView second = submitEventually(10);
        assertThat(awaitTerminal(first.taskId(), 10).status()).isEqualTo("failed");
        assertThat(awaitTerminal(second.taskId(), 10).status()).isEqualTo("completed");
        releaseFirst.set(true);
    }

    @Test
    void restoresInterruptedTaskKeepsLockAndResumesSkippingCompletedDocuments() throws Exception {
        String taskId = UUID.randomUUID().toString();
        ReindexStateStore stateStore = new ReindexStateStore(stateBackend);
        stateStore.save(new ReindexStateStore.PersistedTask(
                view(taskId, "reindex", "reindexing", 2, 1, 1, 0, "doc-1", null),
                false, Set.of("doc-1")));

        when(archive.isEnabled()).thenReturn(true);
        when(ragService.reindexArchived(anyBoolean(), any(), any()))
                .thenReturn(new RagService.RebuildResult(2, 2, 0, false));

        service = newService();

        ReindexTaskService.ReindexTaskView restored = service.status(taskId).orElseThrow();
        assertThat(restored.status()).isEqualTo("interrupted");
        assertThat(restored.processed()).isEqualTo(1);
        assertThat(restored.lockReleaseAt()).isNull();
        assertThatThrownBy(() -> service.submit("reindex", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rag_task_resume");

        assertThat(service.resume(taskId).status()).isIn("queued", "reindexing", "completed");
        assertThat(awaitTerminal(taskId, 5).status()).isEqualTo("completed");
        verify(ragService).reindexArchived(eq(false), any(), eq(Set.of("doc-1")));
    }

    @Test
    void restoresLatestUnfinishedTaskWhenMirrorIsMissing() {
        String taskId = UUID.randomUUID().toString();
        stateBackend.save(new ReindexStateStore.PersistedTask(
                view(taskId, "reindex", "reindexing", 2, 1, 1, 0, "doc-1", null),
                false, Set.of("doc-1")));
        when(archive.isEnabled()).thenReturn(true);

        service = newService();

        assertThat(service.status(taskId).orElseThrow().status()).isEqualTo("interrupted");
        assertThatThrownBy(() -> service.submit("reindex", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rag_task_resume");

        service.cancel(taskId);
    }

    @Test
    void autoResumesInterruptedTaskOnApplicationReady() throws Exception {
        String taskId = UUID.randomUUID().toString();
        ReindexStateStore stateStore = new ReindexStateStore(stateBackend);
        stateStore.save(new ReindexStateStore.PersistedTask(
                view(taskId, "rebuild", "reindexing", 3, 1, 1, 0, "doc-1", null),
                true, Set.of("doc-1")));

        when(archive.isEnabled()).thenReturn(true);
        when(ragService.reindexArchived(anyBoolean(), any(), any()))
                .thenReturn(new RagService.RebuildResult(3, 3, 0, false));

        service = newService();
        service.onApplicationReady();

        assertThat(awaitTerminal(taskId, 5).status()).isEqualTo("completed");
        // rebuild 已完成清空，续接时不再清空
        verify(ragService).reindexArchived(eq(false), any(), eq(Set.of("doc-1")));
    }

    @Test
    void cancelAbandonsInterruptedTaskAndReleasesLock() throws Exception {
        String taskId = UUID.randomUUID().toString();
        ReindexStateStore stateStore = new ReindexStateStore(stateBackend);
        stateStore.save(new ReindexStateStore.PersistedTask(
                view(taskId, "rebuild", "clearing", 3, 0, 0, 0, null, null),
                false, Set.of()));

        when(archive.isEnabled()).thenReturn(true);
        when(archive.listSummaries()).thenReturn(List.of(ArchivedDocumentSummary.of(document("doc-1"))));
        when(ragService.reindexArchived(anyBoolean(), any(), any()))
                .thenReturn(new RagService.RebuildResult(1, 1, 0, false));

        service = newService();

        ReindexTaskService.ReindexTaskView cancelled = service.cancel(taskId);
        assertThat(cancelled.status()).isEqualTo("cancelled");
        assertThat(service.status(taskId).orElseThrow().status()).isEqualTo("cancelled");

        ReindexTaskService.ReindexTaskView next = service.submit("reindex", false);
        assertThat(awaitTerminal(next.taskId(), 5).status()).isEqualTo("completed");
    }

    @Test
    void shutdownPersistsInterruptedTaskAndKeepsLockForRestart() throws Exception {
        when(archive.isEnabled()).thenReturn(true);
        when(archive.listSummaries()).thenReturn(List.of(ArchivedDocumentSummary.of(document("doc-1"))));

        CountDownLatch started = new CountDownLatch(1);
        when(ragService.reindexArchived(anyBoolean(), any(), any())).thenAnswer(invocation -> {
            ReindexListener listener = invocation.getArgument(1);
            listener.onProgress(new ReindexProgress(
                    ReindexProgress.Stage.REINDEXING, 1, 0, 0, 0, "doc-1", false));
            started.countDown();
            Thread.sleep(30_000);
            return new RagService.RebuildResult(1, 1, 0, false);
        });

        service = newService();
        String taskId = service.submit("reindex", false).taskId();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        service.shutdown();

        // 模拟重启：同一 Qdrant 状态后端上的新实例
        service = newService();
        ReindexTaskService.ReindexTaskView restored = service.status(taskId).orElseThrow();
        assertThat(restored.status()).isEqualTo("interrupted");
        assertThatThrownBy(() -> service.submit("reindex", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rag_task_resume");

        service.cancel(taskId);
    }

    @Test
    void recordsFailureWhenRebuildThrows() throws Exception {        when(archive.isEnabled()).thenReturn(true);
        when(archive.listSummaries()).thenReturn(List.of(ArchivedDocumentSummary.of(document("doc-1"))));
        when(ragService.reindexArchived(anyBoolean(), any(), any()))
                .thenThrow(new IllegalStateException("embedding down"));

        service = newService();
        ReindexTaskService.ReindexTaskView submitted = service.submit("reindex", false);
        ReindexTaskService.ReindexTaskView failed = awaitTerminal(submitted.taskId(), 5);

        assertThat(failed.status()).isEqualTo("failed");
        assertThat(failed.error()).isEqualTo("embedding down");
        assertThat(failed.finishedAt()).isNotNull();
    }

    @Test
    void doesNotTakeOverTaskLockHeldByAnotherReplica() {
        String taskId = UUID.randomUUID().toString();
        stateBackend.save(new ReindexStateStore.PersistedTask(
                view(taskId, "reindex", "reindexing", 2, 1, 1, 0, "doc-1", null),
                false, Set.of("doc-1")));
        when(archive.isEnabled()).thenReturn(true);
        when(archive.listSummaries()).thenReturn(List.of(ArchivedDocumentSummary.of(document("doc-1"))));
        // 模拟另一副本已持有权威任务锁
        lock.tryAcquire(taskLockKey(), "other-task", Duration.ofSeconds(30)).orElseThrow();

        service = newService();

        // 不接管、不改写快照：保持另一副本正在执行的 reindexing 状态
        assertThat(service.status(taskId).orElseThrow().status()).isEqualTo("reindexing");
        assertThatThrownBy(() -> service.submit("reindex", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("其他副本");
    }

    @Test
    void stopsWritingWhenTaskLeaseIsLost() throws Exception {
        RevocableLock revocable = new RevocableLock();
        when(archive.isEnabled()).thenReturn(true);
        when(archive.listSummaries()).thenReturn(List.of(ArchivedDocumentSummary.of(document("doc-1"))));

        AtomicBoolean writableBefore = new AtomicBoolean();
        AtomicBoolean writableAfter = new AtomicBoolean(true);
        when(ragService.reindexArchived(anyBoolean(), any(), any())).thenAnswer(invocation -> {
            ReindexListener listener = invocation.getArgument(1);
            writableBefore.set(listener.isWritable());
            // 模拟租约被其他副本接管
            revocable.revoke();
            writableAfter.set(listener.isWritable());
            return new RagService.RebuildResult(1, 1, 0, false);
        });

        service = new ReindexTaskService(ragService, archive, properties, qdrantProperties,
                new ReindexStateStore(stateBackend), new IndexActivityGuard(), revocable, distributed);
        ReindexTaskService.ReindexTaskView submitted = service.submit("reindex", false);
        assertThat(awaitTerminal(submitted.taskId(), 5).status()).isEqualTo("completed");

        assertThat(writableBefore).isTrue();
        assertThat(writableAfter).isFalse();
    }

    @Test
    void exposesLeaseOwnerAndEpoch() throws Exception {
        when(archive.isEnabled()).thenReturn(true);
        when(archive.listSummaries()).thenReturn(List.of(ArchivedDocumentSummary.of(document("doc-1"))));
        when(ragService.reindexArchived(anyBoolean(), any(), any()))
                .thenReturn(new RagService.RebuildResult(1, 1, 0, false));

        service = newService();
        ReindexTaskService.ReindexTaskView submitted = service.submit("reindex", false);

        assertThat(submitted.ownerInstance()).isEqualTo(distributed.getInstanceId());
        assertThat(submitted.epoch()).isEqualTo(1L);
        assertThat(submitted.heartbeatAt()).isNotBlank();
        assertThat(awaitTerminal(submitted.taskId(), 5).status()).isEqualTo("completed");
    }

    @Test
    void statusReadsTaskFromAnotherReplica() {
        service = newService();
        String taskId = UUID.randomUUID().toString();
        stateBackend.save(new ReindexStateStore.PersistedTask(
                view(taskId, "reindex", "reindexing", 4, 2, 2, 0, "doc-2", null),
                false, Set.of("doc-1")));

        ReindexTaskService.ReindexTaskView remote = service.status(taskId).orElseThrow();

        assertThat(remote.status()).isEqualTo("reindexing");
        assertThat(remote.processed()).isEqualTo(2);
        assertThat(remote.ownerInstance()).isEqualTo("host-1");
        assertThat(remote.epoch()).isEqualTo(1L);
    }

    @Test
    void statusPrefersNewerRemoteTaskWhenNoIdGiven() {
        service = newService();
        String older = UUID.randomUUID().toString();
        String newer = UUID.randomUUID().toString();
        stateBackend.save(new ReindexStateStore.PersistedTask(
                viewAt(older, "completed", 1, 1, 1, 0, "doc-1", "2026-11-09T00:00:01Z"),
                true, Set.of("doc-1")));
        stateBackend.save(new ReindexStateStore.PersistedTask(
                viewAt(newer, "reindexing", 2, 0, 0, 0, "doc-2", "2026-11-09T00:00:02Z"),
                false, Set.of()));

        assertThat(service.status(null).orElseThrow().taskId()).isEqualTo(newer);
    }

    @Test
    void resumeTakesOverInterruptedTaskFromAnotherReplica() throws Exception {
        service = newService();
        String taskId = UUID.randomUUID().toString();
        stateBackend.save(new ReindexStateStore.PersistedTask(
                view(taskId, "reindex", "interrupted", 2, 1, 1, 0, "doc-1", null),
                false, Set.of("doc-1")));
        // 模拟原持有者已退出：先占后放，epoch 已递增
        FencedLease previous = FencedLease.acquire(lock, taskLockKey(), taskId,
                Duration.ofSeconds(30)).orElseThrow();
        previous.release();

        when(archive.isEnabled()).thenReturn(true);
        when(ragService.reindexArchived(anyBoolean(), any(), any()))
                .thenReturn(new RagService.RebuildResult(2, 2, 0, false));

        ReindexTaskService.ReindexTaskView resumed = service.resume(taskId);

        assertThat(resumed.status()).isEqualTo("queued");
        assertThat(resumed.epoch()).isEqualTo(2L);
        assertThat(resumed.ownerInstance()).isEqualTo(distributed.getInstanceId());
        assertThat(awaitTerminal(taskId, 5).status()).isEqualTo("completed");
        verify(ragService).reindexArchived(eq(false), any(), eq(Set.of("doc-1")));
    }

    @Test
    void resumeReportsOwnerWhenAnotherReplicaIsAlive() {
        service = newService();
        String taskId = UUID.randomUUID().toString();
        stateBackend.save(new ReindexStateStore.PersistedTask(
                view(taskId, "reindex", "interrupted", 2, 1, 1, 0, "doc-1", null),
                false, Set.of("doc-1")));
        lock.tryAcquire(taskLockKey(), taskId, Duration.ofSeconds(30)).orElseThrow();

        assertThatThrownBy(() -> service.resume(taskId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无法续接");
        assertThat(service.status(taskId).orElseThrow().status()).isEqualTo("interrupted");
    }

    @Test
    void cancelTakesOverInterruptedTaskFromAnotherReplica() {
        service = newService();
        String taskId = UUID.randomUUID().toString();
        stateBackend.save(new ReindexStateStore.PersistedTask(
                view(taskId, "reindex", "interrupted", 2, 1, 1, 0, "doc-1", null),
                false, Set.of("doc-1")));

        ReindexTaskService.ReindexTaskView cancelled = service.cancel(taskId);

        assertThat(cancelled.status()).isEqualTo("cancelled");
        assertThat(lock.currentOwner(taskLockKey())).isEmpty();
    }

    @Test
    void startupClearsStaleLockMirrorWhenAuthorityIsGone() {
        String taskId = UUID.randomUUID().toString();
        ReindexStateStore store = new ReindexStateStore(stateBackend);
        store.writeLock(new ReindexStateStore.LockMirror("task-1", "dead-replica", 9L,
                Instant.now().plusSeconds(30).toString()));

        service = newService();

        assertThat(store.readLock()).isEmpty();
    }

    private String taskLockKey() {
        return distributed.getLock().getKeyPrefix() + ReindexTaskService.TASK_LOCK_SUFFIX;
    }

    private ReindexTaskService.ReindexTaskView awaitTerminal(String taskId, int seconds)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            ReindexTaskService.ReindexTaskView view = service.status(taskId).orElseThrow();
            if (TERMINAL.contains(view.status())) {
                return view;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("重建任务未在 " + seconds + " 秒内结束: " + taskId);
    }

    private ReindexTaskService.ReindexTaskView awaitRevoked(String taskId, int seconds)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            ReindexTaskService.ReindexTaskView view = service.status(taskId).orElseThrow();
            if (view.revoked() && view.lockReleaseAt() != null) {
                return view;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("重建任务未在 " + seconds + " 秒内进入撤销宽限期: " + taskId);
    }

    private ReindexTaskService.ReindexTaskView submitEventually(int seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            try {
                return service.submit("reindex", false);
            } catch (IllegalStateException e) {
                Thread.sleep(20);
            }
        }
        throw new AssertionError("重建任务锁未在 " + seconds + " 秒内释放");
    }

    private ReindexTaskService.ReindexTaskView awaitStalled(String taskId, int seconds)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            ReindexTaskService.ReindexTaskView view = service.status(taskId).orElseThrow();
            if (view.stalled()) {
                return view;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("重建任务未在 " + seconds + " 秒内标记为 stalled: " + taskId);
    }

    private static ArchivedDocument document(String documentId) {
        return new ArchivedDocument(documentId, "正文", Map.of("source", "test"), "sig", "t");
    }

    private static ReindexTaskService.ReindexTaskView view(String taskId, String mode, String status,
            int total, int processed, int succeeded, int failed, String currentDocumentId,
            String finishedAt) {
        String now = Instant.now().toString();
        int percent = total <= 0 ? 0 : (int) Math.round(processed * 100.0 / total);
        return new ReindexTaskService.ReindexTaskView(taskId, mode, status, total, processed,
                succeeded, failed, percent, currentDocumentId, now, false, false, now, finishedAt,
                false, null, false, null, "host-1", 1L, now);
    }

    private static ReindexTaskService.ReindexTaskView viewAt(String taskId, String status,
            int total, int processed, int succeeded, int failed, String currentDocumentId,
            String startedAt) {
        int percent = total <= 0 ? 0 : (int) Math.round(processed * 100.0 / total);
        return new ReindexTaskService.ReindexTaskView(taskId, "reindex", status, total, processed,
                succeeded, failed, percent, currentDocumentId, startedAt, false, false, startedAt,
                null, false, null, false, null, "host-1", 1L, startedAt);
    }

    /**
     * 可被“接管”的锁：revoke 后 isHeld 返回 false，模拟租约被其他副本抢走。
     */
    private static final class RevocableLock implements DistributedLock {

        private final LocalDistributedLock delegate = new LocalDistributedLock();
        private volatile boolean revoked;

        void revoke() {
            revoked = true;
        }

        @Override
        public Optional<LockHandle> tryAcquire(String key, String owner, Duration lease) {
            return delegate.tryAcquire(key, owner, lease);
        }

        @Override
        public boolean renew(LockHandle handle, Duration lease) {
            return !revoked && delegate.renew(handle, lease);
        }

        @Override
        public void release(LockHandle handle) {
            delegate.release(handle);
        }

        @Override
        public boolean isHeld(LockHandle handle) {
            return !revoked && delegate.isHeld(handle);
        }

        @Override
        public Optional<String> currentOwner(String key) {
            return delegate.currentOwner(key);
        }


    }
}
