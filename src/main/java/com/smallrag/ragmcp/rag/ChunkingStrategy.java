package com.smallrag.ragmcp.rag;

/**
 * 文本切分策略。
 *
 * <p>通过 {@code rag.chunking.strategy} 选择，修改策略不会自动重切历史数据：
 * 已入库的向量仍按原策略保留，切换后建议使用新的 {@code QDRANT_COLLECTION}
 * 或重新入库，避免同一集合混用不同切分方式。
 */
public enum ChunkingStrategy {

    /**
     * 固定长度滑窗：实现最简单，按字符数硬切，适合结构不明显的长文本。
     */
    FIXED,

    /**
     * 递归字符切分：优先按段落、换行、句号等自然边界切分，
     * 仅当片段仍超长时才继续用更细的分隔符或硬切。
     */
    RECURSIVE,

    /**
     * 语义切分：先按句子拆分，再根据相邻句子的 Embedding 相似度合并，
     * 在语义发生转折处断开。入库时会额外调用一次 Embedding 模型。
     */
    SEMANTIC,

    /**
     * Parent-Child 切分：用较大的父块保留上下文，用较小的子块做检索；
     * 检索命中子块后返回其父块内容。
     */
    PARENT_CHILD
}
