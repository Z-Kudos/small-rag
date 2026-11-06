package com.smallrag.ragmcp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Qdrant 连接与集合配置，对应 {@code rag.qdrant.*}。
 *
 * @author zjk
 */
@Data
@ConfigurationProperties(prefix = "rag.qdrant")
public class QdrantProperties {

    private String host = "localhost";

    /**
     * Qdrant gRPC 端口（不是 REST 的 6333）。
     */
    private int port = 6334;

    private String apiKey = "";

    private String collectionName = "small_rag_text";

    private boolean useTls = false;

    /**
     * 集合向量维度，必须与 Embedding 模型输出维度一致。
     * text-embedding-3-small 默认 1536；切换模型后需同步修改并重建集合。
     */
    private int dimension = 1536;

    /**
     * 集合初始化 / 探活的 RPC 超时（毫秒）。
     */
    private int initTimeoutMs = 5000;

    /**
     * 常规 Qdrant RPC（写入/删除/检索）的 deadline（毫秒）。
     *
     * <p>与 {@link #initTimeoutMs} 分开：初始化要求快速失败，而批量写入
     * 可能远超 5 秒，过短的 deadline 会让大文档重建间歇性失败。
     */
    private long requestTimeoutMs = 30000;
}
