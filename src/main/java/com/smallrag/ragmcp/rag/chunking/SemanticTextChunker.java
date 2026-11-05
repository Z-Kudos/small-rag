package com.smallrag.ragmcp.rag.chunking;

import org.springframework.ai.embedding.EmbeddingModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 语义切分（段落感知 + 质心比较）。
 *
 * <p>切分流程：
 * <ol>
 * <li>先按空行把文本拆成段落——段落是最强的天然语义边界，段间无条件断开
 * （当前块不足 {@code minChars} 时才跨段合并，避免碎块，合并时保留空行）；</li>
 * <li>段内按句末标点拆句，句子向量与<b>当前块的累积质心</b>比较：
 * 余弦相似度低于阈值（语义转折）或合并后超长时断开。相比只比较相邻两句，
 * 质心比较对短句、过渡句的向量抖动更鲁棒；</li>
 * <li>末尾块低于 {@code minChars} 时回收合并到前一块，避免一句话大小的碎块。</li>
 * </ol>
 *
 * <p>段落边界与换行符在合并时被保留，切块后仍保留原文的段落结构。
 * 代价是入库时需要额外调用一次 Embedding 模型。当前作为
 * {@code MarkdownChunker.chunkSemantic} 中超长文本块的句子级回落处理器。
 *
 * @author zjk
 */
public final class SemanticTextChunker {

    /**
     * 段落边界：空行（可含空白字符）。
     */
    private static final Pattern PARAGRAPH_BOUNDARY = Pattern.compile("\\n\\s*\\n+");

    /**
     * 中英文句末标点、分号、省略号。英文句点要求后面跟空白，
     * 避免把 3.14、example.com 这类内容误判为句子边界。
     * 换行不在此处理——段落切分先行，段内换行保留在句子文本中。
     */
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile(
            "(?<=[。！？!?；;…])|(?<=\\.)(?=\\s)");

    private SemanticTextChunker() {
    }

    /**
     * @param text           原文
     * @param embeddingModel 用于计算句子向量的模型
     * @param maxChars       单块最大字符数
     * @param minChars       单块目标最小字符数，未达到时优先继续合并
     * @param threshold      相似度合并阈值（句子向量与当前块质心的余弦相似度，0~1）
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

        List<String> units = new ArrayList<>();
        List<Integer> paragraphOf = new ArrayList<>();
        List<String> paragraphs = splitParagraphs(text);
        for (int p = 0; p < paragraphs.size(); p++) {
            for (String sentence : splitSentences(paragraphs.get(p), effectiveMax)) {
                units.add(sentence);
                paragraphOf.add(p);
            }
        }
        if (units.isEmpty()) {
            return List.of();
        }
        if (units.size() == 1) {
            return List.of(units.get(0));
        }

        List<float[]> vectors = embedAll(embeddingModel, units, effectiveBatch);
        return merge(units, vectors, paragraphOf, effectiveMax, effectiveMin, threshold);
    }

    /**
     * 按空行拆分段落，保留段落内的换行结构。
     */
    private static List<String> splitParagraphs(String text) {
        List<String> paragraphs = new ArrayList<>();
        for (String paragraph : PARAGRAPH_BOUNDARY.split(text.strip())) {
            String stripped = paragraph.strip();
            if (!stripped.isEmpty()) {
                paragraphs.add(stripped);
            }
        }
        return paragraphs;
    }

    /**
     * 常见英文缩写：句子切分后以此结尾的片段不构成句界，回并与后句。
     */
    private static final List<String> ABBREVIATIONS = List.of(
            "Dr.", "Mr.", "Mrs.", "Ms.", "Prof.", "Sr.", "Jr.", "St.",
            "vs.", "etc.", "e.g.", "i.e.", "Fig.", "fig.", "No.", "no.",
            "Vol.", "vol.", "approx.", "Approx.", "Inc.", "Ltd.", "Corp.");

    private static boolean endsWithAbbreviation(String unit) {
        String trimmed = unit.strip();
        for (String abbreviation : ABBREVIATIONS) {
            if (trimmed.endsWith(abbreviation)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 按句末标点拆分，并把超长句子硬切到 maxChars 以内。
     * 英文缩写（Dr. / e.g. 等）后的空白不视为句界，回并与后句。
     */
    private static List<String> splitSentences(String text, int maxChars) {
        String[] rawSentences = SENTENCE_BOUNDARY.split(text.strip());
        List<String> merged = new ArrayList<>();
        StringBuilder pending = null;
        for (String rawSentence : rawSentences) {
            String sentence = rawSentence == null ? "" : rawSentence.strip();
            if (sentence.isEmpty()) {
                continue;
            }
            if (pending != null) {
                // 补回切分处被丢弃的空格
                pending.append(' ').append(sentence);
            } else {
                pending = new StringBuilder(sentence);
            }
            if (!endsWithAbbreviation(pending.toString())) {
                merged.add(pending.toString());
                pending = null;
            }
        }
        if (pending != null) {
            merged.add(pending.toString());
        }

        List<String> units = new ArrayList<>();
        for (String sentence : merged) {
            if (TextWeights.weight(sentence) > maxChars) {
                units.addAll(TextChunker.chunk(sentence, maxChars, 0));
            } else {
                units.add(sentence);
            }
        }
        return units;
    }

    public static List<float[]> embedAll(EmbeddingModel embeddingModel, List<String> units, int batchSize) {
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

    /**
     * 合并句子为块：段间强边界 + 段内质心比较。
     */
    private static List<String> merge(List<String> units,
            List<float[]> vectors,
            List<Integer> paragraphOf,
            int maxChars,
            int minChars,
            double threshold) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder(units.get(0));
        double currentWeight = TextWeights.weight(current.toString());
        float[] centroidSum = vectors.get(0).clone();
        int currentParagraph = paragraphOf.get(0);

        for (int i = 1; i < units.size(); i++) {
            String next = units.get(i);
            int paragraph = paragraphOf.get(i);
            boolean paragraphBreak = paragraph != currentParagraph;
            double separatorWeight = paragraphBreak
                    ? TextWeights.weight("\n\n")
                    : (needsSpace(current, next) ? TextWeights.NARROW : 0d);
            boolean exceedsMax = currentWeight + separatorWeight + TextWeights.weight(next) > maxChars;
            boolean semanticBreak = !paragraphBreak
                    && currentWeight >= minChars
                    && cosine(centroidSum, vectors.get(i)) < threshold;

            if (exceedsMax || (paragraphBreak && currentWeight >= minChars) || semanticBreak) {
                chunks.add(current.toString());
                current = new StringBuilder(next);
                currentWeight = TextWeights.weight(current.toString());
                centroidSum = vectors.get(i).clone();
            } else {
                if (paragraphBreak) {
                    // 跨段合并（前一块不足 minChars）：保留段落间的空行
                    current.append("\n\n");
                } else if (needsSpace(current, next)) {
                    current.append(' ');
                }
                current.append(next);
                currentWeight = TextWeights.weight(current.toString());
                for (int d = 0; d < centroidSum.length; d++) {
                    centroidSum[d] += vectors.get(i)[d];
                }
            }
            currentParagraph = paragraph;
        }

        if (!current.isEmpty()) {
            chunks.add(current.toString());
        }
        return mergeTail(chunks, maxChars, minChars);
    }

    /**
     * 尾块回收：最后一块低于 minChars 且与前一块合并不超限时并回去，
     * 避免一句话大小的碎块单独入库。
     */
    private static List<String> mergeTail(List<String> chunks, int maxChars, int minChars) {
        if (minChars <= 0 || chunks.size() < 2) {
            return chunks;
        }
        String last = chunks.get(chunks.size() - 1);
        if (TextWeights.weight(last) < minChars) {
            String previous = chunks.get(chunks.size() - 2);
            if (TextWeights.weight(previous) + TextWeights.weight("\n\n") + TextWeights.weight(last) <= maxChars) {
                List<String> merged = new ArrayList<>(chunks);
                merged.set(merged.size() - 2, previous + "\n\n" + last);
                merged.remove(merged.size() - 1);
                return merged;
            }
        }
        return chunks;
    }

    /**
     * 线性插值百分位数：用于分位数断点模式（替代绝对相似度阈值）。
     */
    static double percentile(List<Double> values, double p) {
        if (values == null || values.isEmpty()) {
            return Double.NaN;
        }
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        if (sorted.size() == 1 || p <= 0d) {
            return sorted.get(0);
        }
        if (p >= 1d) {
            return sorted.get(sorted.size() - 1);
        }
        double pos = p * (sorted.size() - 1);
        int lo = (int) Math.floor(pos);
        int hi = Math.min(sorted.size() - 1, lo + 1);
        return sorted.get(lo) + (sorted.get(hi) - sorted.get(lo)) * (pos - lo);
    }

    public static double cosine(float[] left, float[] right) {
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

    private static boolean isAsciiWordChar(char value) {
        return (value >= 'a' && value <= 'z')
                || (value >= 'A' && value <= 'Z')
                || (value >= '0' && value <= '9');
    }
}
