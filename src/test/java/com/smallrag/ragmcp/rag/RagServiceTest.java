package com.smallrag.ragmcp.rag;

import com.smallrag.ragmcp.config.QdrantProperties;
import com.smallrag.ragmcp.config.RagProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * @author zjk
 */
class RagServiceTest {

    private static ChunkingService chunkingService(RagProperties properties) {
        return new ChunkingService(properties, mock(EmbeddingModel.class));
    }

    @Test
    void emptyKnowledgeBaseShortCircuitsWithoutEmbedding() {
        VectorStore vectorStore = mock(VectorStore.class);
        RagProperties properties = new RagProperties();

        RagService service = new RagService(vectorStore, properties, new QdrantProperties(), () -> true, chunkingService(properties), mock(DocumentArchive.class));

        assertThat(service.query("项目支持哪些向量数据库？", 3))
                .isEqualTo(properties.getEmptyContextAnswer());
        verifyNoInteractions(vectorStore);
    }

    @Test
    void noHitsReturnsFriendlyMessage() {
        VectorStore vectorStore = mock(VectorStore.class);
        RagProperties properties = new RagProperties();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        RagService service = new RagService(vectorStore, properties, new QdrantProperties(), () -> false, chunkingService(properties), mock(DocumentArchive.class));

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

        RagService service = new RagService(vectorStore, properties, new QdrantProperties(), () -> false, chunkingService(properties), mock(DocumentArchive.class));

        assertThat(service.query("向量库是什么？", 3))
                .contains("[1]")
                .contains("source=verify")
                .contains("score=0.900")
                .contains("Qdrant 负责向量存储");
    }

    @Test
    void ingestSplitsPureTextIntoOverlappingChunks() {
        VectorStore vectorStore = mock(VectorStore.class);
        RagProperties properties = new RagProperties();
        properties.getChunking().setStrategy(ChunkingStrategy.FIXED);
        RagService service = new RagService(vectorStore, properties, new QdrantProperties(), () -> false, chunkingService(properties), mock(DocumentArchive.class));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);

        RagService.IngestResult result = service.ingest("a".repeat(2000), Map.of("source", "unit-test"));

        verify(vectorStore).add(captor.capture());
        List<Document> documents = captor.getValue();
        assertThat(documents).hasSize(3);
        assertThat(result.chunkCount()).isEqualTo(3);
        assertThat(result.parentCount()).isZero();
        assertThat(documents.get(0).getMetadata())
                .containsEntry("doc_id", result.documentId())
                .containsEntry("chunk_index", 0)
                .containsEntry("chunk_count", 3)
                .containsEntry("chunk_strategy", "fixed")
                .containsEntry("source", "unit-test");
        assertThat(documents.get(1).getMetadata()).containsEntry("chunk_index", 1);
    }

    @Test
    void blankIngestTextIsRejected() {
        RagProperties properties = new RagProperties();
        RagService service = new RagService(mock(VectorStore.class), properties, new QdrantProperties(), () -> false,
                chunkingService(properties), mock(DocumentArchive.class));

        assertThatThrownBy(() -> service.ingest("  ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
    }

    @Test
    void parentChildSearchFiltersChildrenAndExpandsParentText() {
        VectorStore vectorStore = mock(VectorStore.class);
        RagProperties properties = new RagProperties();
        properties.getChunking().setStrategy(ChunkingStrategy.PARENT_CHILD);

        Document child = Document.builder()
                .id(UUID.randomUUID().toString())
                .text("命中的子块")
                .metadata(Map.of(
                        "chunk_type", "child",
                        "parent_text", "父块正文包含完整上下文",
                        "source", "handbook"))
                .score(0.88)
                .build();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(child));

        RagService service = new RagService(vectorStore, properties, new QdrantProperties(), () -> false, chunkingService(properties), mock(DocumentArchive.class));

        ArgumentCaptor<SearchRequest> requestCaptor = ArgumentCaptor.forClass(SearchRequest.class);
        String context = service.query("上下文是什么？", 2);

        verify(vectorStore).similaritySearch(requestCaptor.capture());
        assertThat(requestCaptor.getValue().hasFilterExpression()).isTrue();
        assertThat(context)
                .contains("父块正文包含完整上下文")
                .contains("source=handbook")
                .doesNotContain("命中的子块");
    }

    @Test
    void ingestArchivesOriginalDocumentForAutomaticRechunk() {
        VectorStore vectorStore = mock(VectorStore.class);
        RagProperties properties = new RagProperties();
        DocumentArchive archive = mock(DocumentArchive.class);
        RagService service = new RagService(vectorStore, properties, new QdrantProperties(), () -> false,
                chunkingService(properties), archive);

        RagService.IngestResult result = service.ingest("hello world", Map.of("source", "handbook"));

        verify(archive).save(
                eq(result.documentId()),
                eq("hello world"),
                eq(Map.of("source", "handbook")),
                isNull());
        verify(archive).markIndexed(eq(result.documentId()), anyString());
    }

    @Test
    void ingestRemovesArchiveWhenVectorWriteFails() {
        VectorStore vectorStore = mock(VectorStore.class);
        doThrow(new IllegalStateException("qdrant down")).when(vectorStore).add(anyList());
        RagProperties properties = new RagProperties();
        DocumentArchive archive = mock(DocumentArchive.class);
        RagService service = new RagService(vectorStore, properties, new QdrantProperties(), () -> false,
                chunkingService(properties), archive);

        assertThatThrownBy(() -> service.ingest("hello", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("qdrant down");
        verify(archive).delete(anyString());
    }

    @Test
    void reindexDeletesOldChunksBeforeAddingNewOnes() {
        VectorStore vectorStore = mock(VectorStore.class);
        RagProperties properties = new RagProperties();
        RagService service = new RagService(vectorStore, properties, new QdrantProperties(), () -> false,
                chunkingService(properties), mock(DocumentArchive.class));
        String documentId = UUID.randomUUID().toString();

        service.reindex(documentId, "a".repeat(2000), Map.of());

        var ordered = inOrder(vectorStore);
        ordered.verify(vectorStore).delete(any(Filter.Expression.class));
        ordered.verify(vectorStore).add(anyList());
    }

    @Test
    void updateUsesArchivedMetadataAndReindexes() {
        VectorStore vectorStore = mock(VectorStore.class);
        RagProperties properties = new RagProperties();
        DocumentArchive archive = mock(DocumentArchive.class);
        String documentId = UUID.randomUUID().toString();
        when(archive.isEnabled()).thenReturn(true);
        when(archive.find(documentId)).thenReturn(Optional.of(new ArchivedDocument(
                documentId, "旧文本", Map.of("source", "handbook"), "old-signature", "t1")));
        RagService service = new RagService(vectorStore, properties, new QdrantProperties(), () -> false,
                chunkingService(properties), archive);

        RagService.IngestResult result = service.update(documentId, "新的完整文本", null);

        verify(archive).save(documentId, "新的完整文本", Map.of("source", "handbook"), null);
        verify(vectorStore).delete(any(Filter.Expression.class));
        verify(vectorStore).add(anyList());
        verify(archive).markIndexed(eq(documentId), anyString());
        assertThat(result.chunkCount()).isGreaterThan(0);
    }

    @Test
    void deleteRemovesChunksAndArchive() {
        VectorStore vectorStore = mock(VectorStore.class);
        RagProperties properties = new RagProperties();
        DocumentArchive archive = mock(DocumentArchive.class);
        RagService service = new RagService(vectorStore, properties, new QdrantProperties(), () -> false,
                chunkingService(properties), archive);
        String documentId = UUID.randomUUID().toString();

        service.delete(documentId);

        verify(vectorStore).delete(any(Filter.Expression.class));
        verify(archive).delete(documentId);
    }

    @Test
    void reindexAllRebuildsEveryArchivedDocument() {
        VectorStore vectorStore = mock(VectorStore.class);
        RagProperties properties = new RagProperties();
        DocumentArchive archive = mock(DocumentArchive.class);
        when(archive.isEnabled()).thenReturn(true);

        ArchivedDocument first = new ArchivedDocument(
                UUID.randomUUID().toString(), "第一篇文章", Map.of("source", "a"), "sig", "t1");
        ArchivedDocument second = new ArchivedDocument(
                UUID.randomUUID().toString(), "第二篇文章", Map.of("source", "b"), "sig", "t2");
        when(archive.listAll()).thenReturn(List.of(first, second));

        RagService service = new RagService(vectorStore, properties, new QdrantProperties(), () -> false,
                chunkingService(properties), archive);

        RagService.RebuildResult result = service.reindexAll();

        assertThat(result.total()).isEqualTo(2);
        assertThat(result.succeeded()).isEqualTo(2);
        assertThat(result.failed()).isZero();
        assertThat(result.aborted()).isFalse();
        verify(vectorStore, times(2)).add(anyList());
        verify(archive, times(2)).markIndexed(anyString(), anyString());
    }

    @Test
    void rebuildRefusesEmptyArchive() {
        RagProperties properties = new RagProperties();
        DocumentArchive archive = mock(DocumentArchive.class);
        when(archive.isEnabled()).thenReturn(true);
        when(archive.listAll()).thenReturn(List.of());

        RagService service = new RagService(mock(VectorStore.class), properties, new QdrantProperties(), () -> false,
                chunkingService(properties), archive);

        assertThatThrownBy(service::rebuild)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("拒绝清空");
    }

    @Test
    void listDocumentsPaginates() {
        RagProperties properties = new RagProperties();
        DocumentArchive archive = mock(DocumentArchive.class);
        when(archive.isEnabled()).thenReturn(true);
        String firstId = UUID.randomUUID().toString();
        String secondId = UUID.randomUUID().toString();
        String thirdId = UUID.randomUUID().toString();
        when(archive.listAll()).thenReturn(List.of(
                new ArchivedDocument(firstId, "a", Map.of(), "sig", "t1"),
                new ArchivedDocument(secondId, "bb", Map.of(), "sig", "t2"),
                new ArchivedDocument(thirdId, "ccc", Map.of(), "sig", "t3")));

        RagService service = new RagService(mock(VectorStore.class), properties, new QdrantProperties(), () -> false,
                chunkingService(properties), archive);

        RagService.DocumentPage page = service.listDocuments(2, 1);

        assertThat(page.total()).isEqualTo(3);
        assertThat(page.offset()).isEqualTo(1);
        assertThat(page.limit()).isEqualTo(2);
        assertThat(page.documents()).hasSize(2);
        assertThat(page.documents().get(0).documentId()).isEqualTo(secondId);
        assertThat(page.documents().get(0).textLength()).isEqualTo(2);
    }
}
