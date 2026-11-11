package com.smallrag.ragmcp.rag.eval;

import com.smallrag.ragmcp.config.QdrantProperties;
import com.smallrag.ragmcp.config.RagProperties;
import com.smallrag.ragmcp.rag.QueryCircuitBreaker;
import com.smallrag.ragmcp.rag.RagService;
import com.smallrag.ragmcp.rag.archive.DocumentArchive;
import com.smallrag.ragmcp.rag.chunking.ChunkingService;
import com.smallrag.ragmcp.rag.chunking.ChunkingStrategy;
import com.smallrag.ragmcp.rag.vectorstore.FakeDocumentPointsPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 离线 RAG 管线评测：真实切分 + 真实检索流程 + 确定性哈希向量模型。
 *
 * <p>不依赖 Qdrant / Embedding 服务：用 {@link SimpleVectorStore} 与
 * 基于 token 哈希的 {@link EmbeddingModel} 跑通“入库 → 切分 → 向量化 →
 * 检索 → 文档级指标”。指标用于回归切分/检索/MMR 行为，不代表真实语义模型的效果；
 * 真实语料评测需另行接入 Embedding 服务。
 *
 * @author zjk
 */
class RagPipelineEvaluationTest {

    private static final List<String> STOPWORDS = List.of(
            "the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "with", "is", "are",
            "be", "by", "as", "it", "this", "that", "these", "those", "which", "how", "does",
            "do", "can", "while", "into", "over", "only", "one", "all", "any", "but", "not",
            "from", "than", "then", "when", "where", "who", "what", "will", "should", "may");

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @TempDir
    Path tempDir;

    @Test
    void retrievesEveryTopicWithPerfectMetrics() {
        EvalContext context = context(1.0d);

        List<Double> recalls = new ArrayList<>();
        List<Double> rrs = new ArrayList<>();
        List<Double> ndcgs = new ArrayList<>();
        for (Map.Entry<String, String> query : context.queries().entrySet()) {
            String expectedDocId = context.docIds().get(query.getValue());
            List<String> ranked = rank(context.service().search(query.getKey(), 3, false));

            recalls.add(EvalMetrics.recallAtK(ranked, Set.of(expectedDocId), 3));
            rrs.add(EvalMetrics.reciprocalRank(ranked, Set.of(expectedDocId), 3));
            ndcgs.add(EvalMetrics.ndcgAtK(ranked, Set.of(expectedDocId), 3));
        }

        assertThat(EvalMetrics.mean(recalls)).isEqualTo(1d);
        assertThat(EvalMetrics.mean(rrs)).isCloseTo(1d, within(1e-9));
        assertThat(EvalMetrics.mean(ndcgs)).isCloseTo(1d, within(1e-9));
    }

    @Test
    void queryReturnsContextFromTheRelevantDocument() {
        EvalContext context = context(1.0d);

        String answer = context.service().query(
                "How do Redis lease locks serialize indexing across replicas?", 3);

        assertThat(answer).contains("Redis").contains("lease");
    }

    @Test
    void mmrKeepsTheRelevantDocumentFirst() {
        EvalContext context = context(0.7d);
        String expectedDocId = context.docIds().get("chunking");

        List<Document> hits = context.service().search(
                "How does parent child chunking expand child chunks?", 3, false);

        assertThat(hits).hasSize(3);
        assertThat(hits.getFirst().getMetadata().get("doc_id")).isEqualTo(expectedDocId);
    }

    private EvalContext context(double mmrLambda) {
        RagProperties properties = new RagProperties();
        properties.setArchiveDir(tempDir.resolve("archive").toString());
        properties.setMinScore(0d);
        properties.setMaxContextChars(20_000);
        properties.setMaxChunkChars(2_000);
        properties.setMmrLambda(mmrLambda);
        properties.setChunkSize(80);
        properties.setChunkOverlap(20);
        properties.getChunking().setStrategy(ChunkingStrategy.RECURSIVE);

        EmbeddingModel embeddingModel = new HashingEmbeddingModel(64);
        SimpleVectorStore vectorStore = SimpleVectorStore.builder(embeddingModel).build();
        ChunkingService chunkingService = new ChunkingService(properties, embeddingModel);
        DocumentArchive archive = new DocumentArchive(objectMapper, properties);
        RagService service = new RagService(vectorStore, properties,
                new QueryCircuitBreaker(properties), new QdrantProperties(), () -> false,
                chunkingService, archive, new FakeDocumentPointsPort(), embeddingModel);

        Map<String, String> docIds = new LinkedHashMap<>();
        for (Map.Entry<String, String> doc : corpus().entrySet()) {
            RagService.IngestResult result = service.ingest(doc.getValue(), Map.of("topic", doc.getKey()));
            docIds.put(doc.getKey(), result.documentId());
        }
        return new EvalContext(service, docIds, queries());
    }

    private static List<String> rank(List<Document> documents) {
        return documents.stream()
                .map(document -> String.valueOf(document.getMetadata().get("doc_id")))
                .toList();
    }

    private static Map<String, String> corpus() {
        Map<String, String> docs = new LinkedHashMap<>();
        docs.put("vector-db", """
                Qdrant is the vector database used by this service. It stores embeddings and performs
                cosine similarity search over HNSW indexes. Collections are created automatically and
                points carry payload metadata for filtering.

                The gRPC endpoint is used for ingestion and retrieval, while the REST endpoint is used
                only for administration and health checks.
                """);
        docs.put("chunking", """
                Parent-child chunking stores a large parent block together with smaller child chunks.
                Retrieval matches child chunks first, then expands to the parent text. Child chunks keep
                a parent_id so the parent body can be fetched in one lookup.

                Recursive chunking and semantic chunking are also available, but parent-child is the
                default strategy.
                """);
        docs.put("mmr", """
                MMR improves retrieval diversity. It balances relevance and diversity with the lambda
                parameter, re-ranking candidate chunks and penalizing chunks that are too similar to
                already selected ones.

                A lambda of one disables MMR and keeps pure relevance ordering.
                """);
        docs.put("locks", """
                Redis lease locks serialize indexing across replicas. A replica acquires the lock with
                SET NX PX and renews it in the background with a Lua script. If renewal fails, the
                replica stops writing immediately.

                Directory scans run only on the elected leader, while manual synchronization can start
                on any replica.
                """);
        return docs;
    }

    private static Map<String, String> queries() {
        Map<String, String> queries = new LinkedHashMap<>();
        queries.put("Which vector database stores embeddings and supports cosine similarity?", "vector-db");
        queries.put("How does parent child chunking expand child chunks?", "chunking");
        queries.put("How does MMR balance relevance and diversity with lambda?", "mmr");
        queries.put("How do Redis lease locks serialize indexing across replicas?", "locks");
        return queries;
    }

    private record EvalContext(RagService service, Map<String, String> docIds,
            Map<String, String> queries) {
    }

    /**
     * 确定性哈希向量：按 token 哈希到固定维度，带符号累加并做 L2 归一化。
     * 仅用于离线回归，不具备真实语义泛化能力。
     */
    private static final class HashingEmbeddingModel implements EmbeddingModel {

        private final int dimensions;

        private HashingEmbeddingModel(int dimensions) {
            this.dimensions = dimensions;
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<String> instructions = request.getInstructions();
            List<Embedding> embeddings = new ArrayList<>(instructions.size());
            for (int i = 0; i < instructions.size(); i++) {
                embeddings.add(new Embedding(embedText(instructions.get(i)), i));
            }
            return new EmbeddingResponse(embeddings);
        }

        @Override
        public float[] embed(Document document) {
            return embedText(document.getText());
        }

        @Override
        public int dimensions() {
            return dimensions;
        }

        private float[] embedText(String text) {
            float[] vector = new float[dimensions];
            if (text == null || text.isBlank()) {
                return vector;
            }
            for (String token : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
                if (token.length() < 2 || STOPWORDS.contains(token)) {
                    continue;
                }
                int hash = token.hashCode();
                int index = Math.floorMod(hash, dimensions);
                float sign = ((hash >>> 1) & 1) == 0 ? 1f : -1f;
                vector[index] += sign;
            }
            double norm = 0d;
            for (float value : vector) {
                norm += value * value;
            }
            if (norm == 0d) {
                return vector;
            }
            float scale = (float) (1d / Math.sqrt(norm));
            for (int i = 0; i < vector.length; i++) {
                vector[i] *= scale;
            }
            return vector;
        }
    }
}
