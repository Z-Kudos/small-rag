package com.smallrag.ragmcp.rag;

import com.smallrag.ragmcp.config.RagProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChunkingServiceTest {

    @Test
    void recursiveStrategyRespectsChunkSize() {
        RagProperties properties = new RagProperties();
        properties.setChunkSize(40);
        properties.setChunkOverlap(5);
        properties.getChunking().setStrategy(ChunkingStrategy.RECURSIVE);

        ChunkingService service = new ChunkingService(properties, mock(EmbeddingModel.class));

        ChunkingService.ChunkingResult result = service.chunk(
                "第一段内容。第二段内容。\n\n第三段内容。第四段内容。", "doc-1");

        assertThat(result.parentCount()).isZero();
        assertThat(result.chunks()).isNotEmpty();
        assertThat(result.chunks()).allSatisfy(chunk -> {
            assertThat(chunk.text().length()).isLessThanOrEqualTo(40);
            assertThat(chunk.metadata()).containsEntry("chunk_type", "chunk");
        });
    }

    @Test
    void semanticStrategySplitsOnLowSimilarity() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(anyList())).thenAnswer(invocation -> {
            List<String> sentences = invocation.getArgument(0);
            List<float[]> vectors = new ArrayList<>(sentences.size());
            for (String sentence : sentences) {
                vectors.add(sentence.contains("数据库") ? new float[] { 0f, 1f } : new float[] { 1f, 0f });
            }
            return vectors;
        });

        RagProperties properties = new RagProperties();
        properties.getChunking().setStrategy(ChunkingStrategy.SEMANTIC);
        properties.getChunking().getSemantic().setSimilarityThreshold(0.9);
        properties.getChunking().getSemantic().setMinChunkChars(0);
        properties.getChunking().getSemantic().setMaxChunkChars(100);

        ChunkingService service = new ChunkingService(properties, embeddingModel);
        ChunkingService.ChunkingResult result = service.chunk("苹果。香蕉。数据库。", "doc-1");

        assertThat(result.chunks()).hasSize(2);
        assertThat(result.chunks().get(0).text()).isEqualTo("苹果。香蕉。");
        assertThat(result.chunks().get(1).text()).isEqualTo("数据库。");
    }

    @Test
    void parentChildStrategyStoresParentTextInChildMetadata() {
        RagProperties properties = new RagProperties();
        properties.getChunking().setStrategy(ChunkingStrategy.PARENT_CHILD);
        properties.getChunking().getParentChild().setParentSize(120);
        properties.getChunking().getParentChild().setParentOverlap(10);
        properties.getChunking().getParentChild().setChildSize(40);
        properties.getChunking().getParentChild().setChildOverlap(5);

        ChunkingService service = new ChunkingService(properties, mock(EmbeddingModel.class));
        ChunkingService.ChunkingResult result = service.chunk("知识库内容。".repeat(80), "doc-1");

        assertThat(result.parentCount()).isGreaterThan(0);
        assertThat(result.chunks()).isNotEmpty();
        assertThat(result.chunks()).allSatisfy(chunk -> {
            assertThat(chunk.text().length()).isLessThanOrEqualTo(40);
            assertThat(chunk.metadata())
                    .containsEntry("chunk_type", "child")
                    .containsKey("parent_id")
                    .containsKey("parent_index")
                    .containsKey("parent_text");
            assertThat(String.valueOf(chunk.metadata().get("parent_id"))).startsWith("doc-1#p");
        });
    }
}
