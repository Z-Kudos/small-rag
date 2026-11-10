package com.smallrag.ragmcp.rag.chunking;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author zjk
 */
class TextChunkerTest {

    @Test
    void splitsWithConfiguredSizeAndOverlap() {
        assertThat(TextChunker.chunk("字".repeat(2000), 800, 100))
                .hasSize(3)
                .allSatisfy(chunk -> assertThat(TextWeights.weight(chunk)).isLessThanOrEqualTo(800));
    }

    @Test
    void englishCharactersWeightQuarterSoSameConfigYieldsFewerChunks() {
        // 8000 个拉丁字符权重 2000，与 2000 个中文字符等价
        assertThat(TextChunker.chunk("a".repeat(8000), 800, 100))
                .hasSize(3)
                .allSatisfy(chunk -> assertThat(TextWeights.weight(chunk)).isLessThanOrEqualTo(800));
        assertThat(TextChunker.chunk("字".repeat(800), 800, 100)).hasSize(1);
    }

    @Test
    void englishHardCutSnapsToWordBoundary() {
        String text = "alpha ".repeat(200).strip();

        List<String> chunks = TextChunker.chunk(text, 100, 0);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> assertThat(TextWeights.weight(chunk)).isLessThanOrEqualTo(100));
        // 除最后一块外，切点都落在空白边界，不切断单词
        for (int i = 0; i < chunks.size() - 1; i++) {
            assertThat(chunks.get(i)).endsWith(" ");
        }
    }

    @Test
    void overlapGreaterThanSizeFallsBackToTenPercent() {
        assertThat(TextChunker.chunk("字".repeat(1000), 100, 200))
                .hasSizeGreaterThan(1);
    }

    @Test
    void blankTextReturnsEmptyList() {
        assertThat(TextChunker.chunk("   ", 800, 100)).isEmpty();
        assertThat(TextChunker.chunk(null, 800, 100)).isEmpty();
    }
}
