package com.smallrag.ragmcp.rag.archive;

import java.util.Map;

/**
 * 归档文档的轻量摘要：不包含全文，用于分页列表与启动签名检查。
 *
 * @param documentId     文档 id
 * @param metadata       业务元数据
 * @param chunkSignature 最近一次成功切分的配置签名，未索引为 null
 * @param updatedAt      最近更新时间（ISO-8601）
 * @param textLength     原文字符数
 *
 * @author zjk
 */
public record ArchivedDocumentSummary(
        String documentId,
        Map<String, String> metadata,
        String chunkSignature,
        String updatedAt,
        int textLength) {

    public ArchivedDocumentSummary {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /**
     * 从完整归档记录生成摘要。
     *
     * @param document 归档记录
     * @return 摘要
     */
    public static ArchivedDocumentSummary of(ArchivedDocument document) {
        return new ArchivedDocumentSummary(
                document.documentId(),
                document.metadata(),
                document.chunkSignature(),
                document.updatedAt(),
                document.text() == null ? 0 : document.text().length());
    }
}
