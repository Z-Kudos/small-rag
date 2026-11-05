package com.smallrag.ragmcp.rag;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author zjk
 */
class TextChunkerTest {

    @Test
    void splitsWithConfiguredSizeAndOverlap() {
        assertThat(TextChunker.chunk("a".repeat(2000), 800, 100))
                .hasSize(3)
                .allSatisfy(chunk -> assertThat(chunk.length()).isLessThanOrEqualTo(800));
    }

    @Test
    void overlapGreaterThanSizeFallsBackToTenPercent() {
        assertThat(TextChunker.chunk("a".repeat(1000), 100, 200))
                .hasSizeGreaterThan(1);
    }

    @Test
    void blankTextReturnsEmptyList() {
        assertThat(TextChunker.chunk("   ", 800, 100)).isEmpty();
        assertThat(TextChunker.chunk(null, 800, 100)).isEmpty();
    }
}
