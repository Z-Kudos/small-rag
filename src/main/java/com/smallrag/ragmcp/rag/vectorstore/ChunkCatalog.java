package com.smallrag.ragmcp.rag.vectorstore;

import java.util.List;
import java.util.Optional;

/**
 * chunk 目录端口：供图查询与编辑工具枚举 chunk（可带 doc_id 过滤）。
 *
 * @author zjk
 */
public interface ChunkCatalog {

    /**
     * 列出 chunk；docId 为 null 时全量枚举（按实现的自然顺序，
     * 调用方自行排序）。
     *
     * @param docId 可选文档 id
     * @return chunk 摘要列表
     * @throws IllegalStateException 读取失败时
     */
    List<ChunkSummary> listChunks(String docId) throws IllegalStateException;

    /**
     * 按 point id 精确查找单个 chunk。默认实现退化为全量扫描；
     * Qdrant / SQLite 实现覆盖为按主键直查，避免编辑工具每次枚举全库正文。
     *
     * @param pointId chunk 点 id
     * @return 命中时的 chunk 摘要
     */
    default Optional<ChunkSummary> findByPointId(String pointId) throws IllegalStateException {
        if (pointId == null || pointId.isBlank()) {
            return Optional.empty();
        }
        return listChunks(null).stream()
                .filter(chunk -> pointId.equals(chunk.pointId()))
                .findFirst();
    }
}
