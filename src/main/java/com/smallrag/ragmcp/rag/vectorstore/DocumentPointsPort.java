package com.smallrag.ragmcp.rag.vectorstore;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 文档 point 的底层存储端口：屏蔽 Qdrant / SQLite 等后端
 * 在“枚举旧 point、按 id 删除、取回父块、清理孤儿片段”上的差异。
 *
 * <p>向量读写（upsert / similaritySearch）继续走 Spring AI 的
 * {@code VectorStore}；本接口只承载 {@code VectorStore} 表达不了的操作。
 *
 * <p>实现类必须保证：
 * <ul>
 *   <li>{@link #findPointIds} 失败时返回 {@code Optional.empty()}，由调用方回退到先删后写；</li>
 *   <li>{@link #deletePoints} 失败抛 {@link IllegalStateException}，由调用方显式处理；</li>
 *   <li>{@link #cleanupOrphans} 中删除失败抛 {@link IllegalStateException}。</li>
 * </ul>
 *
 * @author zjk
 */
public interface DocumentPointsPort {

    /**
     * 查询文档现有 point id；失败时返回 empty，由调用方回退到先删后写。
     *
     * @param docId 文档 id
     * @return point id 集合；读取失败时为 {@code Optional.empty()}
     */
    Optional<Set<String>> findPointIds(String docId);

    /**
     * 按 point id 删除。
     *
     * @param pointIds point id 集合
     * @throws IllegalStateException 删除失败时
     */
    void deletePoints(Set<String> pointIds) throws IllegalStateException;

    /**
     * 清理全量重建后的孤儿片段：无对应子块的父块，以及缺少
     * {@code chunk_type} 的旧版本片段。
     *
     * @return 扫描与删除统计
     * @throws IllegalStateException 任一步失败时
     */
    CleanupResult cleanupOrphans() throws IllegalStateException;

    /**
     * 按父块业务 id 批量取回父块正文与可选向量。
     *
     * @param parentIds 父块业务 id 集合
     * @return 以 parent_id 为 key 的结果；整体取回失败时返回空 map（调用方回退为子块结果）
     */
    Map<String, ParentPoint> fetchParents(Set<String> parentIds);
}
