package com.smallrag.ragmcp.rag.graph;

import com.smallrag.ragmcp.rag.vectorstore.ChunkSummary;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ChunkGraph} 的单元测试：建图、子图扩展、种子解析。
 *
 * @author zjk
 */
class ChunkGraphTest {

    private static ChunkSummary chunk(String pointId, String docId, String chunkType,
            String parentId, String parentIndex, String chunkIndex, String text,
            String sourcePath, List<String> links) {
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("doc_id", docId);
        metadata.put("chunk_type", chunkType);
        if (parentId != null) {
            metadata.put("parent_id", parentId);
        }
        if (parentIndex != null) {
            metadata.put("parent_index", parentIndex);
        }
        if (chunkIndex != null) {
            metadata.put("chunk_index", chunkIndex);
        }
        metadata.put("source_path", sourcePath);
        if (links != null) {
            metadata.put("links", links);
        }
        return new ChunkSummary(pointId, text, metadata);
    }

    private static List<ChunkSummary> sample() {
        UUID parentPoint = com.smallrag.ragmcp.rag.vectorstore.PointIds.parentPointUuid("docA#p0");
        UUID child1 = UUID.randomUUID();
        UUID child2 = UUID.randomUUID();
        return List.of(
                chunk(parentPoint.toString(), "docA", "parent", "docA#p0", "0", null,
                        "父块正文", "/data/notes/a.md", null),
                chunk(child1.toString(), "docA", "child", "docA#p0", "0", "0",
                        "子块一，参见 [[b.md]]", "/data/notes/a.md", List.of("b.md")),
                chunk(child2.toString(), "docA", "child", "docA#p0", "0", "1",
                        "子块二", "/data/notes/a.md", null),
                chunk(UUID.randomUUID().toString(), "docB", "chunk", null, null, "0",
                        "另一篇文档", "/data/notes/b.md", null));
    }

    @Test
    void buildsDocumentChunkSectionAndParentEdges() {
        ChunkGraph.BuildResult result = ChunkGraph.build(sample());

        assertThat(result.nodes()).extracting(ChunkGraph.GraphNode::type)
                .contains("document", "chunk");
        assertThat(result.edges()).extracting(ChunkGraph.GraphEdge::type)
                .contains("contains", "parent_of");
        assertThat(result.warnings()).isEmpty();

        // parent → child edges reference the deterministic parent point id
        String parentPoint = com.smallrag.ragmcp.rag.vectorstore.PointIds.parentPointUuid("docA#p0").toString();
        assertThat(result.edges().stream()
                .filter(edge -> "parent_of".equals(edge.type()))
                .allMatch(edge -> edge.from().equals(parentPoint))).isTrue();
    }

    @Test
    void emitsLinksToEdgesAcrossDocuments() {
        ChunkGraph.BuildResult result = ChunkGraph.build(sample());

        assertThat(result.edges()).filteredOn(edge -> "links_to".equals(edge.type()))
                .hasSize(1);
        assertThat(result.edges().stream()
                .filter(edge -> "links_to".equals(edge.type()))
                .findFirst().orElseThrow().to()).isEqualTo("document:docB");
    }

    @Test
    void resolveSeedFindsDocumentChunkAndSection() {
        ChunkGraph.BuildResult result = ChunkGraph.build(sample());

        assertThat(ChunkGraph.resolveSeed(result, "docA")).isEqualTo("document:docA");
        assertThat(ChunkGraph.resolveSeed(result, "doc:docA")).isEqualTo("document:docA");
        Optional<ChunkGraph.BuildResult> expanded = ChunkGraph.expand(result, "docA", 1, 100);
        assertThat(expanded).isPresent();
        assertThat(expanded.get().nodes()).hasSizeGreaterThan(1);
    }

    @Test
    void expandFromSeedNeighborhood() {
        ChunkGraph.BuildResult result = ChunkGraph.build(sample());
        String parentPoint = com.smallrag.ragmcp.rag.vectorstore.PointIds.parentPointUuid("docA#p0").toString();

        Optional<ChunkGraph.BuildResult> expanded = ChunkGraph.expand(result, parentPoint, 1, 100);

        assertThat(expanded).isPresent();
        assertThat(expanded.get().nodes()).extracting(ChunkGraph.GraphNode::id)
                .contains(parentPoint);
    }
}
