package com.smallrag.ragmcp.rag;

import java.util.List;
import java.util.Optional;

/**
 * 重建任务状态与任务锁的底层存储。
 *
 * <p>生产实现是 {@link QdrantReindexStateBackend}：状态跟随 Qdrant 持久化，
 * 不依赖应用容器的文件系统。测试可注入内存实现。
 *
 * @author zjk
 */
interface ReindexStateBackend {

    /**
     * 读取全部任务状态。
     *
     * @return 任务状态列表；读取失败应抛出运行时异常
     */
    List<ReindexStateStore.PersistedTask> loadAll();

    /**
     * 读取任务锁持有者。
     *
     * @return 持有者 taskId；没有锁时为空
     */
    Optional<String> readOwner();

    /**
     * 写入任务锁持有者。
     *
     * @param taskId 持有者 taskId
     */
    void writeOwner(String taskId);

    /**
     * 释放任务锁；仅在当前持有者匹配时删除。
     *
     * @param taskId 期望的持有者 taskId
     */
    void clearOwner(String taskId);

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
