package com.smallrag.ragmcp.rag.reindex;

import com.smallrag.ragmcp.rag.RagService;

/**
 * 重建进度回调。
 *
 * <p>同步重切（{@link RagService#reindexAll()}、{@link RagService#rebuild()}）使用
 * {@link #NOOP}；异步任务由 {@link ReindexTaskService} 注入实现，把事件转成可查询快照。
 *
 * @author zjk
 */
@FunctionalInterface
public interface ReindexListener {

    /**
     * 不关心进度的调用方使用。
     */
    ReindexListener NOOP = progress -> {
    };

    /**
     * 接收一次进度事件；实现必须足够轻量，且不能抛异常。
     *
     * @param progress 当前进度
     */
    void onProgress(ReindexProgress progress);

    /**
     * 是否已请求取消。重建循环在每篇文档边界检查；实现必须避免阻塞。
     *
     * @return true 表示应在当前文档处理完后停止
     */
    default boolean isCancelled() {
        return false;
    }

    /**
     * 是否仍允许写入索引。看门狗硬超时时会先撤销写权限，再中断工作线程；
     * 重建循环在每次 Qdrant 变更前校验，撤销后抛 {@link ReindexRevokedException}。
     *
     * @return false 表示该任务必须立即停止写入
     */
    default boolean isWritable() {
        return true;
    }

    /**
     * 清空阶段已真正完成（删除请求已返回）；用于持久化检查点，重启续接时避免重复清空。
     */
    default void onCleared() {
    }

    /**
     * 单篇文档重建结束（成功或失败）；用于持久化检查点，重启续接时跳过已成功的文档。
     *
     * <p>实现必须足够轻量，不能阻塞重建循环太久。
     *
     * @param documentId 文档 id
     * @param success    是否重建成功
     */
    default void onDocumentFinished(String documentId, boolean success) {
    }
}
