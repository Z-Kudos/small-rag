package com.smallrag.ragmcp.rag;

import org.springframework.ai.embedding.EmbeddingModel;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 语义切分。
 *
 * <p>先把文本拆成句子，再计算相邻句子的 Embedding 余弦相似度：
 * 相似度低于阈值（语义转折）或合并后超长时断开。这样切出的块更贴近主题边界，
 * 代价是入库时需要额外调用一次 Embedding 模型。
 *
 * <p>刻意不做字符级重叠：语义边界通常比固定重叠更准确，强行重叠会把
 * 上一主题的尾巴带进下一块，反而稀释检索信号。
 *
 * @author zjk
 */
public final class SemanticTextChunker {

    /**
     * 中英文句末标点、分号、省略号，以及换行。英文句点要求后面跟空白，
     * 避免把 3.14、example.com 这类内容误判为句子边界。
     */
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile(
            "(?<=[。！？!?；;…])|(?<=\\.)(?=\\s)|\\n+");

    private SemanticTextChunker() {
    }

    /**
     * @param text           原文
     * @param embeddingModel 用于计算句子向量的模型
     * @param maxChars       单块最大字符数
     * @param minChars       单块目标最小字符数，未达到时优先继续合并
     * @param threshold      相邻句子合并阈值（余弦相似度，0~1）
     * @param batchSize      单次 Embedding 请求的句子数
     * @return 按原文顺序排列的语义分块
     */
    public static List<String> chunk(String text,
            EmbeddingModel embeddingModel,
            int maxChars,
            int minChars,
            double threshold,
            int batchSize) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        int effectiveMax = Math.max(1, maxChars);
        int effectiveMin = Math.max(0, Math.min(minChars, effectiveMax));
        int effectiveBatch = Math.max(1, batchSize);

        List<String> units = splitSentences(text, effectiveMax);
        if (units.isEmpty()) {
            return List.of();
        }
        if (units.size() == 1) {
            return units;
        }

        List<float[]> vectors = embedAll(embeddingModel, units, effectiveBatch);
        return merge(units, vectors, effectiveMax, effectiveMin, threshold);
    }

    /**
     * 按句末标点拆分，并把超长句子硬切到 maxChars 以内。
     */
    private static List<String> splitSentences(String text, int maxChars) {
        String[] rawSentences = SENTENCE_BOUNDARY.split(text.strip());
        List<String> units = new ArrayList<>();
        for (String rawSentence : rawSentences) {
            String sentence = rawSentence == null ? "" : rawSentence.strip();
            if (sentence.isEmpty()) {
                continue;
            }
            if (sentence.length() > maxChars) {
                units.addAll(TextChunker.chunk(sentence, maxChars, 0));
            } else {
                units.add(sentence);
            }
        }
        return units;
    }

    private static List<float[]> embedAll(EmbeddingModel embeddingModel, List<String> units, int batchSize) {
        List<float[]> vectors = new ArrayList<>(units.size());
        for (int from = 0; from < units.size(); from += batchSize) {
            int to = Math.min(units.size(), from + batchSize);
            List<float[]> batch = embeddingModel.embed(units.subList(from, to));
            if (batch == null || batch.size() != to - from) {
                throw new IllegalStateException("语义切分失败：Embedding 返回的向量数量与句子数量不一致");
            }
            vectors.addAll(batch);
        }
        return vectors;
    }

    private static List<String> merge(List<String> units,
            List<float[]> vectors,
            int maxChars,
            int minChars,
            double threshold) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder(units.get(0));

        for (int i = 1; i < units.size(); i++) {
            String next = units.get(i);
            boolean exceedsMax = current.length() + separatorLength(current, next) + next.length() > maxChars;
            boolean semanticBreak = current.length() >= minChars
                    && cosine(vectors.get(i - 1), vectors.get(i)) < threshold;

            if (exceedsMax || semanticBreak) {
                chunks.add(current.toString());
                current = new StringBuilder(next);
            } else {
                if (needsSpace(current, next)) {
                    current.append(' ');
                }
                current.append(next);
            }
        }

        if (!current.isEmpty()) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    private static double cosine(float[] left, float[] right) {
        if (left == null || right == null || left.length == 0 || left.length != right.length) {
            return 0d;
        }
        double dot = 0d;
        double leftNorm = 0d;
        double rightNorm = 0d;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        if (leftNorm == 0d || rightNorm == 0d) {
            return 0d;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    /**
     * 中文句子直接拼接；英文句子之间补一个空格。
     */
    private static boolean needsSpace(StringBuilder current, String next) {
        if (current.isEmpty() || next.isEmpty()) {
            return false;
        }
        char left = current.charAt(current.length() - 1);
        char right = next.charAt(0);
        return isAsciiWordChar(left) && isAsciiWordChar(right);
    }

    private static int separatorLength(StringBuilder current, String next) {
        return needsSpace(current, next) ? 1 : 0;
    }

    private static boolean isAsciiWordChar(char value) {
        return (value >= 'a' && value <= 'z')
                || (value >= 'A' && value <= 'Z')
                || (value >= '0' && value <= '9');
    }
}
