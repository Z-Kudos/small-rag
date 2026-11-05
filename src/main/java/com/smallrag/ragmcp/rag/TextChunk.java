package com.smallrag.ragmcp.rag;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 切分后的文本片段及其附加元数据。
 *
 * @param text     片段正文
 * @param metadata 切分器附加的元数据（如 parent_id、parent_text），可为空
 */
public record TextChunk(String text, Map<String, Object> metadata) {

    public TextChunk {
        metadata = metadata == null ? Map.of() : new LinkedHashMap<>(metadata);
    }
}
