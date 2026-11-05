package com.smallrag.ragmcp.rag;

/**
 * 文本切分策略。
 *
 * <p>通过 {@code rag.chunking.strategy} 选择。已归档文档在切换策略并重启后
 * 会自动重切；未归档的历史数据不会，需重新入库。默认策略为
 * {@link #PARENT_CHILD}，并在父子块内部使用 {@link #MARKDOWN} 的结构化切分。
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
     * Markdown / Obsidian 感知切分：按标题分节，保护 fenced code、表格、
     * 引用/标注块等结构，并记录标题路径。适合标准 Markdown 与
     * Obsidian 兼容语法（frontmatter、wikilink、callout 等）。
     */
    MARKDOWN,

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
