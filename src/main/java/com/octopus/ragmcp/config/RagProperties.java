package com.octopus.ragmcp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RAG 检索相关配置。
 */
@Data
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    /**
     * 向量存储实现：memory（默认，进程内）或 redis。
     */
    private String vectorStore = "memory";

    /**
     * 未指定 topK 时使用的默认值。
     */
    private int defaultTopK = 4;

    /**
     * 单次检索允许的最大 topK，防止客户端传入过大值。
     */
    private int maxTopK = 20;
}
