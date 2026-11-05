package com.smallrag.ragmcp.rag;

import com.smallrag.ragmcp.config.RagProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author zjk
 */
class ChunkingSignatureTest {

    @Test
    void sameRelevantConfigProducesSameSignature() {
        RagProperties first = new RagProperties();
        RagProperties second = new RagProperties();
        second.getChunking().setAutoRechunk(false);

        assertThat(ChunkingSignature.of(first)).isEqualTo(ChunkingSignature.of(second));
    }

    @Test
    void changingStrategyOrSizeChangesSignature() {
        RagProperties properties = new RagProperties();
        properties.getChunking().setStrategy(ChunkingStrategy.FIXED);
        String fixed = ChunkingSignature.of(properties);

        properties.setChunkSize(400);
        String smaller = ChunkingSignature.of(properties);
        assertThat(smaller).isNotEqualTo(fixed);

        properties.getChunking().setStrategy(ChunkingStrategy.RECURSIVE);
        assertThat(ChunkingSignature.of(properties)).isNotEqualTo(smaller);
    }

    @Test
    void irrelevantParametersDoNotChangeSignature() {
        RagProperties properties = new RagProperties();
        properties.getChunking().setStrategy(ChunkingStrategy.FIXED);
        String before = ChunkingSignature.of(properties);

        properties.getChunking().getSemantic().setSimilarityThreshold(0.1);
        properties.getChunking().getSemantic().setBatchSize(8);
        properties.getChunking().getParentChild().setParentSize(999);

        assertThat(ChunkingSignature.of(properties)).isEqualTo(before);
    }

    @Test
    void defaultStrategyIsParentChild() {
        assertThat(new RagProperties().getChunking().getStrategy())
                .isEqualTo(ChunkingStrategy.PARENT_CHILD);
    }

    @Test
    void changingTargetCollectionChangesSignature() {
        RagProperties properties = new RagProperties();

        assertThat(ChunkingSignature.of(properties, "collection_a"))
                .isNotEqualTo(ChunkingSignature.of(properties, "collection_b"));
    }
}
