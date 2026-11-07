package com.smallrag.ragmcp.rag.reindex;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 测试用内存后端：同一实例可以在多个 {@link ReindexTaskService} 之间共享，
 * 用来模拟“重启后从同一存储恢复”。
 *
 * @author zjk
 */
class InMemoryReindexStateBackend implements ReindexStateBackend {

    private final Map<String, ReindexStateStore.PersistedTask> tasks = new LinkedHashMap<>();
    private final AtomicReference<String> owner = new AtomicReference<>();

    @Override
    public synchronized List<ReindexStateStore.PersistedTask> loadAll() {
        return List.copyOf(tasks.values());
    }

    @Override
    public Optional<String> readOwner() {
        return Optional.ofNullable(owner.get());
    }

    @Override
    public void writeOwner(String taskId) {
        owner.set(taskId);
    }

    @Override
    public void clearOwner(String taskId) {
        owner.compareAndSet(taskId, null);
    }

    @Override
    public synchronized void save(ReindexStateStore.PersistedTask task) {
        tasks.put(task.view().taskId(), task);
    }

    @Override
    public synchronized void delete(String taskId) {
        tasks.remove(taskId);
    }

    @Override
    public String location() {
        return "memory";
    }
}
