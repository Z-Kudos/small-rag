package com.smallrag.ragmcp.rag;

import com.smallrag.ragmcp.config.RagProperties;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 切分配置签名：只包含会影响切分结果的参数。
 *
 * <p>原始文档归档时记录该签名；应用启动时对比当前签名与归档签名，
 * 不一致就说明切分策略/参数变了，需要自动重切。刻意不包含
 * {@code semantic.batch-size} 这类只影响性能、不影响结果的参数。
 */
public final class ChunkingSignature {

    private static final String VERSION = "v1";

    private ChunkingSignature() {
    }

    /**
     * @param properties 当前 RAG 配置
     * @return SHA-256 十六进制签名（不包含目标集合）
     */
    public static String of(RagProperties properties) {
        return of(properties, "");
    }

    /**
     * @param properties     当前 RAG 配置
     * @param collectionName 目标 Qdrant 集合；切换集合也应触发重切
     * @return SHA-256 十六进制签名
     */
    public static String of(RagProperties properties, String collectionName) {
        String canonical = canonical(properties, collectionName);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    private static String canonical(RagProperties properties, String collectionName) {
        RagProperties.Chunking chunking = properties.getChunking();
        ChunkingStrategy strategy = chunking.getStrategy() == null
                ? ChunkingStrategy.FIXED
                : chunking.getStrategy();

        StringBuilder value = new StringBuilder(VERSION)
                .append("|collection=").append(collectionName == null ? "" : collectionName)
                .append("|strategy=").append(strategy);
        switch (strategy) {
            case FIXED -> value
                    .append("|size=").append(properties.getChunkSize())
                    .append("|overlap=").append(properties.getChunkOverlap());
            case RECURSIVE -> value
                    .append("|size=").append(properties.getChunkSize())
                    .append("|overlap=").append(properties.getChunkOverlap())
                    .append("|separators=").append(String.join("\u001f", safeSeparators(chunking)));
            case SEMANTIC -> value
                    .append("|max=").append(semanticMaxChars(properties))
                    .append("|min=").append(chunking.getSemantic().getMinChunkChars())
                    .append("|threshold=").append(chunking.getSemantic().getSimilarityThreshold());
            case PARENT_CHILD -> value
                    .append("|parent=").append(chunking.getParentChild().getParentSize())
                    .append('|').append(chunking.getParentChild().getParentOverlap())
                    .append("|child=").append(chunking.getParentChild().getChildSize())
                    .append('|').append(chunking.getParentChild().getChildOverlap())
                    .append("|separators=").append(String.join("\u001f", safeSeparators(chunking)));
        }
        return value.toString();
    }

    private static int semanticMaxChars(RagProperties properties) {
        int configured = properties.getChunking().getSemantic().getMaxChunkChars();
        return configured > 0 ? configured : properties.getChunkSize();
    }

    private static List<String> safeSeparators(RagProperties.Chunking chunking) {
        List<String> separators = chunking.getRecursive().getSeparators();
        return separators == null ? List.of() : separators;
    }
}
