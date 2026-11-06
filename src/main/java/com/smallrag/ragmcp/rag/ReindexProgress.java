package com.smallrag.ragmcp.rag;

/**
 * 重建任务的一次进度事件。
 *
 * <p>由 {@link RagService#reindexArchived(boolean, ReindexListener)} 在清空、逐篇重切、
 * 完成/中止时发出，供 {@link ReindexTaskService} 维护可查询的任务快照。
 *
 * @param stage             当前阶段
 * @param total             待重切文档总数；清空阶段可能为 0
 * @param processed         已处理文档数（成功 + 失败）
 * @param succeeded         成功数
 * @param failed            失败数
 * @param currentDocumentId 当前/最近处理的文档 id，可为 null
 * @param aborted           是否因连续失败而中止
 *
 * @author zjk
 */
public record ReindexProgress(
        Stage stage,
        int total,
        int processed,
        int succeeded,
        int failed,
        String currentDocumentId,
        boolean aborted) {

    /**
     * 重建阶段。
     */
    public enum Stage {
        /** 正在清空当前集合中由本服务写入的片段 */
        CLEARING,
        /** 正在逐篇重切 */
        REINDEXING,
        /** 全部完成 */
        COMPLETED,
        /** 连续失败达到阈值，提前中止 */
        ABORTED,
        /** 收到取消请求，在文档边界处停止 */
        CANCELLED
    }
}
