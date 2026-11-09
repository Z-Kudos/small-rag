package com.smallrag.ragmcp.rag.eval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class EvalMetricsTest {

    @Test
    void computesRecallMrrAndNdcg() {
        List<String> ranked = List.of("a", "b", "c");
        Set<String> relevant = Set.of("c");

        assertThat(EvalMetrics.recallAtK(ranked, relevant, 2)).isZero();
        assertThat(EvalMetrics.recallAtK(ranked, relevant, 3)).isEqualTo(1d);
        assertThat(EvalMetrics.reciprocalRank(ranked, relevant, 3)).isEqualTo(1d / 3);
        assertThat(EvalMetrics.ndcgAtK(ranked, relevant, 3))
                .isCloseTo(1d / (Math.log(4) / Math.log(2)), within(1e-9));
    }

    @Test
    void duplicatesDoNotInflateRank() {
        List<String> ranked = List.of("a", "a", "b");
        Set<String> relevant = Set.of("b");

        assertThat(EvalMetrics.reciprocalRank(ranked, relevant, 3)).isEqualTo(1d / 2);
        assertThat(EvalMetrics.ndcgAtK(ranked, relevant, 3))
                .isCloseTo(1d / (Math.log(3) / Math.log(2)), within(1e-9));
    }

    @Test
    void missingRelevantDocumentScoresZero() {
        List<String> ranked = List.of("a", "b");
        Set<String> relevant = Set.of("c");

        assertThat(EvalMetrics.recallAtK(ranked, relevant, 5)).isZero();
        assertThat(EvalMetrics.reciprocalRank(ranked, relevant, 5)).isZero();
        assertThat(EvalMetrics.ndcgAtK(ranked, relevant, 5)).isZero();
    }

    @Test
    void meanHandlesEmptyAndValues() {
        assertThat(EvalMetrics.mean(List.of())).isZero();
        assertThat(EvalMetrics.mean(List.of(1d, 0d, 0.5d))).isCloseTo(0.5d, within(1e-9));
    }
}
