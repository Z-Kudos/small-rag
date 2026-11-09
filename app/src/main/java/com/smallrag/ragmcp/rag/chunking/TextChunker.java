package com.smallrag.ragmcp.rag.chunking;

import java.util.ArrayList;
import java.util.List;

/**
 * 滑窗分块。长度按 {@link TextWeights} 全宽字符当量计量；
 * 拉丁文本的硬切点自动回退到最近的空白/连字符边界，避免把英文单词切断。
 *
 * @author zjk
 */
public final class TextChunker {

    /** 单词边界回退的最大搜索字符数，避免 URL 等超长 token 导致块过小。 */
    private static final int MAX_WORD_BACKOFF = 32;

    private TextChunker() {
    }

    /**
     * @param text    原文
     * @param size    分块大小（全宽字符当量）
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
        if (TextWeights.weight(normalized) <= effectiveSize) {
            return List.of(normalized);
        }

        List<String> chunks = new ArrayList<>();
        int from = 0;
        while (from < normalized.length()) {
            int to = Math.min(normalized.length(),
                    from + Math.max(1, TextWeights.fitChars(normalized.substring(from), effectiveSize)));
            to = snapToWordBoundary(normalized, from, to);
            chunks.add(normalized.substring(from, to));
            if (to >= normalized.length()) {
                break;
            }
            int step = Math.max(1, effectiveSize - effectiveOverlap);
            int advance = Math.max(1, TextWeights.fitChars(normalized.substring(from, to), step));
            from = Math.min(to, from + advance);
        }
        return chunks;
    }

    /**
     * 切点落在 ASCII 单词中间时，回退到最近的空白/连字符边界；
     * 中文等无空格文本不受影响。
     */
    private static int snapToWordBoundary(String text, int from, int to) {
        if (to >= text.length() || to - from < 2) {
            return to;
        }
        if (!isAsciiWordChar(text.charAt(to - 1)) || !isAsciiWordChar(text.charAt(to))) {
            return to;
        }
        int limit = Math.max(from + 1, to - MAX_WORD_BACKOFF);
        for (int i = to - 1; i >= limit; i--) {
            char value = text.charAt(i);
            if (value == ' ' || value == '\t' || value == '\n' || value == '-' || value == '/') {
                return i + 1;
            }
            if (!isAsciiWordChar(value)) {
                break;
            }
        }
        return to;
    }

    private static boolean isAsciiWordChar(char value) {
        return (value >= 'a' && value <= 'z')
                || (value >= 'A' && value <= 'Z')
                || (value >= '0' && value <= '9');
    }
}
