package com.smallrag.ragmcp.rag.reindex;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author zjk
 */
class ReindexStateStoreTest {

    private final InMemoryReindexStateBackend backend = new InMemoryReindexStateBackend();
    private final ReindexStateStore store = new ReindexStateStore(backend);

    @Test
    void savesLoadsAndDeletesTasks() {
        ReindexTaskService.ReindexTaskView view = view("task-1", "reindexing", 2, 1);
        ReindexStateStore.PersistedTask task =
                new ReindexStateStore.PersistedTask(view, true, Set.of("doc-1"));

        store.save(task);

        assertThat(store.loadAll()).containsExactly(task);
        store.delete("task-1");
        assertThat(store.loadAll()).isEmpty();
    }

    @Test
    void lockMirrorFollowsCurrentOwner() {
        store.writeLock(new ReindexStateStore.LockMirror("task-1", "host-1", 7L, null));
        assertThat(store.readLock()).map(ReindexStateStore.LockMirror::taskId).contains("task-1");
        assertThat(store.readLock()).map(ReindexStateStore.LockMirror::epoch).contains(7L);

        // 非持有者不能清锁
        store.clearLock("task-2");
        assertThat(store.readLock()).map(ReindexStateStore.LockMirror::taskId).contains("task-1");

        store.clearLock("task-1");
        assertThat(store.readLock()).isEmpty();
    }

    @Test
    void readFailuresDegradeToEmpty() {
        ReindexStateBackend broken = new InMemoryReindexStateBackend() {
            @Override
            public java.util.List<ReindexStateStore.PersistedTask> loadAll() {
                throw new IllegalStateException("qdrant down");
            }

            @Override
            public Optional<ReindexStateStore.LockMirror> readLock() {
                throw new IllegalStateException("qdrant down");
            }
        };
        ReindexStateStore degraded = new ReindexStateStore(broken);

        assertThat(degraded.loadAll()).isEmpty();
        assertThat(degraded.readLock()).isEmpty();
    }

    private static ReindexTaskService.ReindexTaskView view(String taskId, String status,
            int total, int processed) {
        String now = Instant.now().toString();
        int percent = total <= 0 ? 0 : (int) Math.round(processed * 100.0 / total);
        return new ReindexTaskService.ReindexTaskView(taskId, "reindex", status, total, processed,
                processed, 0, percent, "doc-1", now, false, false, now, null,
                false, null, false, null, "host-1", 3L, now);
    }
}
