package com.smallrag.ragmcp.rag.eval;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 检索评测指标：recall@k、MRR、nDCG@k（binary relevance）。
 *
 * <p>只用文档 id 判断相关性；同一文档的多个片段只按首次出现计算一次。
 *
 * @author zjk
 */
final class EvalMetrics {

    private EvalMetrics() {
    }

    /**
     * recall@k：前 k 个结果中是否至少命中一个相关文档。
     *
     * @param ranked   按相关性排序的文档 id
     * @param relevant 相关文档 id
     * @param k        截断位置
     * @return 0 或 1
     */
    static double recallAtK(List<String> ranked, Set<String> relevant, int k) {
        return firstRelevantRank(ranked, relevant, k) > 0 ? 1d : 0d;
    }

    /**
     * reciprocal rank：第一个相关文档排名的倒数。
     *
     * @param ranked   按相关性排序的文档 id
     * @param relevant 相关文档 id
     * @param k        截断位置
     * @return 0 ~ 1
     */
    static double reciprocalRank(List<String> ranked, Set<String> relevant, int k) {
        int rank = firstRelevantRank(ranked, relevant, k);
        return rank == 0 ? 0d : 1d / rank;
    }

    /**
     * nDCG@k（binary relevance，每个文档只计一次）。
     *
     * @param ranked   按相关性排序的文档 id
     * @param relevant 相关文档 id
     * @param k        截断位置
     * @return 0 ~ 1
     */
    static double ndcgAtK(List<String> ranked, Set<String> relevant, int k) {
        double dcg = 0d;
        Set<String> seen = new HashSet<>();
        int position = 0;
        for (int i = 0; i < Math.min(k, ranked.size()); i++) {
            String docId = ranked.get(i);
            if (docId == null || !seen.add(docId)) {
                continue;
            }
            position++;
            if (relevant.contains(docId)) {
                dcg += 1d / (Math.log(position + 1) / Math.log(2));
            }
        }
        double idcg = 0d;
        for (int i = 1; i <= Math.min(k, relevant.size()); i++) {
            idcg += 1d / (Math.log(i + 1) / Math.log(2));
        }
        return idcg == 0d ? 0d : dcg / idcg;
    }

    /**
     * @param values 指标值
     * @return 平均值；空集合返回 0
     */
    static double mean(Collection<Double> values) {
        return values.isEmpty()
                ? 0d
                : values.stream().mapToDouble(Double::doubleValue).average().orElse(0d);
    }

    private static int firstRelevantRank(List<String> ranked, Set<String> relevant, int k) {
        Set<String> seen = new HashSet<>();
        int position = 0;
        for (int i = 0; i < Math.min(k, ranked.size()); i++) {
            String docId = ranked.get(i);
            if (docId == null || !seen.add(docId)) {
                continue;
            }
            position++;
            if (relevant.contains(docId)) {
                return position;
            }
        }
        return 0;
    }
}
