package com.smallrag.ragmcp.rag;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 原始文档归档记录：自动重切的数据来源。
 *
 * @param documentId     文档 id
 * @param text           原始全文
 * @param metadata       入库时的业务元数据
 * @param chunkSignature 最近一次成功切分时的配置签名
 * @param updatedAt      最近一次归档/重切时间
 *
 * @author zjk
 */
public record ArchivedDocument(
        String documentId,
        String text,
        Map<String, String> metadata,
        String chunkSignature,
        String updatedAt) {

    public ArchivedDocument {
        metadata = metadata == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }
}
