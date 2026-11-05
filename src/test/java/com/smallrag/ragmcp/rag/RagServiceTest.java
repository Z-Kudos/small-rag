package com.smallrag.ragmcp.rag;

import com.smallrag.ragmcp.config.RagProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RagServiceTest {

    @Test
    void emptyKnowledgeBaseShortCircuitsWithoutEmbedding() {
        VectorStore vectorStore = mock(VectorStore.class);
        RagProperties properties = new RagProperties();

        RagService service = new RagService(vectorStore, properties, () -> true);

        assertThat(service.query("项目支持哪些向量数据库？", 3))
                .isEqualTo(properties.getEmptyContextAnswer());
        verifyNoInteractions(vectorStore);
    }

    @Test
    void noHitsReturnsFriendlyMessage() {
        VectorStore vectorStore = mock(VectorStore.class);
        RagProperties properties = new RagProperties();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        RagService service = new RagService(vectorStore, properties, () -> false);

        assertThat(service.query("任意问题", 3)).isEqualTo(properties.getEmptyContextAnswer());
    }

    @Test
    void hitsReturnNumberedContextWithSourceAndScore() {
        VectorStore vectorStore = mock(VectorStore.class);
        RagProperties properties = new RagProperties();
        Document document = Document.builder()
                .id(UUID.randomUUID().toString())
                .text("Qdrant 负责向量存储")
                .metadata(Map.of("source", "verify"))
                .score(0.9)
                .build();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(document));

        RagService service = new RagService(vectorStore, properties, () -> false);

        assertThat(service.query("向量库是什么？", 3))
                .contains("[1]")
                .contains("source=verify")
                .contains("score=0.900")
                .contains("Qdrant 负责向量存储");
    }

    @Test
    void ingestSplitsPureTextIntoOverlappingChunks() {
        VectorStore vectorStore = mock(VectorStore.class);
        RagService service = new RagService(vectorStore, new RagProperties(), () -> false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);

        RagService.IngestResult result = service.ingest("a".repeat(2000), Map.of("source", "unit-test"));

        verify(vectorStore).add(captor.capture());
        List<Document> documents = captor.getValue();
        assertThat(documents).hasSize(3);
        assertThat(result.chunkCount()).isEqualTo(3);
        assertThat(documents.get(0).getMetadata())
                .containsEntry("doc_id", result.documentId())
                .containsEntry("chunk_index", 0)
                .containsEntry("chunk_count", 3)
                .containsEntry("source", "unit-test");
        assertThat(documents.get(1).getMetadata()).containsEntry("chunk_index", 1);
    }

    @Test
    void blankIngestTextIsRejected() {
        RagService service = new RagService(mock(VectorStore.class), new RagProperties(), () -> false);

        assertThatThrownBy(() -> service.ingest("  ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
    }
}
