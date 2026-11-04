package com.octopus.ragmcp.store;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内向量存储。默认实现，无需 Redis / Milvus 即可启动。
 *
 * <p>关键点：知识库为空时直接返回空列表，不会调用 Embedding 模型，
 * 因此即使没有配置真实的模型密钥，{@code rag_query} 也能返回友好提示而不是异常。
 */
public class InMemoryVectorStore implements VectorStore {

    private static final String SIMILARITY = "similarity";

    private final EmbeddingModel embeddingModel;
    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    public InMemoryVectorStore(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @Override
    public void add(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        for (Document document : documents) {
            float[] embedding = embeddingModel.embed(document.getText());
            store.put(document.getId(), new Entry(document, embedding));
        }
    }

    @Override
    public void delete(List<String> idList) {
        if (idList != null) {
            idList.forEach(store::remove);
        }
    }

    @Override
    public void delete(Filter.Expression filterExpression) {
        throw new UnsupportedOperationException("内存向量存储不支持按过滤条件删除，请使用 delete(ids)");
    }

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        if (store.isEmpty() || request == null || request.getQuery() == null || request.getQuery().isBlank()) {
            return List.of();
        }

        float[] queryEmbedding = embeddingModel.embed(request.getQuery());
        List<Scored> scored = new ArrayList<>(store.size());
        for (Entry entry : store.values()) {
            scored.add(new Scored(entry.document(), cosine(queryEmbedding, entry.embedding())));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());

        int limit = Math.min(Math.max(1, request.getTopK()), scored.size());
        List<Document> result = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            Scored item = scored.get(i);
            Map<String, Object> metadata = new LinkedHashMap<>(item.document().getMetadata());
            metadata.put(SIMILARITY, item.score());
            result.add(new Document(item.document().getId(), item.document().getText(), metadata));
        }
        return result;
    }

    private static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0.0;
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private record Entry(Document document, float[] embedding) {
    }

    private record Scored(Document document, double score) {
    }
}
