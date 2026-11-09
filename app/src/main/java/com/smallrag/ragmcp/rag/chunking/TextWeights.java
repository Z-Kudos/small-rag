package com.smallrag.ragmcp.rag.chunking;

/**
 * 语言归一的长度度量：CJK 等全宽字符计 1，拉丁等半宽字符计 0.25。
 *
 * <p>所有切分配置（chunk-size、parent-size、max-chunk-chars 等）的单位因此是
 * “全宽字符当量”：同一配置值对中文与英文的语义体量一致
 * （中文约 1 字 1 token，英文约 4 字符 1 token）。
 *
 * @author zjk
 */
public final class TextWeights {

    /** CJK 等全宽字符权重。 */
    public static final double WIDE = 1.0d;

    /** 拉丁等半宽字符权重。 */
    public static final double NARROW = 0.25d;

    private TextWeights() {
    }

    static boolean isWide(char value) {
        return (value >= 0x2E80 && value <= 0x9FFF)      // CJK 部首/符号、日文假名、CJK 统一表意
                || (value >= 0xAC00 && value <= 0xD7AF)  // 谚文音节
                || (value >= 0xF900 && value <= 0xFAFF)  // CJK 兼容表意
                || (value >= 0xFF00 && value <= 0xFFEF); // 全角形式
    }

    static double weightOf(char value) {
        return isWide(value) ? WIDE : NARROW;
    }

    /**
     * 文本的有效长度（全宽字符当量）。
     */
    public static double weight(String text) {
        if (text == null || text.isEmpty()) {
            return 0d;
        }
        double total = 0d;
        for (int i = 0; i < text.length(); i++) {
            total += weightOf(text.charAt(i));
        }
        return total;
    }

    /**
     * 从头累计，返回权重不超过 maxWeight 的最大字符数。
     */
    public static int fitChars(String text, double maxWeight) {
        if (text == null || maxWeight <= 0d) {
            return 0;
        }
        double acc = 0d;
        int i = 0;
        while (i < text.length()) {
            double w = weightOf(text.charAt(i));
            if (acc + w > maxWeight) {
                break;
            }
            acc += w;
            i++;
        }
        return i;
    }

    /**
     * 从 endIndex 向前累计，返回包含的最早字符下标（后缀权重不超过 maxWeight）。
     */
    public static int fitCharsBefore(String text, int endIndex, double maxWeight) {
        if (text == null || maxWeight <= 0d) {
            return endIndex;
        }
        double acc = 0d;
        int i = endIndex;
        while (i > 0) {
            double w = weightOf(text.charAt(i - 1));
            if (acc + w > maxWeight) {
                break;
            }
            acc += w;
            i--;
        }
        return i;
    }
}
