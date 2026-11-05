package com.smallrag.ragmcp.rag;

/**
 * 知识库探活：用于在知识库为空时短路，避免无意义的 Embedding / LLM 调用。
 */
@FunctionalInterface
public interface KnowledgeBaseProbe {

    /**
     * @return true 表示知识库当前没有任何分块
     */
    boolean isEmpty();
}
