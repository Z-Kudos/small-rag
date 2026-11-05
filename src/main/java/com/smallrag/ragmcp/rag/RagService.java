package com.smallrag.ragmcp.rag;

import com.smallrag.ragmcp.config.QdrantProperties;
import com.smallrag.ragmcp.config.RagProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * RAG 检索服务：分块入库 + 向量检索 + 上下文拼装。
 *
 * <p>本项目只使用向量（Embedding）模型，不依赖 Chat / 生成模型。
 * {@code rag_query} 返回的是检索增强后的上下文，由调用方 Agent 自行生成回答。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RagService {

    private final VectorStore vectorStore;
    private final RagProperties properties;
    private final QdrantProperties qdrantProperties;
    private final KnowledgeBaseProbe knowledgeBaseProbe;
    private final ChunkingService chunkingService;
    private final DocumentArchive documentArchive;

    /**
     * 检索知识库并返回增强上下文（不调用生成模型）。
     *
     * @param query 自然语言查询
     * @param topK  检索片段数量，null 或非正数时使用默认值
     * @return 带编号、来源与相似度的上下文；空知识库/无命中时返回固定提示
     */
    public String query(String query, Integer topK) {
        if (query == null || query.isBlank()) {
            return "查询内容不能为空。";
        }

        List<Document> documents;
        try {
            documents = search(query, topK);
        } catch (Exception e) {
            log.warn("知识库检索失败: query={}, 原因={}", query, e.toString());
            return "知识库检索失败，请确认 Qdrant 与 Embedding 模型可用：" + rootMessage(e);
        }

        if (documents.isEmpty()) {
            log.debug("知识库未命中，query={}", query);
            return properties.getEmptyContextAnswer();
        }
        return buildContext(documents);
    }

    /**
     * 仅做向量检索，返回命中分块（供 rag_search 使用）。
     *
     * @param query 自然语言查询
     * @param topK  返回片段数，null 或非正数时使用默认值
     * @return 按相似度降序排列的分块
     */
    public List<Document> search(String query, Integer topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        // QdrantVectorStore 会先调用 Embedding 再查库；空库先短路，避免无意义的模型调用
        if (knowledgeBaseProbe.isEmpty()) {
            return List.of();
        }

        SearchRequest.Builder request = SearchRequest.builder()
                .query(query)
                .topK(resolveTopK(topK))
                .similarityThreshold(properties.getMinScore());

        // Parent-Child 只把子块写入向量库；过滤条件防止混入其它类型的记录
        if (chunkingService.strategy() == ChunkingStrategy.PARENT_CHILD) {
            request.filterExpression(new FilterExpressionBuilder()
                    .eq(ChunkingService.FIELD_CHUNK_TYPE, ChunkingService.CHUNK_TYPE_CHILD)
                    .build());
        }

        List<Document> documents = vectorStore.similaritySearch(request.build());
        return expandParents(documents);
    }

    /**
     * 按配置的切分策略把纯文本切块后写入 Qdrant，并归档原始文档。
     *
     * @param text     文本内容
     * @param metadata 业务元数据，可为 null
     * @return 文档 id、写入片段数与父块数
     */
    public IngestResult ingest(String text, Map<String, String> metadata) {
        requireText(text);

        String documentId = UUID.randomUUID().toString();
        String signature = ChunkingSignature.of(properties, qdrantProperties.getCollectionName());

        // 先归档原文（签名先留空），入库失败则回滚；成功后再标记签名。
        // 若进程在“归档后、入库前”崩溃，下次启动会因为签名为空而自动补齐索引。
        documentArchive.save(documentId, text, metadata, null);
        try {
            IngestResult result = index(documentId, text, metadata);
            try {
                documentArchive.markIndexed(documentId, signature);
            } catch (RuntimeException e) {
                log.warn("归档签名更新失败，将在下次启动重新切分: docId={}, 原因={}",
                        documentId, e.toString());
            }
            return result;
        } catch (RuntimeException e) {
            documentArchive.delete(documentId);
            throw e;
        }
    }

    /**
     * 使用当前切分配置重建单篇文档的向量。
     *
     * <p>先完成切分（语义策略可能包含 Embedding 调用），再删除旧片段，
     * 尽量缩短文档在重切过程中的不可检索窗口。
     *
     * @param documentId 原文档 id
     * @param text       原始全文
     * @param metadata   入库时的业务元数据
     * @return 重切后的片段数
     */
    public IngestResult reindex(String documentId, String text, Map<String, String> metadata) {
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("documentId 不能为空");
        }
        requireText(text);

        ChunkingService.ChunkingResult chunked = chunkingService.chunk(text, documentId);
        List<Document> documents = buildDocuments(documentId, metadata, chunked);
        vectorStore.delete(new FilterExpressionBuilder().eq("doc_id", documentId).build());
        vectorStore.add(documents);
        log.info("文档已重切: docId={}, strategy={}, chunks={}",
                documentId, chunkingService.strategy(), documents.size());
        return new IngestResult(documentId, documents.size(), chunked.parentCount());
    }

    private IngestResult index(String documentId, String text, Map<String, String> metadata) {
        ChunkingService.ChunkingResult chunked = chunkingService.chunk(text, documentId);
        List<Document> documents = buildDocuments(documentId, metadata, chunked);
        vectorStore.add(documents);
        log.info("文档已入库: docId={}, strategy={}, chunks={}, metadata={}",
                documentId, chunkingService.strategy(), documents.size(), metadata);
        return new IngestResult(documentId, documents.size(), chunked.parentCount());
    }

    private List<Document> buildDocuments(String documentId,
            Map<String, String> metadata,
            ChunkingService.ChunkingResult chunked) {
        List<TextChunk> chunks = chunked.chunks();
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("text 不能为空");
        }
        String ingestedAt = Instant.now().toString();

        List<Document> documents = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            TextChunk chunk = chunks.get(i);
            Map<String, Object> documentMetadata = new LinkedHashMap<>();
            if (metadata != null) {
                documentMetadata.putAll(metadata);
            }
            documentMetadata.putAll(chunk.metadata());
            documentMetadata.put("doc_id", documentId);
            documentMetadata.put("chunk_index", i);
            documentMetadata.put("chunk_count", chunks.size());
            documentMetadata.put("chunk_strategy", ChunkingService.strategyName(chunkingService.strategy()));
            documentMetadata.put("ingested_at", ingestedAt);
            documents.add(new Document(chunk.text(), documentMetadata));
        }
        return documents;
    }

    private static void requireText(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text 不能为空");
        }
    }

    private String buildContext(List<Document> documents) {
        int maxContextChars = Math.max(1, properties.getMaxContextChars());
        int maxChunkChars = Math.max(1, properties.getMaxChunkChars());

        StringBuilder context = new StringBuilder();
        int index = 1;
        for (Document document : documents) {
            String text = document.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            if (text.length() > maxChunkChars) {
                text = text.substring(0, maxChunkChars);
            }
            if (context.length() + text.length() > maxContextChars) {
                break;
            }

            context.append('[').append(index++).append(']');
            Object source = document.getMetadata().get("source");
            if (source != null && !String.valueOf(source).isBlank()) {
                context.append(" source=").append(source);
            }
            if (document.getScore() != null) {
                context.append(String.format(Locale.ROOT, " score=%.3f", document.getScore()));
            }
            context.append(' ').append(text).append('\n');
        }
        return context.toString();
    }

    /**
     * Parent-Child 检索结果展开：用父块正文替换命中的子块正文，
     * 子块原文保留在 {@code matched_text} 元数据中，便于排查召回精度。
     */
    private List<Document> expandParents(List<Document> documents) {
        if (documents == null || chunkingService.strategy() != ChunkingStrategy.PARENT_CHILD) {
            return documents;
        }

        List<Document> expanded = new ArrayList<>(documents.size());
        for (Document document : documents) {
            Object parentText = document.getMetadata().get(ChunkingService.FIELD_PARENT_TEXT);
            if (parentText == null || String.valueOf(parentText).isBlank()) {
                expanded.add(document);
                continue;
            }

            Map<String, Object> metadata = new LinkedHashMap<>(document.getMetadata());
            metadata.put("matched_text", document.getText());
            metadata.remove(ChunkingService.FIELD_PARENT_TEXT);
            expanded.add(Document.builder()
                    .id(document.getId())
                    .text(String.valueOf(parentText))
                    .metadata(metadata)
                    .score(document.getScore())
                    .build());
        }
        return expanded;
    }

    private int resolveTopK(Integer topK) {
        int requested = (topK == null || topK <= 0) ? properties.getDefaultTopK() : topK;
        int max = Math.max(1, properties.getMaxTopK());
        return Math.min(requested, max);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return (message == null || message.isBlank()) ? cause.getClass().getSimpleName() : message;
    }

    /**
     * 入库结果。
     *
     * @param documentId 本次文档 id
     * @param chunkCount 实际写入向量库的片段数；Parent-Child 下为子块数
     * @param parentCount 父块数；非 Parent-Child 策略为 0
     */
    public record IngestResult(String documentId, int chunkCount, int parentCount) {
    }
}
