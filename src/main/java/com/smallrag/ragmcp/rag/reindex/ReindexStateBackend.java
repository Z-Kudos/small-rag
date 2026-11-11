package com.smallrag.ragmcp.rag.reindex;

import java.util.List;

/**
 * 重建任务状态与任务锁的底层存储。
 *
 * <p>生产实现是 {@link QdrantReindexStateBackend}：状态跟随 Qdrant 持久化，
 * 不依赖应用容器的文件系统。测试可注入内存实现。
 *
 * @author zjk
 */
public interface ReindexStateBackend {

    /**
     * 读取全部任务状态。
     *
     * @return 任务状态列表；读取失败应抛出运行时异常
     */
    List<ReindexStateStore.PersistedTask> loadAll();

    /**
     * 保存或覆盖任务状态。
     *
     * @param task 任务状态
     */
    void save(ReindexStateStore.PersistedTask task);

    /**
     * 删除任务状态。
     *
     * @param taskId 任务 id
     */
    void delete(String taskId);

    /**
     * 存储位置的诊断描述。
     *
     * @return 例如 {@code qdrant:small_rag_text_reindex_state}
     */
    String location();
}
