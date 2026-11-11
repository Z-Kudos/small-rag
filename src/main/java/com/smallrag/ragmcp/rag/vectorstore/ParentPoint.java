package com.smallrag.ragmcp.rag.vectorstore;

/**
 * 父块检索结果：按 {@code parent_id} 聚合的独立 point，
 * 带正文与可选向量（用于 MMR；无向量时 MMR 退化为纯相关排序）。
 *
 * @param parentId 父块业务 id（子块 metadata.parent_id 的值）
 * @param pointId  底层存储中的 point id（Qdrant 为 UUID 字符串）
 * @param text     父块正文
 * @param vector   父块向量；不可用时为 null
 * @author zjk
 */
public record ParentPoint(String parentId, String pointId, String text, float[] vector) {
}
