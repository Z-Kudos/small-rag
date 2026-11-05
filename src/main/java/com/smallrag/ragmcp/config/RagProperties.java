package com.smallrag.ragmcp.config;

import com.smallrag.ragmcp.rag.ChunkingStrategy;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * RAG 检索与切分配置，对应 {@code rag.*}。
 *
 * <p>这些值直接影响召回质量与 prompt 体积：相似度阈值过低会把噪声拼进上下文，
 * 上下文预算过高会稀释关键片段并浪费 token。
 */
@Data
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    /**
     * 未指定 topK 时使用的默认值。
     */
    private int defaultTopK = 4;

    /**
     * 单次检索允许的最大 topK，防止客户端传入过大值。
     */
    private int maxTopK = 20;

    /**
     * 相似度阈值（0~1，Qdrant Cosine）。低于该值的片段直接丢弃。
     */
    private double minScore = 0.35d;

    /**
     * 固定长度/递归切分的目标块大小，同时作为语义切分的默认上限。
     */
    private int chunkSize = 800;

    /**
     * 相邻分块的重叠字符数，必须小于 chunkSize。
     */
    private int chunkOverlap = 100;

    /**
     * 拼入 prompt 的上下文总字符预算，超出后按相似度从高到低截断。
     */
    private int maxContextChars = 12000;

    /**
     * 单条上下文片段的硬截断长度。
     */
    private int maxChunkChars = 1200;

    /**
     * 知识库为空或没有命中时的固定答复（短路，不再调用模型）。
     */
    private String emptyContextAnswer =
            "未检索到相关内容。请先通过 rag_ingest 向知识库写入文本，或换一个查询词重试。";

    /**
     * 原始文档归档目录。自动重切依赖该目录中的原文；
     * 目录不可写时降级为“只入库、不自动重切”。
     */
    private String archiveDir = "./data/documents";

    /**
     * 切分策略及其参数。
     */
    private Chunking chunking = new Chunking();

    /**
     * 切分配置。
     */
    @Data
    public static class Chunking {

        /**
         * 切分策略：FIXED、RECURSIVE、SEMANTIC、PARENT_CHILD。
         */
        private ChunkingStrategy strategy = ChunkingStrategy.FIXED;

        /**
         * 启动时检测切分配置签名，发现变化后自动重切已归档文档。
         */
        private boolean autoRechunk = true;

        private Recursive recursive = new Recursive();

        private Semantic semantic = new Semantic();

        private ParentChild parentChild = new ParentChild();
    }

    /**
     * 递归字符切分参数。
     */
    @Data
    public static class Recursive {

        /**
         * 分隔符优先级，从粗到细。按字面量匹配，不是正则表达式。
         */
        private List<String> separators = List.of(
                "\n\n", "\n", "。", "！", "？", "；", "…",
                ". ", "! ", "? ", "; ", "，", ", ", " ");
    }

    /**
     * 语义切分参数。
     */
    @Data
    public static class Semantic {

        /**
         * 相邻句子合并阈值：余弦相似度低于该值时断开。
         */
        private double similarityThreshold = 0.75d;

        /**
         * 目标最小块大小：未达到该长度时，即使相似度偏低也继续合并，避免碎块。
         */
        private int minChunkChars = 200;

        /**
         * 单块最大字符数，<= 0 时回落到 {@code rag.chunk-size}。
         */
        private int maxChunkChars = 0;

        /**
         * 单次 Embedding 请求的句子数。
         */
        private int batchSize = 64;
    }

    /**
     * Parent-Child 切分参数。
     */
    @Data
    public static class ParentChild {

        /**
         * 父块大小，提供较完整的上下文。
         */
        private int parentSize = 2000;

        /**
         * 父块之间的重叠字符数。
         */
        private int parentOverlap = 200;

        /**
         * 子块大小，实际写入向量库并参与检索。
         */
        private int childSize = 400;

        /**
         * 子块之间的重叠字符数。
         */
        private int childOverlap = 50;
    }
}
