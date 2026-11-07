package com.smallrag.ragmcp.rag;

import com.smallrag.ragmcp.config.QdrantProperties;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Common.PointId;
import io.qdrant.client.grpc.JsonWithInt.Value;
import com.google.common.util.concurrent.Futures;
import io.qdrant.client.grpc.Points;
import io.qdrant.client.grpc.Points.RetrievedPoint;
import io.qdrant.client.grpc.Points.VectorsOutput;
import com.smallrag.ragmcp.rag.archive.ArchivedDocument;
import com.smallrag.ragmcp.rag.archive.ArchivedDocumentSummary;
import com.smallrag.ragmcp.rag.archive.DocumentArchive;
import com.smallrag.ragmcp.rag.chunking.ChunkingService;
import com.smallrag.ragmcp.rag.chunking.ChunkingStrategy;
import com.smallrag.ragmcp.config.RagProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
import static org.mockito.Mockito.never;
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

    private static void stubArchive(DocumentArchive archive, ArchivedDocument... documents) {
        when(archive.listSummaries()).thenReturn(List.of(documents).stream()
                .map(ArchivedDocumentSummary::of)
                .toList());
        for (ArchivedDocument document : documents) {
            when(archive.find(document.documentId())).thenReturn(Optional.of(document));
        }
    }

    @Test
    void emptyKnowledgeBaseShortCircuitsWithoutEmbedding() {
        VectorStore vectorStore = mock(VectorStore.class);
        RagProperties properties = new RagProperties();

        RagService service = new RagService(vectorStore, properties, new QdrantProperties(), () -> true, chunkingService(properties), mock(DocumentArchive.class), mock(QdrantClient.class), mock(EmbeddingModel.class));

        assertThat(service.query("项目支持哪些向量数据库？", 3))
                .isEqualTo(properties.getEmptyContextAnswer());
        verifyNoInteractions(vectorStore);
    }

    @Test
    void noHitsReturnsFriendlyMessage() {
        VectorStore vectorStore = mock(VectorStore.class);
        RagProperties properties = new RagProperties();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        RagService service = new RagService(vectorStore, properties, new QdrantProperties(), () -> false, chunkingService(properties), mock(DocumentArchive.class), mock(QdrantClient.class), mock(EmbeddingModel.class));

        assertThat(service.query("任意问题", 3)).isEqualTo(properties.getEmptyContextAnswer());
    }

    @Test
    void hitsReturnNumberedContextWithSourceAndScore() {
        VectorStore vectorStore = mock(VectorStore.class);
        RagProperties properties = new RagProperties();
        Document document = Document.builder()
                .id(UUID.randomUUID().toString())
                .text("Qdrant 负责向量存储")
                .metadata(Map.of("source", "verify", "heading_path", "指南 > 存储"))
                .score(0.9)
                .build();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(document));

        RagService service = new RagService(vectorStore, properties, new QdrantProperties(), () -> false, chunkingService(properties), mock(DocumentArchive.class), mock(QdrantClient.class), mock(EmbeddingModel.class));

        assertThat(service.query("向量库是什么？", 3))
                .contains("[1]")
                .contains("source=verify")
                .contains("heading=指南 > 存储")
                .contains("score=0.900")
                .contains("Qdrant 负责向量存储");
    }

    @Test
    void ingestSplitsPureTextIntoOverlappingChunks() {
        VectorStore vectorStore = mock(VectorStore.class);
        RagProperties properties = new RagProperties();
        properties.getChunking().setStrategy(ChunkingStrategy.FIXED);
        RagService service = new RagService(vectorStore, properties, new QdrantProperties(), () -> false, chunkingService(properties), mock(DocumentArchive.class), mock(QdrantClient.class), mock(EmbeddingModel.class));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);

        RagService.IngestResult result = service.ingest("字".repeat(2000), Map.of("source", "unit-test"));

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
                chunkingService(properties), mock(DocumentArchive.class), mock(QdrantClient.class), mock(EmbeddingModel.class));

        assertThatThrownBy(() -> service.ingest("  ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
    }

    @Test
    void parentChildSearchFiltersChildrenAndExpandsParentText() {
        VectorStore vectorStore = mock(VectorStore.class);
        QdrantClient qdrantClient = mock(QdrantClient.class);
        RagProperties properties = new RagProperties();
        properties.getChunking().setStrategy(ChunkingStrategy.PARENT_CHILD);

        Document child = Document.builder()
                .id(UUID.randomUUID().toString())
                .text("指南 > 安装\n命中的子块")
                .metadata(Map.of(
                        "chunk_type", "child",
                        "parent_id", "doc-1#p0",
                        "heading_path", "指南 > 安装",
                        "source", "handbook"))
                .score(0.88)
                .build();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(child));
        mockParentFetch(qdrantClient, parentPoint("doc-1#p0", "父块正文包含完整上下文", null));

        RagService service = new RagService(vectorStore, properties, new QdrantProperties(), () -> false,
                chunkingService(properties), mock(DocumentArchive.class), qdrantClient, mock(EmbeddingModel.class));

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
    void parentChildSearchDeduplicatesHitsFromSameParent() {
        VectorStore vectorStore = mock(VectorStore.class);
        QdrantClient qdrantClient = mock(QdrantClient.class);
        RagProperties properties = new RagProperties();
        properties.getChunking().setStrategy(ChunkingStrategy.PARENT_CHILD);

        Document first = childHit("doc-1#p0", "子块一", 0.9);
        Document second = childHit("doc-1#p0", "子块二", 0.85);
        Document third = childHit("doc-1#p1", "子块三", 0.7);
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(first, second, third));
        mockParentFetch(qdrantClient,
                parentPoint("doc-1#p0", "第一个父块的正文", null),
                parentPoint("doc-1#p1", "第二个父块的正文", null));

        RagService service = new RagService(vectorStore, properties, new QdrantProperties(), () -> false,
                chunkingService(properties), mock(DocumentArchive.class), qdrantClient, mock(EmbeddingModel.class));

        String context = service.query("问题", 2);

        // 同一父块的两次命中只保留一条，topK=2 返回两个不同父块
        assertThat(context).contains("第一个父块的正文").contains("第二个父块的正文");
        assertThat(context.indexOf("第一个父块的正文")).isEqualTo(context.lastIndexOf("第一个父块的正文"));
    }

    @Test
    void contextTruncatesAroundMatchedChildInsteadOfHead() {
        VectorStore vectorStore = mock(VectorStore.class);
        QdrantClient qdrantClient = mock(QdrantClient.class);
        RagProperties properties = new RagProperties();
        properties.getChunking().setStrategy(ChunkingStrategy.PARENT_CHILD);
        properties.setMaxChunkChars(50);

        String parentText = "前导内容。".repeat(20) + "命中的关键内容。" + "后续内容。".repeat(20);
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(childHit("doc-1#p0", "命中的关键内容。", 0.9)));
        mockParentFetch(qdrantClient, parentPoint("doc-1#p0", parentText, null));

        RagService service = new RagService(vectorStore, properties, new QdrantProperties(), () -> false,
                chunkingService(properties), mock(DocumentArchive.class), qdrantClient, mock(EmbeddingModel.class));

        String context = service.query("问题", 1);

        // 窗口截断以命中子块为中心，命中内容不被截掉
        assertThat(context).contains("命中的关键内容").contains("…");
    }

    @Test
    void parentChildIngestStoresParentsAsIndependentPoints() {
        VectorStore vectorStore = mock(VectorStore.class);
        RagProperties properties = new RagProperties();

        RagService service = new RagService(vectorStore, properties, new QdrantProperties(), () -> false,
                chunkingService(properties), mock(DocumentArchive.class), mock(QdrantClient.class),
                mock(EmbeddingModel.class));

        RagService.IngestResult result =
                service.ingest("# 章节\n\n" + "内容段落。".repeat(60), Map.of("source", "handbook"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());
        List<Document> documents = captor.getValue();

        List<Document> parents = documents.stream()
                .filter(d -> "parent".equals(d.getMetadata().get("chunk_type")))
                .toList();
        List<Document> children = documents.stream()
                .filter(d -> "child".equals(d.getMetadata().get("chunk_type")))
                .toList();

        assertThat(result.parentCount()).isGreaterThan(0);
        assertThat(parents).hasSize(result.parentCount());
        assertThat(children).isNotEmpty();

        // 子块不再复制 parent_text；父块 id 由 parent_id 确定性生成，重切后稳定
        assertThat(children).allSatisfy(d ->
                assertThat(d.getMetadata()).doesNotContainKey("parent_text"));
        assertThat(parents).allSatisfy(d -> {
            String parentId = String.valueOf(d.getMetadata().get("parent_id"));
            assertThat(d.getId()).isEqualTo(RagService.parentPointUuid(parentId).toString());
            assertThat(d.getText()).isNotBlank();
        });
        assertThat(result.chunkCount()).isEqualTo(children.size());
    }

    @Test
    void mmrSelectsDiverseParentsOverNearDuplicates() {
        VectorStore vectorStore = mock(VectorStore.class);
        QdrantClient qdrantClient = mock(QdrantClient.class);
        RagProperties properties = new RagProperties();
        properties.getChunking().setStrategy(ChunkingStrategy.PARENT_CHILD);
        properties.setMmrLambda(0.5);

        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                childHit("doc-1#p0", "子块甲", 0.95),
                childHit("doc-1#p1", "子块乙", 0.94),
                childHit("doc-1#p2", "子块丙", 0.90)));
        mockParentFetch(qdrantClient,
                parentPoint("doc-1#p0", "父块甲的正文", List.of(1f, 0f)),
                parentPoint("doc-1#p1", "父块乙的正文", List.of(0.8f, 0.6f)),
                parentPoint("doc-1#p2", "父块丙的正文", List.of(0f, 1f)));

        RagService service = new RagService(vectorStore, properties, new QdrantProperties(), () -> false,
                chunkingService(properties), mock(DocumentArchive.class), qdrantClient, mock(EmbeddingModel.class));

        List<Document> results = service.search("问题", 2);

        // λ=0.5：甲先选；乙与甲 cos=0.8 → 0.47-0.4=0.07；丙与甲 cos=0 → 0.45 → 选丙
        assertThat(results).extracting(Document::getText)
                .containsExactly("父块甲的正文", "父块丙的正文");
    }

    @Test
    void mmrAppliesToPlainStrategiesViaEmbedding() {
        VectorStore vectorStore = mock(VectorStore.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        RagProperties properties = new RagProperties();
        properties.getChunking().setStrategy(ChunkingStrategy.FIXED);
        properties.setMmrLambda(0.5);

        Document a = plainHit("内容甲", 0.95);
        Document b = plainHit("内容乙", 0.94);
        Document c = plainHit("内容丙", 0.90);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(a, b, c));
        when(embeddingModel.embed(anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            List<float[]> vectors = new ArrayList<>(texts.size());
            for (String text : texts) {
                if (text.contains("甲")) {
                    vectors.add(new float[] { 1f, 0f });
                } else if (text.contains("乙")) {
                    vectors.add(new float[] { 0.8f, 0.6f });
                } else {
                    vectors.add(new float[] { 0f, 1f });
                }
            }
            return vectors;
        });

        RagService service = new RagService(vectorStore, properties, new QdrantProperties(), () -> false,
                chunkingService(properties), mock(DocumentArchive.class), mock(QdrantClient.class), embeddingModel);

        List<Document> results = service.search("问题", 2);

        assertThat(results).extracting(Document::getText).containsExactly("内容甲", "内容丙");
    }

    @Test
    void parentFetchFailureFallsBackToChildHits() {
        VectorStore vectorStore = mock(VectorStore.class);
        QdrantClient qdrantClient = mock(QdrantClient.class);
        RagProperties properties = new RagProperties();
        properties.getChunking().setStrategy(ChunkingStrategy.PARENT_CHILD);

        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(childHit("doc-1#p0", "命中的子块", 0.9)));
        when(qdrantClient.retrieveAsync(anyString(), anyList(), eq(true), eq(true), isNull()))
                .thenReturn(Futures.immediateFailedFuture(new IllegalStateException("qdrant down")));

        RagService service = new RagService(vectorStore, properties, new QdrantProperties(), () -> false,
                chunkingService(properties), mock(DocumentArchive.class), qdrantClient, mock(EmbeddingModel.class));

        String context = service.query("问题", 2);

        // 父块取回失败不中断检索：回退为子块原文
        assertThat(context).contains("命中的子块");
    }

    private static Document childHit(String parentId, String text, double score) {
        return Document.builder()
                .id(UUID.randomUUID().toString())
                .text(text)
                .metadata(Map.of("chunk_type", "child", "parent_id", parentId))
                .score(score)
                .build();
    }

    private static Document plainHit(String text, double score) {
        return Document.builder()
                .id(UUID.randomUUID().toString())
                .text(text)
                .metadata(Map.of("chunk_type", "chunk"))
                .score(score)
                .build();
    }

    private static RetrievedPoint parentPoint(String parentId, String text, List<Float> vector) {
        Points.RetrievedPoint.Builder builder = Points.RetrievedPoint.newBuilder()
                .setId(PointIdFactory.id(RagService.parentPointUuid(parentId)));
        if (text != null) {
            builder.putPayload(RagService.CONTENT_FIELD,
                    Value.newBuilder().setStringValue(text).build());
        }
        if (vector != null) {
            builder.setVectors(VectorsOutput.newBuilder()
                    .setVector(io.qdrant.client.grpc.Points.VectorOutput.newBuilder()
                            .addAllData(vector)));
        }
        return builder.build();
    }

    private static void mockParentFetch(QdrantClient qdrantClient, RetrievedPoint... points) {
        when(qdrantClient.retrieveAsync(anyString(), anyList(), eq(true), eq(true), isNull()))
                .thenReturn(Futures.immediateFuture(List.of(points)));
    }

    @Test
    void ingestArchivesOriginalDocumentForAutomaticRechunk() {
        VectorStore vectorStore = mock(VectorStore.class);
        RagProperties properties = new RagProperties();
        DocumentArchive archive = mock(DocumentArchive.class);
        RagService service = new RagService(vectorStore, properties, new QdrantProperties(), () -> false,
                chunkingService(properties), archive, mock(QdrantClient.class), mock(EmbeddingModel.class));

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
                chunkingService(properties), archive, mock(QdrantClient.class), mock(EmbeddingModel.class));

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
                chunkingService(properties), mock(DocumentArchive.class), mock(QdrantClient.class), mock(EmbeddingModel.class));
        String documentId = UUID.randomUUID().toString();

        service.reindex(documentId, "a".repeat(2000), Map.of());

        var ordered = inOrder(vectorStore);
        ordered.verify(vectorStore).delete(any(Filter.Expression.class));
        ordered.verify(vectorStore).add(anyList());
    }

    @Test
    void reindexWritesNewChunksBeforeDeletingOldOnesWhenIdsAreKnown() throws Exception {
        VectorStore vectorStore = mock(VectorStore.class);
        QdrantClient qdrantClient = mock(QdrantClient.class);
        RagProperties properties = new RagProperties();
        QdrantProperties qdrantProperties = new QdrantProperties();
        UUID oldPointId = UUID.randomUUID();
        when(qdrantClient.scrollAsync(any(Points.ScrollPoints.class)))
                .thenReturn(Futures.immediateFuture(Points.ScrollResponse.newBuilder()
                        .addResult(RetrievedPoint.newBuilder()
                                .setId(PointIdFactory.id(oldPointId))
                                .build())
                        .build()));
        when(qdrantClient.deleteAsync(anyString(), anyList()))
                .thenReturn(Futures.immediateFuture(Points.UpdateResult.getDefaultInstance()));
        RagService service = new RagService(vectorStore, properties, qdrantProperties, () -> false,
                chunkingService(properties), mock(DocumentArchive.class), qdrantClient,
                mock(EmbeddingModel.class));
        String documentId = UUID.randomUUID().toString();

        service.reindex(documentId, "a".repeat(2000), Map.of());

        var ordered = inOrder(vectorStore, qdrantClient);
        ordered.verify(vectorStore).add(anyList());
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<List<PointId>> deleted = ArgumentCaptor.forClass((Class) List.class);
        ordered.verify(qdrantClient).deleteAsync(
                eq(qdrantProperties.getCollectionName()), deleted.capture());
        assertThat(deleted.getValue())
                .extracting(PointId::getUuid)
                .containsExactly(oldPointId.toString());
        verify(vectorStore, never()).delete(any(Filter.Expression.class));
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
                chunkingService(properties), archive, mock(QdrantClient.class), mock(EmbeddingModel.class));

        RagService.IngestResult result = service.update(documentId, "新的完整文本", null);

        verify(archive).save(documentId, "新的完整文本", Map.of("source", "handbook"), null);
        verify(vectorStore).delete(any(Filter.Expression.class));
        verify(vectorStore).add(anyList());
        verify(archive).markIndexed(eq(documentId), anyString());
        assertThat(result.chunkCount()).isGreaterThan(0);
    }

    @Test
    void upsertCreatesNewDocumentWithGivenId() {
        VectorStore vectorStore = mock(VectorStore.class);
        RagProperties properties = new RagProperties();
        DocumentArchive archive = mock(DocumentArchive.class);
        when(archive.isEnabled()).thenReturn(true);
        String documentId = UUID.randomUUID().toString();
        RagService service = new RagService(vectorStore, properties, new QdrantProperties(), () -> false,
                chunkingService(properties), archive, mock(QdrantClient.class), mock(EmbeddingModel.class));

        RagService.IngestResult result = service.upsert(
                documentId, "目录导入的完整文本", Map.of("source", "notes/a.md"));

        verify(archive).save(documentId, "目录导入的完整文本", Map.of("source", "notes/a.md"), null);
        verify(vectorStore).delete(any(Filter.Expression.class));
        verify(vectorStore).add(anyList());
        verify(archive).markIndexed(eq(documentId), anyString());
        assertThat(result.documentId()).isEqualTo(documentId);
        assertThat(result.chunkCount()).isGreaterThan(0);
    }

    @Test
    void upsertKeepsArchivedMetadataWhenNull() {
        VectorStore vectorStore = mock(VectorStore.class);
        RagProperties properties = new RagProperties();
        DocumentArchive archive = mock(DocumentArchive.class);
        when(archive.isEnabled()).thenReturn(true);
        String documentId = UUID.randomUUID().toString();
        when(archive.find(documentId)).thenReturn(Optional.of(new ArchivedDocument(
                documentId, "旧文本", Map.of("source", "handbook"), "old-signature", "t1")));
        RagService service = new RagService(vectorStore, properties, new QdrantProperties(), () -> false,
                chunkingService(properties), archive, mock(QdrantClient.class), mock(EmbeddingModel.class));

        service.upsert(documentId, "新的完整文本", null);

        verify(archive).save(documentId, "新的完整文本", Map.of("source", "handbook"), null);
    }

    @Test
    void deleteRemovesChunksAndArchive() {
        VectorStore vectorStore = mock(VectorStore.class);
        RagProperties properties = new RagProperties();
        DocumentArchive archive = mock(DocumentArchive.class);
        RagService service = new RagService(vectorStore, properties, new QdrantProperties(), () -> false,
                chunkingService(properties), archive, mock(QdrantClient.class), mock(EmbeddingModel.class));
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
        stubArchive(archive, first, second);

        RagService service = new RagService(vectorStore, properties, new QdrantProperties(), () -> false,
                chunkingService(properties), archive, mock(QdrantClient.class), mock(EmbeddingModel.class));

        RagService.RebuildResult result = service.reindexAll();

        assertThat(result.total()).isEqualTo(2);
        assertThat(result.succeeded()).isEqualTo(2);
        assertThat(result.failed()).isZero();
        assertThat(result.aborted()).isFalse();
        verify(vectorStore, times(2)).add(anyList());
        verify(archive, times(2)).markIndexed(anyString(), anyString());
    }

    @Test
    void rebuildReportsProgressEvents() {
        VectorStore vectorStore = mock(VectorStore.class);
        RagProperties properties = new RagProperties();
        DocumentArchive archive = mock(DocumentArchive.class);
        when(archive.isEnabled()).thenReturn(true);

        ArchivedDocument first = new ArchivedDocument(
                UUID.randomUUID().toString(), "第一篇文章", Map.of("source", "a"), "sig", "t1");
        ArchivedDocument second = new ArchivedDocument(
                UUID.randomUUID().toString(), "第二篇文章", Map.of("source", "b"), "sig", "t2");
        stubArchive(archive, first, second);

        RagService service = new RagService(vectorStore, properties, new QdrantProperties(), () -> false,
                chunkingService(properties), archive, mock(QdrantClient.class), mock(EmbeddingModel.class));
        List<ReindexProgress> events = new ArrayList<>();

        RagService.RebuildResult result = service.reindexArchived(true, events::add);

        assertThat(result.succeeded()).isEqualTo(2);
        assertThat(events.getFirst().stage()).isEqualTo(ReindexProgress.Stage.CLEARING);
        assertThat(events.getLast().stage()).isEqualTo(ReindexProgress.Stage.COMPLETED);
        assertThat(events.getLast().processed()).isEqualTo(2);
        assertThat(events).anyMatch(progress -> progress.stage() == ReindexProgress.Stage.REINDEXING
                && progress.total() == 2
                && first.documentId().equals(progress.currentDocumentId()));
        assertThat(events).anyMatch(progress -> progress.stage() == ReindexProgress.Stage.REINDEXING
                && progress.processed() == 2
                && progress.succeeded() == 2);
    }

    @Test
    void rebuildStopsWhenCancelled() {
        VectorStore vectorStore = mock(VectorStore.class);
        RagProperties properties = new RagProperties();
        DocumentArchive archive = mock(DocumentArchive.class);
        when(archive.isEnabled()).thenReturn(true);

        ArchivedDocument first = new ArchivedDocument(
                UUID.randomUUID().toString(), "第一篇文章", Map.of("source", "a"), "sig", "t1");
        ArchivedDocument second = new ArchivedDocument(
                UUID.randomUUID().toString(), "第二篇文章", Map.of("source", "b"), "sig", "t2");
        stubArchive(archive, first, second);

        RagService service = new RagService(vectorStore, properties, new QdrantProperties(), () -> false,
                chunkingService(properties), archive, mock(QdrantClient.class), mock(EmbeddingModel.class));
        List<ReindexProgress> events = new ArrayList<>();
        boolean[] cancelled = {false};
        ReindexListener listener = new ReindexListener() {
            @Override
            public void onProgress(ReindexProgress progress) {
                events.add(progress);
                if (progress.stage() == ReindexProgress.Stage.REINDEXING && progress.processed() >= 1) {
                    cancelled[0] = true;
                }
            }

            @Override
            public boolean isCancelled() {
                return cancelled[0];
            }
        };

        RagService.RebuildResult result = service.reindexArchived(false, listener);

        assertThat(result.succeeded()).isEqualTo(1);
        assertThat(events.getLast().stage()).isEqualTo(ReindexProgress.Stage.CANCELLED);
        assertThat(events.getLast().processed()).isEqualTo(1);
        verify(vectorStore, times(1)).add(anyList());
    }

    @Test
    void rebuildStopsWritingWhenRevoked() {
        VectorStore vectorStore = mock(VectorStore.class);
        RagProperties properties = new RagProperties();
        DocumentArchive archive = mock(DocumentArchive.class);
        when(archive.isEnabled()).thenReturn(true);

        ArchivedDocument first = new ArchivedDocument(
                UUID.randomUUID().toString(), "第一篇文章", Map.of("source", "a"), "sig", "t1");
        ArchivedDocument second = new ArchivedDocument(
                UUID.randomUUID().toString(), "第二篇文章", Map.of("source", "b"), "sig", "t2");
        stubArchive(archive, first, second);

        RagService service = new RagService(vectorStore, properties, new QdrantProperties(), () -> false,
                chunkingService(properties), archive, mock(QdrantClient.class), mock(EmbeddingModel.class));
        boolean[] revoked = {false};
        ReindexListener listener = new ReindexListener() {
            @Override
            public void onProgress(ReindexProgress progress) {
                if (progress.stage() == ReindexProgress.Stage.REINDEXING && progress.processed() >= 1) {
                    revoked[0] = true;
                }
            }

            @Override
            public boolean isWritable() {
                return !revoked[0];
            }
        };

        assertThatThrownBy(() -> service.reindexArchived(false, listener))
                .isInstanceOf(ReindexRevokedException.class);

        // 第一篇已写入；撤销后第二篇不得再触发 add
        verify(vectorStore, times(1)).add(anyList());
        verify(vectorStore, times(1)).delete(any(Filter.Expression.class));
    }

    @Test
    void rebuildSkipsAlreadyCompletedDocuments() {
        VectorStore vectorStore = mock(VectorStore.class);
        RagProperties properties = new RagProperties();
        DocumentArchive archive = mock(DocumentArchive.class);
        when(archive.isEnabled()).thenReturn(true);

        ArchivedDocument first = new ArchivedDocument(
                UUID.randomUUID().toString(), "第一篇文章", Map.of("source", "a"), "sig", "t1");
        ArchivedDocument second = new ArchivedDocument(
                UUID.randomUUID().toString(), "第二篇文章", Map.of("source", "b"), "sig", "t2");
        stubArchive(archive, first, second);

        RagService service = new RagService(vectorStore, properties, new QdrantProperties(), () -> false,
                chunkingService(properties), archive, mock(QdrantClient.class), mock(EmbeddingModel.class));
        List<String> finished = new ArrayList<>();
        List<ReindexProgress> events = new ArrayList<>();
        ReindexListener listener = new ReindexListener() {
            @Override
            public void onProgress(ReindexProgress progress) {
                events.add(progress);
            }

            @Override
            public void onDocumentFinished(String documentId, boolean success) {
                finished.add(documentId);
            }
        };

        RagService.RebuildResult result = service.reindexArchived(
                false, listener, Set.of(first.documentId()));

        // 跳过的文档计入成功，但不再写入、不再触发完成回调
        assertThat(result.succeeded()).isEqualTo(2);
        assertThat(finished).containsExactly(second.documentId());
        assertThat(events.getLast().stage()).isEqualTo(ReindexProgress.Stage.COMPLETED);
        assertThat(events.getLast().processed()).isEqualTo(2);
        verify(vectorStore, times(1)).add(anyList());
    }

    @Test
    void rebuildRefusesEmptyArchive() {
        RagProperties properties = new RagProperties();
        DocumentArchive archive = mock(DocumentArchive.class);
        when(archive.isEnabled()).thenReturn(true);
        when(archive.listSummaries()).thenReturn(List.of());

        RagService service = new RagService(mock(VectorStore.class), properties, new QdrantProperties(), () -> false,
                chunkingService(properties), archive, mock(QdrantClient.class), mock(EmbeddingModel.class));

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
        when(archive.listSummaries()).thenReturn(List.of(
                ArchivedDocumentSummary.of(new ArchivedDocument(
                        firstId, "a", Map.of(), "sig", "t1")),
                ArchivedDocumentSummary.of(new ArchivedDocument(
                        secondId, "bb", Map.of(), "sig", "t2")),
                ArchivedDocumentSummary.of(new ArchivedDocument(
                        thirdId, "ccc", Map.of(), "sig", "t3"))));

        RagService service = new RagService(mock(VectorStore.class), properties, new QdrantProperties(), () -> false,
                chunkingService(properties), archive, mock(QdrantClient.class), mock(EmbeddingModel.class));

        RagService.DocumentPage page = service.listDocuments(2, 1);

        assertThat(page.total()).isEqualTo(3);
        assertThat(page.offset()).isEqualTo(1);
        assertThat(page.limit()).isEqualTo(2);
        assertThat(page.documents()).hasSize(2);
        assertThat(page.documents().get(0).documentId()).isEqualTo(secondId);
        assertThat(page.documents().get(0).textLength()).isEqualTo(2);
    }
}
