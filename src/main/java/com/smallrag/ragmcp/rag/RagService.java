package com.smallrag.ragmcp.rag;

import com.smallrag.ragmcp.config.QdrantProperties;
import com.smallrag.ragmcp.config.RagProperties;
import com.smallrag.ragmcp.rag.archive.ArchivedDocument;
import com.smallrag.ragmcp.rag.archive.DocumentArchive;
import com.smallrag.ragmcp.rag.chunking.ChunkingService;
import com.smallrag.ragmcp.rag.chunking.ChunkingSignature;
import com.smallrag.ragmcp.rag.chunking.ChunkingStrategy;
import com.smallrag.ragmcp.rag.chunking.MarkdownChunker;
import com.smallrag.ragmcp.rag.chunking.SemanticTextChunker;
import com.smallrag.ragmcp.rag.chunking.TextChunk;
import com.smallrag.ragmcp.rag.chunking.TextWeights;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Common.PointId;
import io.qdrant.client.grpc.Points.RetrievedPoint;
import io.qdrant.client.grpc.Points.VectorsOutput;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * RAG 检索服务：分块入库 + 向量检索 + 上下文拼装。
 *
 * <p>本项目只使用向量（Embedding）模型，不依赖 Chat / 生成模型。
 * {@code rag_query} 返回的是检索增强后的上下文，由调用方 Agent 自行生成回答。
 *
 * <p>Parent-Child 存储：父块作为独立 point（id 由 parent_id 确定性生成，
 * {@code chunk_type=parent}）与子块同集合写入；子块只携带 {@code parent_id}，
 * 检索命中后按 id 取回父块正文与向量。父块向量同时用于 MMR 多样性选择，
 * 不产生额外 Embedding 调用。
 *
 * @author zjk
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RagService {

    public static final String CONTENT_FIELD = "content";

    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    /**
     * 候选放大倍数：父块去重 / MMR 都需要比 topK 更多的候选，
     * 先取 topK × N 个命中再收敛到 topK。
     */
    private static final int CANDIDATE_MULTIPLIER = 4;

    /**
     * 内部检索候选量的硬上限，与对客户端暴露的 maxTopK 无关。
     */
    private static final int MAX_FETCH_TOPK = 100;

    private final VectorStore vectorStore;
    private final RagProperties properties;
    private final QdrantProperties qdrantProperties;
    private final KnowledgeBaseProbe knowledgeBaseProbe;
    private final ChunkingService chunkingService;
    private final DocumentArchive documentArchive;
    private final QdrantClient qdrantClient;
    private final EmbeddingModel embeddingModel;

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
     * <p>Parent-Child 策略下默认按父块去重后返回父块级结果。
     */
    public List<Document> search(String query, Integer topK) {
        return search(query, topK, true);
    }

    /**
     * 仅做向量检索。
     *
     * <p>启用 MMR（{@code 0 < rag.mmr-lambda < 1}）或 Parent-Child 展开时，
     * 先放大候选再收敛到 topK：Parent-Child 按父块去重后复用父块存储向量做
     * MMR；其余策略对候选文本做一次批量 Embedding 后做 MMR。
     *
     * @param query         自然语言查询
     * @param topK          返回片段数，null 或非正数时使用默认值
     * @param expandParents Parent-Child 策略下是否展开为去重后的父块结果；
     *                      false 时返回原始子块命中
     * @return 按相似度降序排列的分块
     */
    public List<Document> search(String query, Integer topK, boolean expandParents) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        // QdrantVectorStore 会先调用 Embedding 再查库；空库先短路，避免无意义的模型调用
        if (knowledgeBaseProbe.isEmpty()) {
            return List.of();
        }

        boolean parentChild = chunkingService.strategy() == ChunkingStrategy.PARENT_CHILD;
        int requestedTopK = resolveTopK(topK);
        boolean expand = parentChild && expandParents;
        boolean mmr = mmrEnabled();
        int fetchTopK = expand || mmr
                ? Math.min(requestedTopK * CANDIDATE_MULTIPLIER, MAX_FETCH_TOPK)
                : requestedTopK;

        SearchRequest.Builder request = SearchRequest.builder()
                .query(query)
                .topK(fetchTopK)
                .similarityThreshold(properties.getMinScore());

        // Parent-Child 只把子块写入向量库；过滤条件防止混入父块与其它类型的记录
        if (parentChild) {
            request.filterExpression(new FilterExpressionBuilder()
                    .eq(ChunkingService.FIELD_CHUNK_TYPE, ChunkingService.CHUNK_TYPE_CHILD)
                    .build());
        }

        List<Document> documents = vectorStore.similaritySearch(request.build());
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        if (expand) {
            return expandToParents(documents, requestedTopK);
        }
        if (mmr && documents.size() > requestedTopK) {
            return selectByMmrWithEmbedding(documents, requestedTopK);
        }
        return documents.size() > requestedTopK ? documents.subList(0, requestedTopK) : documents;
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

        // 先归档原文（签名先留空），入库失败则回滚；成功后再标记签名。
        // 若进程在“归档后、入库前”崩溃，下次启动会因为签名为空而自动补齐索引。
        documentArchive.save(documentId, text, metadata, null);
        try {
            IngestResult result = index(documentId, text, metadata);
            markIndexedQuietly(documentId);
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
        requireDocumentId(documentId);
        requireText(text);

        ChunkingService.ChunkingResult chunked = chunkingService.chunk(text, documentId);
        List<Document> documents = buildDocuments(documentId, metadata, chunked);
        vectorStore.delete(new FilterExpressionBuilder().eq("doc_id", documentId).build());
        vectorStore.add(documents);
        log.info("文档已重切: docId={}, strategy={}, chunks={}, parents={}",
                documentId, chunkingService.strategy(), chunked.chunks().size(), chunked.parentCount());
        return new IngestResult(documentId, chunked.chunks().size(), chunked.parentCount());
    }

    /**
     * 更新已归档文档：metadata 为 null 时保留原元数据；更新后立即重建向量。
     *
     * @param documentId 文档 id
     * @param text       新的完整文本
     * @param metadata   新元数据；为 null 时沿用原元数据
     * @return 更新后的片段数
     */
    public IngestResult update(String documentId, String text, Map<String, String> metadata) {
        requireDocumentId(documentId);
        requireText(text);
        requireArchiveEnabled();

        ArchivedDocument existing = documentArchive.find(documentId)
                .orElseThrow(() -> new IllegalArgumentException("归档文档不存在: " + documentId));
        Map<String, String> effectiveMetadata = metadata != null ? metadata : existing.metadata();

        // 先写归档（签名置空），重切失败时下次启动会按新原文重试
        documentArchive.save(documentId, text, effectiveMetadata, null);
        IngestResult result = reindex(documentId, text, effectiveMetadata);
        markIndexedQuietly(documentId);
        log.info("文档已更新: docId={}, chunks={}", documentId, result.chunkCount());
        return result;
    }

    /**
     * 删除文档的全部向量与原文归档。
     */
    public void delete(String documentId) {
        requireDocumentId(documentId);
        vectorStore.delete(new FilterExpressionBuilder().eq("doc_id", documentId).build());
        documentArchive.delete(documentId);
        log.info("文档已删除: docId={}", documentId);
    }

    /**
     * 手动重建单篇已归档文档的向量。
     */
    public IngestResult reindexDocument(String documentId) {
        requireDocumentId(documentId);
        requireArchiveEnabled();
        ArchivedDocument document = documentArchive.find(documentId)
                .orElseThrow(() -> new IllegalArgumentException("归档文档不存在: " + documentId));

        IngestResult result = reindex(documentId, document.text(), document.metadata());
        markIndexedQuietly(documentId);
        return result;
    }

    /**
     * 手动重建全部已归档文档的向量；不会清理归档之外的残留片段。
     */
    public RebuildResult reindexAll() {
        requireArchiveEnabled();
        return reindexArchived(false);
    }

    /**
     * 全量重建：先清空当前集合中由 small-rag 写入的片段，再按归档重建全部文档。
     *
     * <p>没有归档文档时拒绝执行，避免误清空知识库。
     */
    public RebuildResult rebuild() {
        requireArchiveEnabled();
        return reindexArchived(true);
    }

    /**
     * 分页列出已归档文档，供管理端查看。
     */
    public DocumentPage listDocuments(Integer limit, Integer offset) {
        requireArchiveEnabled();
        int safeLimit = (limit == null || limit <= 0) ? 20 : Math.min(limit, 100);
        int safeOffset = (offset == null || offset < 0) ? 0 : offset;

        List<ArchivedDocument> all = documentArchive.listAll();
        List<DocumentSummary> documents = all.stream()
                .skip(safeOffset)
                .limit(safeLimit)
                .map(document -> new DocumentSummary(
                        document.documentId(),
                        document.metadata(),
                        document.chunkSignature(),
                        document.updatedAt(),
                        document.text() == null ? 0 : document.text().length()))
                .toList();
        return new DocumentPage(all.size(), safeOffset, safeLimit, documents);
    }

    private RebuildResult reindexArchived(boolean clearFirst) {
        List<ArchivedDocument> documents = documentArchive.listAll();
        if (documents.isEmpty()) {
            if (clearFirst) {
                throw new IllegalStateException("没有归档文档，拒绝清空向量库");
            }
            return new RebuildResult(0, 0, 0, false);
        }

        if (clearFirst) {
            vectorStore.delete(new FilterExpressionBuilder()
                    .in(ChunkingService.FIELD_CHUNK_TYPE,
                            ChunkingService.CHUNK_TYPE_CHUNK,
                            ChunkingService.CHUNK_TYPE_CHILD,
                            ChunkingService.CHUNK_TYPE_PARENT)
                    .build());
            log.info("已清空当前集合中由 small-rag 写入的片段，开始全量重建: {} 篇", documents.size());
        }

        String signature = currentSignature();
        int succeeded = 0;
        int failed = 0;
        int consecutiveFailures = 0;
        for (ArchivedDocument document : documents) {
            try {
                reindex(document.documentId(), document.text(), document.metadata());
                documentArchive.markIndexed(document.documentId(), signature);
                succeeded++;
                consecutiveFailures = 0;
            } catch (Exception e) {
                failed++;
                consecutiveFailures++;
                log.warn("重建失败: docId={}, 原因={}", document.documentId(), e.toString());
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    log.error("连续 {} 篇重建失败，中止本次任务", consecutiveFailures);
                    return new RebuildResult(documents.size(), succeeded, failed, true);
                }
            }
        }
        log.info("重建完成: total={}, succeeded={}, failed={}", documents.size(), succeeded, failed);
        return new RebuildResult(documents.size(), succeeded, failed, false);
    }

    private void markIndexedQuietly(String documentId) {
        try {
            documentArchive.markIndexed(documentId, currentSignature());
        } catch (RuntimeException e) {
            log.warn("归档签名更新失败，将在下次启动重新切分: docId={}, 原因={}",
                    documentId, e.toString());
        }
    }

    private String currentSignature() {
        return ChunkingSignature.of(properties, qdrantProperties.getCollectionName());
    }

    private void requireArchiveEnabled() {
        if (!documentArchive.isEnabled()) {
            throw new IllegalStateException("原始文档归档不可用，无法执行该管理操作");
        }
    }

    private static void requireDocumentId(String documentId) {
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("documentId 不能为空");
        }
        try {
            UUID.fromString(documentId);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("documentId 必须是 UUID", e);
        }
    }

    private IngestResult index(String documentId, String text, Map<String, String> metadata) {
        ChunkingService.ChunkingResult chunked = chunkingService.chunk(text, documentId);
        List<Document> documents = buildDocuments(documentId, metadata, chunked);
        vectorStore.add(documents);
        log.info("文档已入库: docId={}, strategy={}, chunks={}, parents={}, metadata={}",
                documentId, chunkingService.strategy(), chunked.chunks().size(),
                chunked.parentCount(), metadata);
        return new IngestResult(documentId, chunked.chunks().size(), chunked.parentCount());
    }

    private List<Document> buildDocuments(String documentId,
            Map<String, String> metadata,
            ChunkingService.ChunkingResult chunked) {
        List<TextChunk> chunks = chunked.chunks();
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("text 不能为空");
        }
        String ingestedAt = Instant.now().toString();

        List<Document> documents = new ArrayList<>(chunks.size() + chunked.parents().size());
        for (int i = 0; i < chunks.size(); i++) {
            TextChunk chunk = chunks.get(i);
            Map<String, Object> documentMetadata = baseMetadata(metadata, chunk.metadata(), documentId, ingestedAt);
            documentMetadata.put("chunk_index", i);
            documentMetadata.put("chunk_count", chunks.size());
            documents.add(new Document(chunk.text(), documentMetadata));
        }
        // 父块作为独立 point：id 由 parent_id 确定性生成，重切/更新后保持稳定
        for (TextChunk parent : chunked.parents()) {
            String parentId = String.valueOf(parent.metadata().get(ChunkingService.FIELD_PARENT_ID));
            Map<String, Object> documentMetadata = baseMetadata(metadata, parent.metadata(), documentId, ingestedAt);
            documents.add(Document.builder()
                    .id(parentPointUuid(parentId).toString())
                    .text(parent.text())
                    .metadata(documentMetadata)
                    .build());
        }
        return documents;
    }

    private Map<String, Object> baseMetadata(Map<String, String> businessMetadata,
            Map<String, Object> chunkMetadata,
            String documentId,
            String ingestedAt) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (businessMetadata != null) {
            result.putAll(businessMetadata);
        }
        result.putAll(chunkMetadata);
        result.put("doc_id", documentId);
        result.put("chunk_strategy", ChunkingService.strategyName(chunkingService.strategy()));
        result.put("ingested_at", ingestedAt);
        return result;
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
        double contextWeight = 0d;
        int index = 1;
        for (Document document : documents) {
            String text = document.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            text = truncateAroundAnchor(text,
                    document.getMetadata().get("matched_text"), maxChunkChars);
            double textWeight = TextWeights.weight(text);
            if (contextWeight + textWeight > maxContextChars) {
                // 预算不足时跳过当前片段，继续尝试装载后续更短的片段
                continue;
            }
            contextWeight += textWeight + TextWeights.NARROW;

            context.append('[').append(index++).append(']');
            Object source = document.getMetadata().get("source");
            if (source != null && !String.valueOf(source).isBlank()) {
                context.append(" source=").append(source);
            }
            Object headingPath = document.getMetadata().get(MarkdownChunker.FIELD_HEADING_PATH);
            if (headingPath != null && !String.valueOf(headingPath).isBlank()) {
                context.append(" heading=").append(headingPath);
            }
            if (document.getScore() != null) {
                context.append(String.format(Locale.ROOT, " score=%.3f", document.getScore()));
            }
            context.append(' ').append(text).append('\n');
        }
        return context.toString();
    }

    /**
     * 单片段硬截断：优先以命中子块（matched_text）为中心保留前后文窗口。
     *
     * <p>Parent-Child 展开后的父块可能超过 maxChunkChars；从头部截断会把
     * 位于父块尾部的命中内容切掉。找不到锚点时回退为从头部截断。
     */
    private static String truncateAroundAnchor(String text, Object anchorObj, int maxChars) {
        if (TextWeights.weight(text) <= maxChars) {
            return text;
        }
        String anchor = anchorObj == null ? null : String.valueOf(anchorObj);
        int anchorIndex = locateAnchor(text, anchor);
        if (anchorIndex < 0) {
            // 头部截断：保留权重预算内的前缀
            return text.substring(0, TextWeights.fitChars(text, Math.max(1, maxChars - 1))) + "…";
        }

        // 内容预算扣掉可能出现的省略号，保证结果不超过 maxChars（全宽字符当量）
        double budget = Math.max(1, maxChars - 2);
        int anchorLen = TextWeights.fitChars(anchor, budget);
        double anchorWeight = TextWeights.weight(anchor.substring(0, anchorLen));
        int anchorEnd = anchorIndex + anchorLen;

        double side = Math.max(0d, (budget - anchorWeight) / 2);
        int from = TextWeights.fitCharsBefore(text, anchorIndex, side);
        double usedBefore = TextWeights.weight(text.substring(from, anchorEnd));
        int to = Math.min(text.length(),
                anchorEnd + TextWeights.fitChars(text.substring(anchorEnd), Math.max(0d, budget - usedBefore)));

        // 前向空间被尾部截断时，把剩余预算还给前文
        double used = TextWeights.weight(text.substring(from, to));
        if (from > 0 && used < budget) {
            from = TextWeights.fitCharsBefore(text, from, budget - used);
        }

        StringBuilder result = new StringBuilder();
        if (from > 0) {
            result.append('…');
        }
        result.append(text, from, to);
        if (to < text.length()) {
            result.append('…');
        }
        return result.toString();
    }

    /**
     * 在父块中定位命中子块；完整匹配失败时用锚点前 64 字符退化定位
     * （锚点自身可能在入库后被轻微改写）。
     */
    private static int locateAnchor(String text, String anchor) {
        if (anchor == null || anchor.isBlank()) {
            return -1;
        }
        int index = text.indexOf(anchor);
        if (index >= 0) {
            return index;
        }
        String head = anchor.substring(0, Math.min(64, anchor.length()));
        return text.indexOf(head);
    }

    /**
     * Parent-Child 检索结果展开：按 parent_id 去重，从 Qdrant 取回父块独立 point，
     * 用父块正文替换命中的子块正文。
     *
     * <p>同一父块的多个子块命中只保留最高分的一条，避免父块正文重复占用
     * 上下文预算；子块原文（去除 heading 前缀）保留在 {@code matched_text}
     * 元数据中，便于排查召回精度，也作为上下文窗口截断的定位锚点。
     * 父块 point 缺失（旧数据 / 取回失败）时回退为子块原文。
     */
    private List<Document> expandToParents(List<Document> children, int limit) {
        if (children == null || children.isEmpty()) {
            return children == null ? List.of() : children;
        }

        // 输入已按相似度降序；putIfAbsent 保留每个父块的最高分命中
        Map<String, Document> bestByParent = new LinkedHashMap<>();
        for (Document child : children) {
            Object parentId = child.getMetadata().get(ChunkingService.FIELD_PARENT_ID);
            String key = parentId != null && !String.valueOf(parentId).isBlank()
                    ? String.valueOf(parentId)
                    : "point:" + child.getId();
            bestByParent.putIfAbsent(key, child);
        }

        Set<String> realParentIds = bestByParent.keySet().stream()
                .filter(key -> !key.startsWith("point:"))
                .collect(Collectors.toSet());
        Map<String, ParentPoint> parents = fetchParents(realParentIds);

        List<Candidate> candidates = new ArrayList<>(bestByParent.size());
        for (Map.Entry<String, Document> entry : bestByParent.entrySet()) {
            Document child = entry.getValue();
            ParentPoint parent = parents.get(entry.getKey());
            if (parent == null) {
                // 回退：父块 point 缺失时保留子块原文，保证检索不中断
                candidates.add(new Candidate(child, null, scoreOrZero(child)));
                continue;
            }
            Map<String, Object> metadata = new LinkedHashMap<>(child.getMetadata());
            metadata.put("matched_text", stripHeadingPrefix(child.getText(),
                    metadata.get(MarkdownChunker.FIELD_HEADING_PATH)));
            Document parentDoc = Document.builder()
                    .id(parent.pointId())
                    .text(parent.text())
                    .metadata(metadata)
                    .score(child.getScore())
                    .build();
            candidates.add(new Candidate(parentDoc, parent.vector(), scoreOrZero(child)));
        }
        return selectTop(candidates, limit);
    }

    /**
     * 按 parent_id 从 Qdrant 批量取回父块独立 point（正文 + 向量）。
     * 失败时返回空 Map，调用方回退为子块结果。
     */
    private Map<String, ParentPoint> fetchParents(Set<String> parentIds) {
        if (parentIds == null || parentIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> uuidToParent = new HashMap<>();
        List<PointId> pointIds = new ArrayList<>(parentIds.size());
        for (String parentId : parentIds) {
            UUID uuid = parentPointUuid(parentId);
            uuidToParent.put(uuid, parentId);
            pointIds.add(PointIdFactory.id(uuid));
        }
        try {
            List<RetrievedPoint> points = qdrantClient
                    .retrieveAsync(qdrantProperties.getCollectionName(), pointIds, true, true, null)
                    .get(qdrantProperties.getInitTimeoutMs(), TimeUnit.MILLISECONDS);
            Map<String, ParentPoint> result = new HashMap<>();
            for (RetrievedPoint point : points) {
                UUID uuid = UUID.fromString(point.getId().getUuid());
                String parentId = uuidToParent.get(uuid);
                if (parentId == null) {
                    continue;
                }
                Value content = point.getPayloadMap().get(CONTENT_FIELD);
                if (content == null || content.getStringValue().isBlank()) {
                    continue;
                }
                result.put(parentId, new ParentPoint(parentId, point.getId().getUuid(),
                        content.getStringValue(), extractVector(point.getVectors())));
            }
            return result;
        } catch (Exception e) {
            log.warn("父块独立 point 取回失败，回退为子块结果: {}", e.toString());
            return Map.of();
        }
    }

    /**
     * 从 RetrievedPoint 提取稠密向量；无向量或非稠密时返回 null（MMR 退化为纯相关排序）。
     */
    private static float[] extractVector(VectorsOutput vectors) {
        if (vectors == null || !vectors.hasVector()) {
            return null;
        }
        List<Float> data = vectors.getVector().hasDense()
                ? vectors.getVector().getDense().getDataList()
                : vectors.getVector().getDataList();
        if (data == null || data.isEmpty()) {
            return null;
        }
        float[] result = new float[data.size()];
        for (int i = 0; i < data.size(); i++) {
            result[i] = data.get(i);
        }
        return result;
    }

    /**
     * parent_id → 父块 point id 的确定性映射，重切后保持稳定。
     */
    public static UUID parentPointUuid(String parentId) {
        return UUID.nameUUIDFromBytes(("parent:" + parentId).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * MMR 是否启用：{@code 0 < rag.mmr-lambda < 1} 时启用，λ=1 为纯相关排序。
     */
    private boolean mmrEnabled() {
        double lambda = properties.getMmrLambda();
        return lambda > 0d && lambda < 1d;
    }

    /**
     * 从候选中选出最终 topK：候选不多或 MMR 关闭时按分数截取。
     */
    private List<Document> selectTop(List<Candidate> candidates, int limit) {
        if (candidates.size() <= limit) {
            return candidates.stream().map(Candidate::document).toList();
        }
        if (!mmrEnabled()) {
            return candidates.stream()
                    .sorted(Comparator.comparingDouble(Candidate::score).reversed())
                    .limit(limit)
                    .map(Candidate::document)
                    .toList();
        }
        return selectByMmr(candidates, limit);
    }

    /**
     * MMR（最大边际相关）选择：每轮选出
     * {@code λ·score − (1−λ)·与已选集合的最大余弦相似度} 最大的候选，
     * 在相关性与多样性之间折中。无向量的候选按相似度 0 参与（退化为纯相关）。
     */
    private List<Document> selectByMmr(List<Candidate> candidates, int limit) {
        double lambda = Math.min(1d, Math.max(0d, properties.getMmrLambda()));
        List<Candidate> pool = new ArrayList<>(candidates);
        List<Candidate> selected = new ArrayList<>(limit);

        Candidate first = pool.stream().max(Comparator.comparingDouble(Candidate::score)).orElse(null);
        if (first == null) {
            return List.of();
        }
        selected.add(first);
        pool.remove(first);

        while (selected.size() < limit && !pool.isEmpty()) {
            Candidate best = null;
            double bestValue = Double.NEGATIVE_INFINITY;
            for (Candidate candidate : pool) {
                double value = lambda * candidate.score()
                        - (1 - lambda) * maxSimilarity(candidate.vector(), selected);
                if (value > bestValue) {
                    bestValue = value;
                    best = candidate;
                }
            }
            selected.add(best);
            pool.remove(best);
        }
        return selected.stream().map(Candidate::document).toList();
    }

    /**
     * 非 Parent-Child 策略的 MMR：对候选文本做一次批量 Embedding 后选择。
     * 向量化失败时回退为纯相关排序（输入本身已按分数降序）。
     */
    private List<Document> selectByMmrWithEmbedding(List<Document> documents, int limit) {
        try {
            List<String> texts = documents.stream().map(Document::getText).toList();
            int batchSize = Math.max(1, properties.getChunking().getSemantic().getBatchSize());
            List<float[]> vectors = SemanticTextChunker.embedAll(embeddingModel, texts, batchSize);
            List<Candidate> candidates = new ArrayList<>(documents.size());
            for (int i = 0; i < documents.size(); i++) {
                candidates.add(new Candidate(documents.get(i), vectors.get(i), scoreOrZero(documents.get(i))));
            }
            return selectByMmr(candidates, limit);
        } catch (RuntimeException e) {
            log.warn("MMR 候选向量化失败，回退为纯相关排序: {}", e.toString());
            return documents.subList(0, limit);
        }
    }

    private static double maxSimilarity(float[] vector, List<Candidate> selected) {
        if (vector == null || vector.length == 0) {
            return 0d;
        }
        double max = 0d;
        for (Candidate candidate : selected) {
            if (candidate.vector() == null || candidate.vector().length != vector.length) {
                continue;
            }
            max = Math.max(max, SemanticTextChunker.cosine(vector, candidate.vector()));
        }
        return max;
    }

    private static double scoreOrZero(Document document) {
        return document.getScore() == null ? 0d : document.getScore();
    }

    /**
     * 去掉子块正文前置的 heading_path，得到可在父块中定位的原始子块文本。
     */
    private static String stripHeadingPrefix(String text, Object headingPath) {
        if (text == null || headingPath == null) {
            return text;
        }
        String prefix = headingPath + "\n";
        return text.startsWith(prefix) ? text.substring(prefix.length()) : text;
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
     * @param chunkCount 实际写入向量库的子块/片段数；Parent-Child 下为子块数（父块另计）
     * @param parentCount 父块数；非 Parent-Child 策略为 0
     */
    public record IngestResult(String documentId, int chunkCount, int parentCount) {
    }

    /**
     * 文档分页结果。
     */
    public record DocumentPage(int total, int offset, int limit, List<DocumentSummary> documents) {
    }

    /**
     * 归档文档摘要（不返回全文）。
     */
    public record DocumentSummary(String documentId,
            Map<String, String> metadata,
            String chunkSignature,
            String updatedAt,
            int textLength) {
    }

    /**
     * 重建结果。
     *
     * @param total     已归档文档总数
     * @param succeeded 成功重建数
     * @param failed    失败数
     * @param aborted   是否因连续失败而中止
     */
    public record RebuildResult(int total, int succeeded, int failed, boolean aborted) {
    }

    /**
     * MMR 候选：文档 + 参与多样性计算的向量（可为 null）+ 相关性分数。
     */
    private record Candidate(Document document, float[] vector, double score) {
    }

    /**
     * 从 Qdrant 取回的父块独立 point。
     */
    private record ParentPoint(String parentId, String pointId, String text, float[] vector) {
    }
}
