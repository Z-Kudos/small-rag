package com.smallrag.ragmcp.rag.chunking;

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
 * <p>所有策略最终都输出“可写入向量库的片段列表”。Parent-Child 额外输出
 * 父块片段：父块作为独立 point（{@code chunk_type=parent}，id 由 parent_id
 * 确定性生成）与子块同集合写入，子块只携带 {@code parent_id}，检索命中后
 * 按 id 取回父块正文。
 *
 * @author zjk
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChunkingService {

    public static final String FIELD_CHUNK_TYPE = "chunk_type";
    public static final String CHUNK_TYPE_CHUNK = "chunk";
    public static final String CHUNK_TYPE_CHILD = "child";
    public static final String FIELD_PARENT_ID = "parent_id";
    public static final String FIELD_PARENT_INDEX = "parent_index";
    public static final String CHUNK_TYPE_PARENT = "parent";

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
            case FIXED -> new ChunkingResult(fixed(text), List.of(), 0);
            case RECURSIVE -> new ChunkingResult(recursive(text), List.of(), 0);
            case SEMANTIC -> new ChunkingResult(semantic(text), List.of(), 0);
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
        return toPlainChunks(parts);
    }

    private List<TextChunk> recursive(String text) {
        List<String> parts = RecursiveTextChunker.chunk(
                text,
                properties.getChunkSize(),
                properties.getChunkOverlap(),
                separators());
        return toPlainChunks(parts);
    }

    /**
     * 语义切分：Markdown 结构分节 + 节内文本块与累积质心的相似度合并。
     */
    private List<TextChunk> semantic(String text) {
        RagProperties.Semantic config = properties.getChunking().getSemantic();
        int maxChars = config.getMaxChunkChars() > 0
                ? config.getMaxChunkChars()
                : properties.getChunkSize();
        return withChunkType(prependHeadingPath(MarkdownChunker.chunkSemantic(
                text,
                maxChars,
                config.getMinChunkChars(),
                headingSeparator(),
                Map.of(),
                embeddingModel,
                config.getSimilarityThreshold(),
                config.getBreakpointPercentile(),
                config.getBatchSize())));
    }

    private ChunkingResult parentChild(String text, String documentId) {
        RagProperties.ParentChild config = properties.getChunking().getParentChild();
        String separator = headingSeparator();
        List<TextChunk> parents = MarkdownChunker.chunk(
                text,
                config.getParentSize(),
                config.getParentOverlap(),
                separator,
                Map.of());

        List<TextChunk> chunks = new ArrayList<>();
        List<TextChunk> parentChunks = new ArrayList<>(parents.size());
        for (int parentIndex = 0; parentIndex < parents.size(); parentIndex++) {
            TextChunk parent = parents.get(parentIndex);
            String parentText = parent.text();
            String parentId = documentId + "#p" + parentIndex;

            // 父块作为独立片段输出：chunk_type=parent，metadata 带 parent_id
            Map<String, Object> parentMetadata = new LinkedHashMap<>(parent.metadata());
            parentMetadata.put(FIELD_CHUNK_TYPE, CHUNK_TYPE_PARENT);
            parentMetadata.put(FIELD_PARENT_ID, parentId);
            parentMetadata.put(FIELD_PARENT_INDEX, parentIndex);
            parentChunks.add(new TextChunk(parentText, parentMetadata));

            // 父块已经带有完整 heading_path；子块重新解析时只继承非标题元数据，
            // 解析完后再把完整标题路径补回，避免只得到当前标题。
            Map<String, Object> childBase = new LinkedHashMap<>(parent.metadata());
            childBase.remove(MarkdownChunker.FIELD_HEADING);
            childBase.remove(MarkdownChunker.FIELD_HEADING_LEVEL);
            childBase.remove(MarkdownChunker.FIELD_HEADING_PATH);
            childBase.remove("wikilinks");
            childBase.remove("oversized_block");
            List<TextChunk> children = MarkdownChunker.chunk(
                    parentText,
                    config.getChildSize(),
                    config.getChildOverlap(),
                    separator,
                    childBase);

            Object parentHeadingPath = parent.metadata().get(MarkdownChunker.FIELD_HEADING_PATH);
            for (TextChunk child : children) {
                Map<String, Object> metadata = new LinkedHashMap<>(child.metadata());
                if (parentHeadingPath != null) {
                    metadata.put(MarkdownChunker.FIELD_HEADING_PATH, parentHeadingPath);
                    metadata.put(MarkdownChunker.FIELD_HEADING,
                            parent.metadata().get(MarkdownChunker.FIELD_HEADING));
                    metadata.put(MarkdownChunker.FIELD_HEADING_LEVEL,
                            parent.metadata().get(MarkdownChunker.FIELD_HEADING_LEVEL));
                }
                metadata.put(FIELD_CHUNK_TYPE, CHUNK_TYPE_CHILD);
                metadata.put(FIELD_PARENT_ID, parentId);
                metadata.put(FIELD_PARENT_INDEX, parentIndex);
                chunks.add(prependHeadingPath(new TextChunk(child.text(), metadata)));
            }
        }
        log.debug("Parent-Child 切分完成: parents={}, children={}", parents.size(), chunks.size());
        return new ChunkingResult(chunks, parentChunks, parents.size());
    }

    private List<TextChunk> toPlainChunks(List<String> parts) {
        List<TextChunk> chunks = new ArrayList<>(parts.size());
        for (String part : parts) {
            chunks.add(new TextChunk(part, Map.of(FIELD_CHUNK_TYPE, CHUNK_TYPE_CHUNK)));
        }
        return chunks;
    }

    /**
     * 给不以 Markdown 标题行开头的片段正文前置 heading_path。
     *
     * <p>标题行通常只出现在章节首个片段的正文里，中后部片段嵌入时看不到
     * 章节主题（metadata 中的 heading_path 不参与向量化）。前置标题路径
     * 让向量模型感知完整章节层级，对“标题即主题”的 Markdown/Obsidian
     * 语料召回质量提升明显。代价是片段长度可能超出 maxChars 一个标题
     * 路径的长度。
     */
    private static List<TextChunk> prependHeadingPath(List<TextChunk> chunks) {
        List<TextChunk> result = new ArrayList<>(chunks.size());
        for (TextChunk chunk : chunks) {
            result.add(prependHeadingPath(chunk));
        }
        return result;
    }

    private static TextChunk prependHeadingPath(TextChunk chunk) {
        Object headingPath = chunk.metadata().get(MarkdownChunker.FIELD_HEADING_PATH);
        if (headingPath == null || String.valueOf(headingPath).isBlank()) {
            return chunk;
        }
        String text = chunk.text();
        if (text.startsWith("#")) {
            // 片段以标题行开头，标题已在正文中，不重复前置
            return chunk;
        }
        return new TextChunk(headingPath + "\n" + text, chunk.metadata());
    }

    private List<TextChunk> withChunkType(List<TextChunk> chunks) {
        List<TextChunk> result = new ArrayList<>(chunks.size());
        for (TextChunk chunk : chunks) {
            Map<String, Object> metadata = new LinkedHashMap<>(chunk.metadata());
            metadata.put(FIELD_CHUNK_TYPE, CHUNK_TYPE_CHUNK);
            result.add(new TextChunk(chunk.text(), metadata));
        }
        return result;
    }

    private String headingSeparator() {
        String separator = properties.getChunking().getMarkdown().getHeadingSeparator();
        return (separator == null || separator.isBlank()) ? " > " : separator;
    }

    private List<String> separators() {
        List<String> configured = properties.getChunking().getRecursive().getSeparators();
        if (configured == null || configured.isEmpty()) {
            return List.of("\n\n", "\n", "。", "！", "？", "；", "，", " ");
        }
        return configured;
    }

    public static String strategyName(ChunkingStrategy strategy) {
        return strategy.name().toLowerCase(Locale.ROOT);
    }

    /**
     * 切分结果。
     *
     * @param chunks      待写入向量库的片段；Parent-Child 下为子块
     * @param parents     父块片段（独立 point 写入）；非 Parent-Child 策略为空
     * @param parentCount 父块数量；非 Parent-Child 策略为 0
     */
    public record ChunkingResult(List<TextChunk> chunks, List<TextChunk> parents, int parentCount) {
    }
}
