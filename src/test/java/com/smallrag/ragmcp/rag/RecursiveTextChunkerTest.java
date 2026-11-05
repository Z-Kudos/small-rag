package com.smallrag.ragmcp.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author zjk
 */
class RecursiveTextChunkerTest {

    private static final List<String> SEPARATORS = List.of("\n\n", "\n", "。", "！", "？", "，", " ");

    @Test
    void prefersParagraphAndSentenceBoundaries() {
        String text = "第一段第一句。第一段第二句。\n\n第二段第一句。第二段第二句。\n\n第三段内容。";

        List<String> chunks = RecursiveTextChunker.chunk(text, 18, 3, SEPARATORS);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.length()).isLessThanOrEqualTo(18));
        assertThat(String.join("", chunks)).contains("第一段第一句").contains("第三段内容");
    }

    @Test
    void fallsBackToHardSplitWhenNoSeparatorMatches() {
        List<String> chunks = RecursiveTextChunker.chunk("a".repeat(200), 50, 5, List.of("\n\n", "。"));

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.length()).isLessThanOrEqualTo(50));
    }

    @Test
    void keepsSpacesWhenSplittingEnglishText() {
        List<String> chunks = RecursiveTextChunker.chunk(
                "alpha beta gamma delta epsilon zeta eta theta", 20, 5, List.of(" "));

        assertThat(chunks).isNotEmpty();
        assertThat(chunks.get(0)).contains("alpha beta");
        assertThat(String.join("", chunks)).doesNotContain("betagamma").doesNotContain("deltaepsilon");
    }

    @Test
    void blankTextReturnsEmptyList() {
        assertThat(RecursiveTextChunker.chunk("   ", 100, 10, SEPARATORS)).isEmpty();
        assertThat(RecursiveTextChunker.chunk(null, 100, 10, SEPARATORS)).isEmpty();
    }
}
