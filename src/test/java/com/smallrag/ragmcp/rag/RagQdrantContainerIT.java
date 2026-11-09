package com.smallrag.ragmcp.rag;

import com.smallrag.ragmcp.config.QdrantProperties;
import com.smallrag.ragmcp.config.RagProperties;
import com.smallrag.ragmcp.rag.archive.DocumentArchive;
import com.smallrag.ragmcp.rag.chunking.ChunkingService;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.Vector;
import io.qdrant.client.grpc.Points.Vectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 Qdrant 的端到端测试：入库 → 检索、重复 upsert 幂等、孤儿父块清理。
 *
 * <p>默认不参与 {@code mvn test}；运行方式：
 * {@code mvn verify -Pcontainer-it}（需要 Docker / Podman）。
 * 本地镜像可用 {@code -Dsmallrag.qdrant.image=...} 覆盖。
 *
 * @author zjk
 */
class RagQdrantContainerIT {

    private static final String COLLECTION = "small_rag_it";
    private static final int DIMENSION = 32;

    private static GenericContainer<?> qdrant;
    private static QdrantClient client;
    private static QdrantVectorStore vectorStore;
    private static EmbeddingModel embeddingModel;

    @TempDir
    static Path archiveDir;

    private RagService ragService;

    @BeforeAll
    static void startQdrant() throws Exception {
        String image = System.getProperty("smallrag.qdrant.image",
                System.getenv().getOrDefault("SMALL_RAG_QDRANT_IMAGE", "qdrant/qdrant:latest"));
        qdrant = new GenericContainer<>(DockerImageName.parse(image))
                .withExposedPorts(6333, 6334)
                .waitingFor(Wait.forHttp("/readyz").forPort(6333)
                        .withStartupTimeout(java.time.Duration.ofSeconds(120)));
        qdrant.start();

        client = new QdrantClient(QdrantGrpcClient.newBuilder(
                qdrant.getHost(), qdrant.getMappedPort(6334), false).build());
        embeddingModel = new HashEmbeddingModel(DIMENSION);
        vectorStore = QdrantVectorStore.builder(client, embeddingModel)
                .collectionName(COLLECTION)
                .initializeSchema(true)
                .build();
        vectorStore.afterPropertiesSet();
    }

    @AfterAll
    static void stopQdrant() {
        if (client != null) {
            client.close();
        }
        if (qdrant != null) {
            qdrant.stop();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        client.deleteAsync(COLLECTION, io.qdrant.client.grpc.Common.Filter.newBuilder().build())
                .get(30, TimeUnit.SECONDS);
        RagProperties properties = new RagProperties();
        properties.setArchiveDir(archiveDir.toString());
        QdrantProperties qdrantProperties = new QdrantProperties();
        qdrantProperties.setCollectionName(COLLECTION);
        DocumentArchive archive = new DocumentArchive(new ObjectMapper(), properties);
        ragService = new RagService(vectorStore, properties, new QueryCircuitBreaker(properties),
                qdrantProperties, () -> false,
                new ChunkingService(properties, embeddingModel), archive, client, embeddingModel);
    }

    @Test
    void ingestThenQueryReturnsIndexedContent() {
        String text = "Qdrant 是一个向量数据库，负责保存文本片段的向量。"
                + "small-rag 通过 Embedding 模型把查询转换成向量，再在 Qdrant 中做相似度检索。";
        ragService.ingest(text, Map.of("source", "container-it"));

        String answer = ragService.query("Qdrant 向量数据库如何做相似度检索？", 3);

        assertThat(answer)
                .contains("Qdrant")
                .contains("向量");
    }

    @Test
    void repeatedUpsertKeepsPointCountStable() throws Exception {
        String documentId = UUID.randomUUID().toString();
        String text = "# 标题\n\n" + "重复写入不应产生多余片段。".repeat(80);

        long first = upsertAndCount(documentId, text);
        long second = upsertAndCount(documentId, text);
        long third = upsertAndCount(documentId, text);

        assertThat(second).isEqualTo(first);
        assertThat(third).isEqualTo(first);
    }

    @Test
    void cleanupRemovesOrphanParentsAndLegacyPoints() throws Exception {
        String text = "# 标题\n\n" + "孤儿清理测试正文。".repeat(60);
        ragService.ingest(text, Map.of());
        long before = countPoints();

        UUID orphanParent = UUID.randomUUID();
        UUID legacy = UUID.randomUUID();
        client.upsertAsync(COLLECTION, List.of(
                point(orphanParent, Map.of(
                        "chunk_type", "parent",
                        "parent_id", "missing#p0",
                        "doc_id", UUID.randomUUID().toString())),
                point(legacy, Map.of(
                        "doc_id", UUID.randomUUID().toString()))))
                .get(30, TimeUnit.SECONDS);
        assertThat(countPoints()).isEqualTo(before + 2);

        RagService.CleanupResult result = ragService.cleanupOrphans();

        assertThat(result.orphanParents()).isEqualTo(1);
        assertThat(countPoints()).isEqualTo(before);
    }

    private long upsertAndCount(String documentId, String text) throws Exception {
        ragService.upsert(documentId, text, Map.of("source", "container-it"));
        return countPoints();
    }

    private long countPoints() throws Exception {
        return client.countAsync(COLLECTION).get(30, TimeUnit.SECONDS);
    }

    private static PointStruct point(UUID id, Map<String, String> payload) {
        PointStruct.Builder point = PointStruct.newBuilder()
                .setId(PointIdFactory.id(id))
                .setVectors(Vectors.newBuilder()
                        .setVector(Vector.newBuilder().addAllData(vector("orphan")))
                        .build());
        payload.forEach((key, value) -> point.putPayload(key,
                io.qdrant.client.grpc.JsonWithInt.Value.newBuilder().setStringValue(value).build()));
        return point.build();
    }

    private static List<Float> vector(String text) {
        float[] embedding = HashEmbeddingModel.embed(text, DIMENSION);
        List<Float> values = new ArrayList<>(embedding.length);
        for (float value : embedding) {
            values.add(value);
        }
        return values;
    }

    /**
     * 确定性哈希 Embedding：不依赖外部模型，只用于验证真实 Qdrant 的读写链路。
     */
    private static final class HashEmbeddingModel implements EmbeddingModel {

        private final int dimension;

        private HashEmbeddingModel(int dimension) {
            this.dimension = dimension;
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<Embedding> embeddings = new ArrayList<>();
            List<String> inputs = request.getInstructions();
            for (int i = 0; i < inputs.size(); i++) {
                embeddings.add(new Embedding(embed(inputs.get(i), dimension), i));
            }
            return new EmbeddingResponse(embeddings);
        }

        @Override
        public float[] embed(Document document) {
            return embed(document.getText(), dimension);
        }

        @Override
        public float[] embed(String text) {
            return embed(text, dimension);
        }

        @Override
        public int dimensions() {
            return dimension;
        }

        private static float[] embed(String text, int dimension) {
            float[] values = new float[dimension];
            if (text == null) {
                return values;
            }
            for (int i = 0; i < text.length(); i++) {
                values[Math.floorMod(text.charAt(i) * 31 + i, dimension)] += 1f;
            }
            double norm = 0;
            for (float value : values) {
                norm += value * value;
            }
            if (norm > 0) {
                float scale = (float) (1.0 / Math.sqrt(norm));
                for (int i = 0; i < values.length; i++) {
                    values[i] *= scale;
                }
            }
            return values;
        }
    }
}
