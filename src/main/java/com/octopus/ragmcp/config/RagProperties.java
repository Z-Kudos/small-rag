package com.octopus.ragmcp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RAG 检索与问答调参配置，对应 {@code rag.*}。
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
     * 单个分块的最大字符数。
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
}
