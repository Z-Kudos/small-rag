package com.smallrag.ragmcp.rag.vectorstore;

/**
 * 一个 chunk 的存储无关摘要：id（point UUID）、正文与 metadata（含链引用与图结构字段）。
 *
 * @param pointId  底层 point id（Qdrant 为 UUID 字符串；SQLite 为确定性 UUID 字符串）
 * @param text     chunk 正文（不含运行时前置信息）
 * @param metadata chunk metadata（doc_id / chunk_type / parent_id / heading_path / chunk_index / links ...）
 * @author zjk
 */
public record ChunkSummary(String pointId, String text, java.util.Map<String, Object> metadata) {
}
