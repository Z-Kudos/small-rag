package com.smallrag.ragmcp.rag.chunking;

/**
 * 文本切分策略。
 *
 * <p>通过 {@code rag.chunking.strategy} 选择。已归档文档在切换策略并重启后
 * 会自动重切；未归档的历史数据不会，需重新入库。默认策略为
 * {@link #PARENT_CHILD}。Markdown 结构切分（MarkdownChunker）是内部引擎，
 * 由 SEMANTIC 与 PARENT_CHILD 共用，不作为独立策略暴露。
 *
 * @author zjk
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
     * 语义切分：先按 Markdown 标题分节，节内文本块与累积质心比较相似度，
     * 在语义转折处断开；代码/表格等受保护块独立成块，超长文本块回落到
     * 句子级切分。入库时会额外调用一次 Embedding 模型。
     */
    SEMANTIC,

    /**
     * Parent-Child 切分：用较大的父块保留上下文，用较小的子块做检索；
     * 检索命中子块后返回其父块内容。
     */
    PARENT_CHILD
}
