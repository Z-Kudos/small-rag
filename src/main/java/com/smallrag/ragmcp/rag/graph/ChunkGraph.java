package com.smallrag.ragmcp.rag.graph;

import com.smallrag.ragmcp.rag.vectorstore.ChunkSummary;
import com.smallrag.ragmcp.rag.vectorstore.PointIds;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 从 chunk metadata 构建切片图：节点（document / section / chunk）、
 * 边（contains / section / parent_of / links_to）。
 *
 * <p>图不落库：邻接只在回答一个请求的时间段内构建。边类型：
 * <ul>
 *   <li>{@code contains}：文档 → 其下的 chunk</li>
 *   <li>{@code section}：章节（heading_path 的同一文档内节点）→ 其下的 chunk</li>
 *   <li>{@code parent_of}：父块 chunk point → 子块 chunk point</li>
 *   <li>{@code links_to}：chunk → 链接目标的文档（target=document 节点），
 *       重名跨目录时标记 {@code ambiguous}，见 {@link #warnings}</li>
 * </ul>
 *
 * @author zjk
 */
public final class ChunkGraph {

    /** 节点类型。 */
    public record GraphNode(String id, String type, Map<String, Object> attrs) {
    }

    /** 边。from/to 指向节点 id。 */
    public record GraphEdge(String from, String to, String type) {
    }

    /** 建图结果。{@code warnings} 收集无法决断的 links_to（ambiguous 列表等）。 */
    public record BuildResult(List<GraphNode> nodes, List<GraphEdge> edges, List<String> warnings) {
    }

    private static final int MAX_LABEL = 80;

    private ChunkGraph() {
    }

    /**
     * 构建完整图。
     *
     * @param chunks chunk 目录（顺序不要求，内部排序）
     */
    public static BuildResult build(List<ChunkSummary> chunks) {
        Map<String, GraphNode> nodes = new LinkedHashMap<>();
        List<GraphEdge> edges = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // 1) 节点与 contains/section 边：文档 + 章节 + chunk
        List<ChunkSummary> ordered = new ArrayList<>(chunks);
        ordered.sort(java.util.Comparator
                .comparingInt((ChunkSummary c) -> intField(c.metadata(), "chunk_index").orElse(Integer.MAX_VALUE))
                .thenComparingInt(c -> intField(c.metadata(), "parent_index").orElse(Integer.MAX_VALUE))
                .thenComparing(ChunkSummary::pointId));

        // 文档节点与 source_path → doc_id 映射（links_to 用）
        Map<String, String> pathsByDocId = new LinkedHashMap<>();
        for (ChunkSummary chunk : ordered) {
            String docId = stringField(chunk.metadata(), "doc_id");
            if (docId == null) {
                continue;
            }
            String sourcePath = stringField(chunk.metadata(), "source_path");
            if (sourcePath != null && !sourcePath.isBlank()) {
                pathsByDocId.putIfAbsent(LinkResolver.normalize(LinkResolver.toPosix(sourcePath)), docId);
            }
            putNode(nodes, chunkDocumentNode(docId, chunk.metadata()));
        }

        for (ChunkSummary chunk : ordered) {
            String docId = stringField(chunk.metadata(), "doc_id");
            if (docId == null) {
                continue;
            }
            String chunkType = stringField(chunk.metadata(), "chunk_type");
            String headingPath = stringField(chunk.metadata(), "heading_path");

            putNode(nodes, chunkNode(chunk, chunkType));
            edges.add(new GraphEdge("document:" + docId, chunk.pointId(), "contains"));
            if (headingPath != null && !headingPath.isBlank()) {
                String sectionId = sectionId(docId, headingPath);
                putNode(nodes, sectionNode(sectionId, docId, headingPath, chunk.metadata()));
                edges.add(new GraphEdge(sectionId, chunk.pointId(), "section"));
            }
        }

        // 2) parent_of：父块 point → 子块 point（两端的节点必须都存在）
        for (ChunkSummary chunk : ordered) {
            String parentId = stringField(chunk.metadata(), "parent_id");
            if (parentId == null || parentId.isBlank()) {
                continue;
            }
            String childType = stringField(chunk.metadata(), "chunk_type");
            if (!"child".equals(childType) && !"chunk".equals(childType)) {
                continue;
            }
            String parentPointId = PointIds.parentPointUuid(parentId).toString();
            if (nodes.containsKey(parentPointId)) {
                edges.add(new GraphEdge(parentPointId, chunk.pointId(), "parent_of"));
            }
        }

        // 3) links_to：links 指向的外部文档（零命中/ambiguous 记入 warnings）
        for (ChunkSummary chunk : ordered) {
            List<String> links = linksMeta(chunk.metadata());
            if (links.isEmpty()) {
                continue;
            }
            String currentSource = stringField(chunk.metadata(), "source_path");
            for (String target : links) {
                LinkResolver.Result resolved = LinkResolver.resolve(target, currentSource, pathsByDocId);
                if (!resolved.found()) {
                    continue;
                }
                if (resolved.ambiguous()) {
                    warnings.add("links_ambiguous: chunk=" + chunk.pointId()
                            + " target=" + target + " candidates=" + resolved.docIds());
                    continue;
                }
                edges.add(new GraphEdge(chunk.pointId(), "document:" + resolved.docIds().get(0), "links_to"));
            }
        }

        return new BuildResult(nodes.values().stream().toList(), edges, warnings);
    }

    /**
     * 从 seed 出发扩展至多 depth 跳（无向邻接扩展），最多 maxNodes 个节点。
     *
     * @return 子图；seed 找不到时返回 empty
     */
    public static Optional<BuildResult> expand(BuildResult full, String seed, int depth, int maxNodes) {
        int effectiveDepth = Math.max(0, Math.min(2, depth));
        List<GraphNode> fullNodes = full.nodes();
        Map<String, GraphNode> byId = new LinkedHashMap<>();
        for (GraphNode node : fullNodes) {
            byId.put(node.id(), node);
        }
        String start = resolveSeed(full, seed);
        if (start == null) {
            return Optional.empty();
        }
        Map<String, Set<String>> adjacency = new LinkedHashMap<>();
        for (GraphEdge edge : full.edges()) {
            adjacency.computeIfAbsent(edge.from(), k -> new LinkedHashSet<>()).add(edge.to());
            adjacency.computeIfAbsent(edge.to(), k -> new LinkedHashSet<>()).add(edge.from());
        }
        Set<String> visited = new LinkedHashSet<>();
        Deque<Map.Entry<String, Integer>> queue = new ArrayDeque<>();
        queue.add(Map.entry(start, 0));
        visited.add(start);
        while (!queue.isEmpty() && visited.size() < Math.max(1, maxNodes)) {
            Map.Entry<String, Integer> head = queue.poll();
            int level = head.getValue();
            if (level >= effectiveDepth) {
                continue;
            }
            for (String neighbor : adjacency.getOrDefault(head.getKey(), Set.of())) {
                if (visited.size() >= Math.max(1, maxNodes)) {
                    break;
                }
                if (visited.add(neighbor)) {
                    queue.add(Map.entry(neighbor, level + 1));
                }
            }
        }
        List<GraphNode> nodes = visited.stream().map(byId::get).filter(n -> n != null).toList();
        List<GraphEdge> edges = full.edges().stream()
                .filter(e -> visited.contains(e.from()) && visited.contains(e.to()))
                .toList();
        return Optional.of(new BuildResult(nodes, edges, full.warnings()));
    }

    /** 按 seed 字符串解析出节点 id；找不到返回 null。 */
    public static String resolveSeed(BuildResult full, String seed) {
        if (seed == null || seed.isBlank()) {
            return null;
        }
        String trimmed = seed.strip();
        // doc:<uuid> | chunk:<pointId> | section|<...>
        if (trimmed.startsWith("doc:")) {
            return "document:" + trimmed.substring("doc:".length());
        }
        if (trimmed.startsWith("chunk:")) {
            return trimmed.substring("chunk:".length());
        }
        if (trimmed.startsWith("section|")) {
            return trimmed;
        }
        // 先看是否为已知 chunk id / document id
        for (GraphNode node : full.nodes()) {
            if (node.id().equals(trimmed)) {
                return node.id();
            }
        }
        // 裸 UUID 尝试 document / chunk 两种
        if (full.nodes().stream().anyMatch(n -> n.id().equals("document:" + trimmed))) {
            return "document:" + trimmed;
        }
        // heading_path 模糊匹配 section 节点（含 " > "）
        for (GraphNode node : full.nodes()) {
            if ("section".equals(node.type())
                    && node.id().endsWith("#" + trimmed)) {
                return node.id();
            }
        }
        return null;
    }

    private static GraphNode chunkDocumentNode(String docId, Map<String, Object> metadata) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("doc_id", docId);
        String source = stringField(metadata, "source");
        if (source != null) {
            attrs.put("source", source);
        }
        return new GraphNode("document:" + docId, "document", attrs);
    }

    private static GraphNode sectionNode(String id, String docId, String headingPath,
            Map<String, Object> metadata) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("doc_id", docId);
        attrs.put("heading_path", headingPath);
        Object level = metadata.get("heading_level");
        if (level != null) {
            attrs.put("heading_level", level);
        }
        return new GraphNode(id, "section", attrs);
    }

    private static GraphNode chunkNode(ChunkSummary chunk, String chunkType) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("doc_id", stringField(chunk.metadata(), "doc_id"));
        attrs.put("chunk_type", chunkType == null ? "chunk" : chunkType);
        String headingPath = stringField(chunk.metadata(), "heading_path");
        if (headingPath != null) {
            attrs.put("heading_path", headingPath);
        }
        intField(chunk.metadata(), "chunk_index").ifPresent(index -> attrs.put("chunk_index", index));
        intField(chunk.metadata(), "parent_index").ifPresent(index -> attrs.put("parent_index", index));
        String parentId = stringField(chunk.metadata(), "parent_id");
        if (parentId != null) {
            attrs.put("parent_id", parentId);
        }
        attrs.put("label", snippet(chunk.text()));
        return new GraphNode(chunk.pointId(), "chunk", attrs);
    }

    static String sectionId(String docId, String headingPath) {
        return "section|" + docId + "|" + headingPath;
    }

    private static void putNode(Map<String, GraphNode> nodes, GraphNode node) {
        nodes.putIfAbsent(node.id(), node);
    }

    private static String snippet(String text) {
        if (text == null) {
            return "";
        }
        String stripped = text.strip().replaceAll("\\s+", " ");
        if (stripped.length() <= MAX_LABEL) {
            return stripped;
        }
        return stripped.substring(0, MAX_LABEL) + "…";
    }

    static String stringField(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value instanceof String string && !string.isBlank()) {
            return string;
        }
        return null;
    }

    static Optional<Integer> intField(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value instanceof Number number) {
            return Optional.of(number.intValue());
        }
        if (value instanceof String string) {
            try {
                return Optional.of(Integer.parseInt(string.trim()));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    static List<String> linksMeta(Map<String, Object> metadata) {
        Object value = metadata.get("links");
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    result.add(String.valueOf(item));
                }
            }
            return result;
        }
        if (value instanceof String string && !string.isBlank()) {
            return java.util.Arrays.stream(string.split(","))
                    .map(String::strip).filter(s -> !s.isBlank()).toList();
        }
        return List.of();
    }
}
