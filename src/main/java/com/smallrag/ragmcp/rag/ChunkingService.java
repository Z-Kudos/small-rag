package com.smallrag.ragmcp.rag;

import com.smallrag.ragmcp.config.RagProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 按配置选择切分策略，把纯文本转成待入库的片段。
 *
 * <p>所有策略最终都输出“可写入向量库的片段列表”；Parent-Child 只写入子块，
 * 父块正文保存在子块元数据 {@code parent_text} 中，检索命中后再展开。
 *
 * @author zjk
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChunkingService {

    static final String FIELD_CHUNK_TYPE = "chunk_type";
    static final String CHUNK_TYPE_CHUNK = "chunk";
    static final String CHUNK_TYPE_CHILD = "child";
    static final String FIELD_PARENT_ID = "parent_id";
    static final String FIELD_PARENT_INDEX = "parent_index";
    static final String FIELD_PARENT_TEXT = "parent_text";

    private final RagProperties properties;
    private final EmbeddingModel embeddingModel;

    /**
     * @param text       原文
     * @param documentId 本次入库的文档 id，用于生成稳定的 parent_id
     * @return 片段列表与父块数量（非 Parent-Child 策略为 0）
     */
    public ChunkingResult chunk(String text, String documentId) {
        ChunkingStrategy strategy = strategy();
        log.debug("使用切分策略 {}: textLength={}, documentId={}",
                strategy, text == null ? 0 : text.length(), documentId);

        return switch (strategy) {
            case FIXED -> new ChunkingResult(fixed(text), 0);
            case RECURSIVE -> new ChunkingResult(recursive(text), 0);
            case SEMANTIC -> new ChunkingResult(semantic(text), 0);
            case PARENT_CHILD -> parentChild(text, documentId);
        };
    }

    /**
     * 当前配置的切分策略，空值回落到 PARENT_CHILD。
     */
    public ChunkingStrategy strategy() {
        ChunkingStrategy strategy = properties.getChunking().getStrategy();
        return strategy == null ? ChunkingStrategy.PARENT_CHILD : strategy;
    }

    private List<TextChunk> fixed(String text) {
        List<String> parts = TextChunker.chunk(text, properties.getChunkSize(), properties.getChunkOverlap());
        return toChunks(parts);
    }

    private List<TextChunk> recursive(String text) {
        List<String> parts = RecursiveTextChunker.chunk(
                text,
                properties.getChunkSize(),
                properties.getChunkOverlap(),
                separators());
        return toChunks(parts);
    }

    private List<TextChunk> semantic(String text) {
        RagProperties.Semantic config = properties.getChunking().getSemantic();
        int maxChars = config.getMaxChunkChars() > 0
                ? config.getMaxChunkChars()
                : properties.getChunkSize();
        List<String> parts = SemanticTextChunker.chunk(
                text,
                embeddingModel,
                maxChars,
                config.getMinChunkChars(),
                config.getSimilarityThreshold(),
                config.getBatchSize());
        return toChunks(parts);
    }

    private ChunkingResult parentChild(String text, String documentId) {
        RagProperties.ParentChild config = properties.getChunking().getParentChild();
        List<String> separators = separators();
        List<String> parents = RecursiveTextChunker.chunk(
                text,
                config.getParentSize(),
                config.getParentOverlap(),
                separators);

        List<TextChunk> chunks = new ArrayList<>();
        for (int parentIndex = 0; parentIndex < parents.size(); parentIndex++) {
            String parentText = parents.get(parentIndex);
            String parentId = documentId + "#p" + parentIndex;
            List<String> children = RecursiveTextChunker.chunk(
                    parentText,
                    config.getChildSize(),
                    config.getChildOverlap(),
                    separators);

            for (String child : children) {
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put(FIELD_CHUNK_TYPE, CHUNK_TYPE_CHILD);
                metadata.put(FIELD_PARENT_ID, parentId);
                metadata.put(FIELD_PARENT_INDEX, parentIndex);
                metadata.put(FIELD_PARENT_TEXT, parentText);
                chunks.add(new TextChunk(child, metadata));
            }
        }
        log.debug("Parent-Child 切分完成: parents={}, children={}", parents.size(), chunks.size());
        return new ChunkingResult(chunks, parents.size());
    }

    private List<TextChunk> toChunks(List<String> parts) {
        List<TextChunk> chunks = new ArrayList<>(parts.size());
        for (String part : parts) {
            chunks.add(new TextChunk(part, Map.of(FIELD_CHUNK_TYPE, CHUNK_TYPE_CHUNK)));
        }
        return chunks;
    }

    private List<String> separators() {
        List<String> configured = properties.getChunking().getRecursive().getSeparators();
        if (configured == null || configured.isEmpty()) {
            return List.of("\n\n", "\n", "。", "！", "？", "；", "，", " ");
        }
        return configured;
    }

    static String strategyName(ChunkingStrategy strategy) {
        return strategy.name().toLowerCase(Locale.ROOT);
    }

    /**
     * 切分结果。
     *
     * @param chunks      待写入向量库的片段
     * @param parentCount 父块数量；非 Parent-Child 策略为 0
     */
    public record ChunkingResult(List<TextChunk> chunks, int parentCount) {
    }
}
