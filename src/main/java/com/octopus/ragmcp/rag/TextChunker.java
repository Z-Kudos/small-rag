package com.octopus.ragmcp.rag;

import java.util.ArrayList;
import java.util.List;

/**
 * 纯文本定长滑窗分块（参考 whale 的 800/100 策略）。
 */
public final class TextChunker {

    private TextChunker() {
    }

    /**
     * @param text    原文
     * @param size    分块大小（字符）
     * @param overlap 相邻分块重叠（字符），非法时回落到 size/10
     * @return 按原文顺序排列的分块；空文本返回空列表
     */
    public static List<String> chunk(String text, int size, int overlap) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        int effectiveSize = Math.max(1, size);
        int effectiveOverlap = overlap < 0 ? 0 : overlap;
        if (effectiveOverlap >= effectiveSize) {
            effectiveOverlap = effectiveSize / 10;
        }

        String normalized = text.strip();
        if (normalized.length() <= effectiveSize) {
            return List.of(normalized);
        }

        List<String> chunks = new ArrayList<>();
        int step = effectiveSize - effectiveOverlap;
        int from = 0;
        while (from < normalized.length()) {
            int to = Math.min(from + effectiveSize, normalized.length());
            chunks.add(normalized.substring(from, to));
            if (to >= normalized.length()) {
                break;
            }
            from += step;
        }
        return chunks;
    }
}
