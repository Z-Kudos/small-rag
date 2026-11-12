package com.smallrag.ragmcp.rag.vectorstore;

import java.util.List;

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
}
