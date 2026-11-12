package com.smallrag.ragmcp.rag.edit;

import com.smallrag.ragmcp.config.QdrantProperties;
import com.smallrag.ragmcp.config.RagProperties;
import com.smallrag.ragmcp.rag.RagService;
import com.smallrag.ragmcp.rag.archive.ArchivedDocument;
import com.smallrag.ragmcp.rag.archive.DocumentArchive;
import com.smallrag.ragmcp.rag.chunking.ChunkingService;
import com.smallrag.ragmcp.rag.chunking.ChunkingSignature;
import com.smallrag.ragmcp.rag.chunking.TextChunk;
import com.smallrag.ragmcp.rag.vectorstore.ChunkCatalog;
import com.smallrag.ragmcp.rag.vectorstore.ChunkSummary;
import com.smallrag.ragmcp.rag.vectorstore.IndexNamespaces;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Chunk 级 Agent 面编辑：按 point id 读取、替换或删除某个 chunk，
 * 变更先写回归档原文，再整篇重切（与 {@code rag_update} 相同的语义）。
 *
 * <p>所有写操作都先在内存中验证位置（按当前签名重切对应的正文片段），
 * 任何找不到对应位置的报错不会落盘。{@code expectedText} 非空时必须
 * 与当前 chunk 内容一致，防止 Agent 基于过期快照覆盖新内容。
 *
 * <p>归档签名在写入前置空，重切失败时下次启动自动补齐（与
 * {@code RagService.update} 相同的自愈语义）。
 *
 * @author zjk
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChunkEditService {

    private final ChunkCatalog catalog;
    private final DocumentArchive archive;
    private final RagService ragService;
    private final ChunkingService chunkingService;
    private final RagProperties properties;
    private final QdrantProperties qdrantProperties;
    private final ObjectMapper objectMapper;

    /**
     * @return 单个 chunk 的视图（pointId / docId / chunkType / 正文 / metadata）
     */
    public String getChunk(String pointId) {
        ChunkSummary chunk = find(pointId)
                .orElseThrow(() -> new IllegalArgumentException("chunk 不存在: " + pointId));
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("pointId", chunk.pointId());
        payload.put("text", chunk.text());
        putIfNotNull(payload, "docId", stringField(chunk.metadata(), "doc_id"));
        putIfNotNull(payload, "chunkType", stringField(chunk.metadata(), "chunk_type"));
        putIfNotNull(payload, "headingPath", stringField(chunk.metadata(), "heading_path"));
        putIfNotNull(payload, "parentId", stringField(chunk.metadata(), "parent_id"));
        putIfNotNull(payload, "chunkIndex", chunk.metadata().get("chunk_index"));
        putIfNotNull(payload, "parentIndex", chunk.metadata().get("parent_index"));
        putIfNotNull(payload, "links", chunk.metadata().get("links"));
        payload.put("metadata", chunk.metadata());
        return toJson(payload);
    }

    private static void putIfNotNull(Map<String, Object> payload, String key, Object value) {
        if (value != null) {
            payload.put(key, value);
        }
    }

    /**
     * 替换 chunk 正文；{@code expectedText} 非空时必须与当前内容一致。
     *
     * @return 编辑回执（文档 id、新片段数、父块数、当前签名）
     */
    public String editChunk(String pointId, String newText, String expectedText) {
        if (newText == null) {
            throw new IllegalArgumentException("newText 不能为空");
        }
        EditResult result = doEdit(pointId, newText, expectedText);
        return toJson(receipt(result, pointId, null));
    }

    /**
     * 删除 chunk（从归档原文中删除该片段对应的文本段，后整篇重切）。
     *
     * @param confirm `true` 才继续；否则返回错误说明
     */
    public String deleteChunk(String pointId, boolean confirm, String expectedText) {
        if (!confirm) {
            throw new IllegalArgumentException("删除 chunk 需要显式 confirm=true（与 rag_rebuild 一致）");
        }
        EditResult result = doEdit(pointId, "", expectedText);
        return toJson(receipt(result, pointId, "deleted"));
    }

    private EditResult doEdit(String pointId, String replacement, String expectedText) {
        ChunkSummary target = find(pointId)
                .orElseThrow(() -> new IllegalArgumentException("chunk 不存在: " + pointId));
        if (expectedText != null && !expectedText.isEmpty()
                && !expectedText.equals(target.text())) {
            throw new IllegalArgumentException(
                    "expectedText 与当前 chunk 内容不一致，请先 rag_get_chunk 确认当前内容；当前内容: "
                            + snippet(target.text()));
        }
        String docId = stringField(target.metadata(), "doc_id");
        if (docId == null) {
            throw new IllegalStateException("chunk 缺少 doc_id，无法编辑: " + pointId);
        }
        ArchivedDocument archived = archive.find(docId)
                .orElseThrow(() -> new IllegalStateException(
                        "文档不存在或归档不可用，无法编辑: docId=" + docId));

        String archivedText = archived.text();
        String targetHeadingPath = stringField(target.metadata(), "heading_path");
        String body = stripHeadingPrefix(target.text(), targetHeadingPath);

        // 按当前签名重切，定位目标 chunk：tuple(type, parent_index, chunk_index, body) 精确匹配
        List<TextChunk> reChunked = chunkingService.chunk(archivedText, docId).chunks();
        int targetIndex = locate(reChunked, target, docId, targetHeadingPath);
        if (targetIndex < 0) {
            throw new IllegalStateException(
                    "无法定位 chunk（归档与重切结果不一致），请先 rag_update 全量更新该文档: docId=" + docId);
        }

        // 顺序游标扫描：chunk 出现顺序在原文中的位置单调递增。
        // chunker 会折叠空行/压缩空白，先尝试精确匹配，失败时回退到按 \s+ 折叠的正则定位。
        int cursor = 0;
        int spanStart = -1;
        int spanLength = 0;
        for (int i = 0; i < reChunked.size(); i++) {
            TextChunk chunk = reChunked.get(i);
            String headingPath = stringField(chunk.metadata(), "heading_path");
            String current = stripHeadingPrefix(chunk.text(), headingPath);
            int[] span = findSpan(archivedText, current, cursor);
            if (span == null) {
                throw new IllegalStateException(
                        "定位 chunk 失败（归档内容与重切结果不一致），请先 rag_update 该文档: docId=" + docId);
            }
            if (i == targetIndex) {
                spanStart = span[0];
                spanLength = span[1] - span[0];
                break;
            }
            cursor = span[0] + 1;
        }

        String rebuilt = archivedText.substring(0, spanStart) + replacement
                + archivedText.substring(spanStart + spanLength);

        if (rebuilt == null || rebuilt.isBlank()) {
            // 删空后文档不再有任何 chunk：直接整篇删除，保持与 rag_delete 一致
            ragService.delete(docId);
            return new EditResult(docId, 0, 0, null);
        }

        // 栅栏：先归档（签名置空），再重嵌；任一步失败，签名置空 + 启动自愈保底
        archive.save(docId, rebuilt, archived.metadata(), null);
        RagService.IngestResult reindex = ragService.reindex(docId, rebuilt, archived.metadata());
        return new EditResult(docId, reindex.chunkCount(), reindex.parentCount(),
                ChunkingSignature.of(properties, IndexNamespaces.of(properties, qdrantProperties)));
    }

    private Optional<ChunkSummary> find(String pointId) {
        if (pointId == null || pointId.isBlank()) {
            return Optional.empty();
        }
        return catalog.listChunks(null).stream()
                .filter(chunk -> pointId.equals(chunk.pointId()))
                .findFirst();
    }

    /**
     * 去掉 prependHeadingPath 前置的 heading_path 行，得到与原文对应的片段正文。
     * 仅当前文片段正文确实以 headingPath + "\n" 开头时才去掉，其余保留原样。
     */
    static String stripHeadingPrefix(String stored, String headingPath) {
        if (stored == null) {
            return "";
        }
        if (headingPath != null && !headingPath.isBlank()) {
            String prefix = headingPath + "\n";
            if (stored.startsWith(prefix)) {
                return stored.substring(prefix.length());
            }
        }
        return stored;
    }

    /**
     * 精确定位 target 在重切列表中的下标：tuple(type, parent_index, chunk_index, body)
     * 精确匹配；多于一个匹配返回 -1（视为不一致）。
     */
    private int locate(List<TextChunk> reChunked, ChunkSummary target, String docId,
            String targetHeadingPath) {
        String body = stripHeadingPrefix(target.text(), targetHeadingPath);
        String targetType = stringField(target.metadata(), "chunk_type");
        int found = -1;
        for (int i = 0; i < reChunked.size(); i++) {
            TextChunk chunk = reChunked.get(i);
            String currentType = stringField(chunk.metadata(), "chunk_type");
            if (targetType != null && !targetType.equals(currentType)) {
                continue;
            }
            if (!stripHeadingPrefix(chunk.text(), stringField(chunk.metadata(), "heading_path")).equals(body)) {
                continue;
            }
            if (!tupleMatches("parent_index", target.metadata(), chunk.metadata())) {
                continue;
            }
            if (!tupleMatches("chunk_index", target.metadata(), chunk.metadata())) {
                continue;
            }
            if (found >= 0) {
                return -1;
            }
            found = i;
        }
        return found;
    }

    /** 两边都存在同名元数据时必须相等；只在一边缺失则不参与比对。 */
    private static boolean tupleMatches(String key, Map<String, Object> target,
            Map<String, Object> other) {
        Object t = target.get(key);
        Object o = other.get(key);
        if (t == null || o == null) {
            return true;
        }
        return String.valueOf(t).equals(String.valueOf(o));
    }

    private static int[] findSpan(String haystack, String needle, int fromIndex) {
        if (needle == null || needle.isEmpty()) {
            return null;
        }
        int exact = haystack.indexOf(needle, fromIndex);
        if (exact >= 0) {
            return new int[] {exact, exact + needle.length()};
        }
        // chunker 折叠空白后与原文对齐不了：用 \s+ 连接词片段重建正则
        String[] tokens = needle.strip().split("\\s+");
        StringBuilder regex = new StringBuilder();
        for (String token : tokens) {
            String escaped = java.util.regex.Pattern.quote(token);
            if (regex.length() == 0) {
                regex.append(escaped);
            } else {
                regex.append("\\s+").append(escaped);
            }
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(regex.toString()).matcher(haystack);
        matcher.region(fromIndex, haystack.length());
        if (matcher.find()) {
            return new int[] {matcher.start(), matcher.end()};
        }
        return null;
    }

    private record EditResult(String docId, int chunkCount, int parentCount, String signature) {
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("序列化失败: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> receipt(EditResult result, String pointId, String action) {
        Map<String, Object> receipt = new java.util.LinkedHashMap<>();
        receipt.put("documentId", result.docId());
        receipt.put("pointId", pointId);
        if (action != null) {
            receipt.put("action", action);
        }
        receipt.put("chunkCount", result.chunkCount());
        receipt.put("parentCount", result.parentCount());
        if (result.signature() != null) {
            receipt.put("chunkingSignature", result.signature());
        } else {
            receipt.put("note", "删除后文档为空，已整篇删除（等价于 rag_delete）");
        }
        receipt.put("note", result.signature() != null
                ? "chunk 已回写归档并整篇重切；可用 rag_search 验证"
                : "删除后文档为空，已整篇删除（等价于 rag_delete）");
        return receipt;
    }

    private static String stringField(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value instanceof String string && !string.isBlank() ? string : null;
    }

    private static String snippet(String text) {
        String stripped = text == null ? "" : text.strip().replaceAll("\\s+", " ");
        return stripped.length() <= 120 ? stripped : stripped.substring(0, 120) + "…";
    }
}
