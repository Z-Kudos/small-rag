package com.smallrag.ragmcp.rag;

import java.util.ArrayList;
import java.util.List;

/**
 * 递归字符切分。
 *
 * <p>按分隔符列表的优先级依次尝试：先用最粗的边界（段落），再把仍然超长的片段
 * 交给下一级分隔符（换行、句号、逗号……），最后才按字符数硬切。相比固定长度切分，
 * 它更不容易把一个完整句子或段落从中间截断。
 *
 * @author zjk
 */
public final class RecursiveTextChunker {

    private RecursiveTextChunker() {
    }

    /**
     * @param text       原文
     * @param size       目标分块大小（字符）
     * @param overlap    相邻分块重叠（字符）
     * @param separators 分隔符优先级列表，从粗到细
     * @return 按原文顺序排列的分块；空文本返回空列表
     */
    public static List<String> chunk(String text, int size, int overlap, List<String> separators) {
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

        List<String> effectiveSeparators = separators == null ? List.of() : separators;
        List<String> pieces = splitHierarchical(normalized, effectiveSeparators, effectiveSize);
        return merge(pieces, effectiveSize, effectiveOverlap);
    }

    /**
     * 递归拆分：命中分隔符就按它拆，超长片段继续用更细的分隔符拆。
     */
    private static List<String> splitHierarchical(String text, List<String> separators, int size) {
        if (text.length() <= size) {
            return List.of(text);
        }

        for (int i = 0; i < separators.size(); i++) {
            String separator = separators.get(i);
            if (separator == null || separator.isEmpty() || !text.contains(separator)) {
                continue;
            }

            List<String> parts = splitKeepingSeparator(text, separator);
            if (parts.size() <= 1) {
                continue;
            }

            List<String> result = new ArrayList<>();
            List<String> remaining = separators.subList(i + 1, separators.size());
            for (String part : parts) {
                if (part.length() > size) {
                    result.addAll(splitHierarchical(part, remaining, size));
                } else {
                    result.add(part);
                }
            }
            return result;
        }

        // 所有分隔符都无法再拆：按字符数硬切，保证单块不超限
        return TextChunker.chunk(text, size, 0);
    }

    /**
     * 按字面量分隔符拆分，并把分隔符保留在前一个片段末尾，避免丢失换行/标点。
     */
    private static List<String> splitKeepingSeparator(String text, String separator) {
        List<String> parts = new ArrayList<>();
        int from = 0;
        int index;
        while ((index = text.indexOf(separator, from)) >= 0) {
            int to = index + separator.length();
            addIfNotBlank(parts, text.substring(from, to));
            from = to;
        }
        if (from < text.length()) {
            addIfNotBlank(parts, text.substring(from));
        }
        return parts;
    }

    /**
     * 把细碎片段合并到目标大小以内，并给相邻块保留 overlap 字符的重叠。
     */
    private static List<String> merge(List<String> pieces, int size, int overlap) {
        List<String> merged = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String rawPiece : pieces) {
            String piece = rawPiece == null ? "" : rawPiece;
            if (piece.isBlank()) {
                continue;
            }
            if (piece.length() > size) {
                // 理论上 splitHierarchical 已保证不超限，这里兜底避免单块越界
                if (!current.isEmpty()) {
                    merged.add(current.toString());
                    current = new StringBuilder();
                }
                merged.addAll(TextChunker.chunk(piece, size, overlap));
                continue;
            }

            if (current.isEmpty()) {
                current.append(piece);
                continue;
            }

            if (current.length() + piece.length() <= size) {
                current.append(piece);
                continue;
            }

            merged.add(current.toString());
            String tail = overlap > 0
                    ? current.substring(Math.max(0, current.length() - overlap))
                    : "";
            String next = tail + piece;
            if (next.length() > size) {
                next = next.substring(next.length() - size);
            }
            current = new StringBuilder(next);
        }

        if (!current.isEmpty()) {
            merged.add(current.toString());
        }
        return merged;
    }

    private static void addIfNotBlank(List<String> parts, String value) {
        if (value != null && !value.isBlank()) {
            parts.add(value);
        }
    }
}
