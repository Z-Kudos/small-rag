package com.smallrag.ragmcp.rag.vectorstore;

import com.smallrag.ragmcp.config.RagProperties;
import com.smallrag.ragmcp.rag.reindex.ReindexStateStore;
import com.smallrag.ragmcp.rag.reindex.ReindexTaskService;
import com.smallrag.ragmcp.rag.reindex.SqliteReindexStateBackend;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SqliteVectorStore} 的功能测试（临时文件 SQLite + 确定性哈希向量模型）。
 *
 * @author zjk
 */
class SqliteVectorStoreTest {

    @TempDir
    Path tempDir;

    private String dbPath;

    @BeforeEach
    void setUp() {
        dbPath = tempDir.resolve("smallrag.sqlite").toString();
    }

    private RagProperties properties(int dimension) {
        RagProperties properties = new RagProperties();
        properties.getVectorStore().setType("sqlite");
        properties.getVectorStore().getSqlite().setPath(dbPath);
        properties.getVectorStore().getSqlite().setDimension(dimension);
        return properties;
    }

    private static Document document(String docId, String chunkType, String parentId, String text) {
        return Document.builder()
                .id(UUID.randomUUID().toString())
                .text(text)
                .metadata(Map.of(
                        "doc_id", docId,
                        "chunk_type", chunkType,
                        "parent_id", parentId))
                .build();
    }

    @Test
    void addAndSearchReturnsScoredResultsInDescendingOrder() {
        EmbeddingModel model = TestEmbeddingModels.hashing(32);
        SqliteVectorStore store = new SqliteVectorStore(properties(32), model);
        store.ensureReady();
        store.add(List.of(
                document("d1", "child", "p1", "向量数据库负责存储向量并做相似度检索"),
                document("d2", "child", "p2", "完全无关的内容：今天天气晴朗适合出门散步"),
                document("d3", "child", "p3", "向量检索基于余弦相似度计算")));

        List<Document> hits = store.similaritySearch(SearchRequest.builder()
                .query("向量检索原理")
                .topK(10)
                .similarityThreshold(0.0)
                .build());

        assertThat(hits).isNotEmpty();
        for (int i = 1; i < hits.size(); i++) {
            assertThat(hits.get(i - 1).getScore()).isGreaterThanOrEqualTo(hits.get(i).getScore());
        }
        assertThat(hits).allSatisfy(document -> assertThat(document.getScore()).isBetween(-1.0, 1.0));
    }

    @Test
    void searchAppliesSimilarityThreshold() {
        EmbeddingModel model = TestEmbeddingModels.hashing(32);
        SqliteVectorStore store = new SqliteVectorStore(properties(32), model);
        store.ensureReady();
        store.add(List.of(
                document("d1", "child", "p1", "向量数据库负责存储向量"),
                document("d2", "child", "p2", "完全无关的内容：今天天气晴朗适合出门散步")));

        List<Document> noFilter = store.similaritySearch(SearchRequest.builder()
                .query("向量数据库")
                .topK(10)
                .similarityThreshold(0.0)
                .build());
        assertThat(noFilter).hasSize(2);

        List<Document> filtered = store.similaritySearch(SearchRequest.builder()
                .query("向量数据库")
                .topK(10)
                .similarityThreshold(1.0)
                .build());
        assertThat(filtered).isEmpty();
    }

    @Test
    void searchAppliesChunkTypeFilterExpression() {
        EmbeddingModel model = TestEmbeddingModels.hashing(32);
        SqliteVectorStore store = new SqliteVectorStore(properties(32), model);
        store.ensureReady();
        Document child = document("d1", "child", "p1", "向量数据库负责存储向量");
        Document parent = document("d1", "parent", "p1", "向量数据库负责存储向量");
        store.add(List.of(child, parent));

        List<Document> hits = store.similaritySearch(SearchRequest.builder()
                .query("向量数据库")
                .topK(10)
                .similarityThreshold(0.0)
                .filterExpression(new FilterExpressionBuilder().eq("chunk_type", "child").build())
                .build());

        assertThat(hits).isNotEmpty();
        assertThat(hits).allSatisfy(document ->
                assertThat(document.getMetadata().get("chunk_type")).isEqualTo("child"));
    }

    @Test
    void deleteByIdListRemovesDocuments() {
        EmbeddingModel model = TestEmbeddingModels.hashing(32);
        SqliteVectorStore store = new SqliteVectorStore(properties(32), model);
        store.ensureReady();
        Document document = document("d1", "child", "p1", "向量数据库负责存储向量");
        store.add(List.of(document));
        assertThat(store.countChunks()).isEqualTo(1);

        store.delete(List.of(document.getId()));

        assertThat(store.countChunks()).isZero();
    }

    @Test
    void deleteByFilterRemovesByDocId() {
        EmbeddingModel model = TestEmbeddingModels.hashing(32);
        SqliteVectorStore store = new SqliteVectorStore(properties(32), model);
        store.ensureReady();
        store.add(List.of(
                document("keep", "child", "p1", "需要保留的文档"),
                document("drop", "child", "p2", "需要删除的文档"),
                document("drop", "parent", "p2", "需要删除的父块")));

        store.delete(new FilterExpressionBuilder().eq("doc_id", "drop").build());

        assertThat(store.countChunks()).isEqualTo(1);
    }

    @Test
    void embeddingDimensionMismatchFailsFastOnAdd() {
        // 配置期望 32 维，但 Embedding 实际返回 8 维
        EmbeddingModel model = TestEmbeddingModels.hashing(8);
        SqliteVectorStore store = new SqliteVectorStore(properties(32), model);
        store.ensureReady();

        assertThatThrownBy(() -> store.add(List.of(document("d1", "child", "p1", "任意文本"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("维度");
    }

    @Test
    void ensureReadyFailsWhenRecordedDimensionDiffers() {
        EmbeddingModel model = TestEmbeddingModels.hashing(16);
        new SqliteVectorStore(properties(16), model).ensureReady();

        SqliteVectorStore mismatched = new SqliteVectorStore(properties(32), model);

        assertThatThrownBy(mismatched::ensureReady)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("维度");
    }

    @Test
    void sqliteReindexStateBackendRoundTripsPersistedTasks() {
        ReindexTaskService.ReindexTaskView view = new ReindexTaskService.ReindexTaskView(
                "task-1", "rebuild", "RUNNING", 10, 3, 1, 0, 30,
                null, null, false, false, "2026-11-11T00:00:00Z", null, false, null, false, null);
        ReindexStateStore.PersistedTask task =
                new ReindexStateStore.PersistedTask(view, true, Set.of("doc-1"));
        SqliteReindexStateBackend backend =
                new SqliteReindexStateBackend(new tools.jackson.databind.ObjectMapper(), dbPath);

        backend.save(task);

        List<ReindexStateStore.PersistedTask> loaded = backend.loadAll();
        assertThat(loaded).hasSize(1);
        assertThat(loaded.get(0).view().taskId()).isEqualTo("task-1");
        assertThat(loaded.get(0).completedDocumentIds()).containsExactly("doc-1");

        backend.delete("task-1");
        assertThat(backend.loadAll()).isEmpty();
    }

    @Test
    void sqliteDocumentPointsPortFindAndDeleteWork() {
        EmbeddingModel model = TestEmbeddingModels.hashing(32);
        SqliteVectorStore store = new SqliteVectorStore(properties(32), model);
        store.ensureReady();
        store.add(List.of(
                document("d1", "child", "p1", "甲的文档片段"),
                document("d1", "child", "p1", "甲的另一个片段"),
                document("d2", "child", "p2", "乙的文档片段")));
        SqliteDocumentPointsPort port = new SqliteDocumentPointsPort(dbPath);

        Optional<Set<String>> found = port.findPointIds("d1");

        assertThat(found).isPresent();
        assertThat(found.get()).hasSize(2);

        port.deletePoints(found.get());

        assertThat(store.countChunks()).isEqualTo(1);
    }

    @Test
    void sqliteDocumentPointsPortCleansOrphanParents() {
        EmbeddingModel model = TestEmbeddingModels.hashing(32);
        SqliteVectorStore store = new SqliteVectorStore(properties(32), model);
        store.ensureReady();
        // liveParent 有子块；orphanParent 没有
        store.add(List.of(
                Document.builder().id(PointIds.parentPointUuid("live").toString()).text("活父块")
                        .metadata(Map.of("doc_id", "d1", "chunk_type", "parent", "parent_id", "live"))
                        .build(),
                Document.builder().id(PointIds.parentPointUuid("orphan").toString()).text("孤儿父块")
                        .metadata(Map.of("doc_id", "d1", "chunk_type", "parent", "parent_id", "orphan"))
                        .build(),
                Document.builder().id(PointIds.chunkPointUuid("d1", "sig", 0).toString()).text("子块")
                        .metadata(Map.of("doc_id", "d1", "chunk_type", "child", "parent_id", "live"))
                        .build()));
        SqliteDocumentPointsPort port = new SqliteDocumentPointsPort(dbPath);

        CleanupResult result = port.cleanupOrphans();

        assertThat(result.parentPoints()).isEqualTo(2);
        assertThat(result.orphanParents()).isEqualTo(1);
        assertThat(store.countOrphanParents()).isZero();
    }

}
