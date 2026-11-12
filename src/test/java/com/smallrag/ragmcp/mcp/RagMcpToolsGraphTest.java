package com.smallrag.ragmcp.mcp;

import com.smallrag.ragmcp.rag.RagService;
import com.smallrag.ragmcp.rag.edit.ChunkEditService;
import com.smallrag.ragmcp.rag.reindex.ReindexTaskService;
import com.smallrag.ragmcp.rag.sources.SourceSyncService;
import com.smallrag.ragmcp.rag.vectorstore.ChunkCatalog;
import com.smallrag.ragmcp.rag.vectorstore.ChunkSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 图查询与 Agent 编辑 MCP 工具的单元测试（mock 目录与子服务）。
 *
 * @author zjk
 */
class RagMcpToolsGraphTest {

    private static final String PARENT_ID = "doc-1#p0";
    private static final String PARENT_POINT =
            com.smallrag.ragmcp.rag.vectorstore.PointIds.parentPointUuid(PARENT_ID).toString();

    private RagService ragService;
    private ChunkEditService chunkEditService;
    private ChunkCatalog chunkCatalog;
    private RagMcpTools tools;

    @BeforeEach
    void setUp() {
        ragService = mock(RagService.class);
        chunkEditService = mock(ChunkEditService.class);
        chunkCatalog = mock(ChunkCatalog.class);
        when(chunkCatalog.listChunks(null)).thenReturn(sampleChunks());
        when(chunkCatalog.listChunks("doc-1")).thenReturn(sampleChunks().stream()
                .filter(chunk -> "doc-1".equals(chunk.metadata().get("doc_id"))).toList());
        tools = new RagMcpTools(ragService,
                mock(ReindexTaskService.class),
                mock(SourceSyncService.class),
                chunkEditService,
                chunkCatalog,
                new ObjectMapper());
    }

    private static List<ChunkSummary> sampleChunks() {
        String p = PARENT_POINT;
        return List.of(
                new ChunkSummary(p, "父块正文内容在此", Map.of(
                        "doc_id", "doc-1",
                        "chunk_type", "parent",
                        "parent_id", PARENT_ID,
                        "parent_index", 0,
                        "heading_path", "指南 > 安装",
                        "source_path", "/data/sources/docs/a.md",
                        "links", List.of("b.md"))),
                new ChunkSummary(paul(p, "child-1"), "子块一内容", Map.of(
                        "doc_id", "doc-1",
                        "chunk_type", "child",
                        "parent_id", PARENT_ID,
                        "parent_index", 0,
                        "chunk_index", 0,
                        "heading_path", "指南 > 安装",
                        "source_path", "/data/sources/docs/a.md")),
                new ChunkSummary(paul(p, "child-2"), "子块二内容", Map.of(
                        "doc_id", "doc-1",
                        "chunk_type", "child",
                        "parent_id", PARENT_ID,
                        "parent_index", 0,
                        "chunk_index", 1,
                        "heading_path", "指南 > 安装",
                        "source_path", "/data/sources/docs/a.md")),
                new ChunkSummary(UUID.randomUUID().toString(), "另一篇文档的内容", Map.of(
                        "doc_id", "doc-2",
                        "chunk_type", "chunk",
                        "chunk_index", 0,
                        "source_path", "/data/sources/docs/b.md")));
    }

    private static String paul(String parent, String suffix) {
        return UUID.nameUUIDFromBytes(("child:" + parent + ":" + suffix).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    @Test
    void ragChunksListsDocumentChunks() {
        String json = tools.ragChunks("doc-1", null);

        assertThat(json).contains("\"documentId\":\"doc-1\"")
                .contains("\"count\":3")
                .contains(PARENT_POINT);
    }

    @Test
    void ragGraphExpandsFromDocumentSeed() {
        // links_to 距离 seed 为 2（chunk → document），depth=1 只看本层
        String json = tools.ragGraph("doc:doc-1", 2, null);

        assertThat(json).contains("\"edges\"")
                .contains("contains")
                .contains("parent_of")
                .contains("links_to");
    }

    @Test
    void ragGraphExportRespectsNodeLimit() {
        String json = tools.ragGraphExport(3);

        assertThat(json).contains("\"truncated\":true");
    }

    @Test
    void ragGetChunkDelegatesToEditService() {
        when(chunkEditService.getChunk(PARENT_POINT)).thenReturn("{\"pointId\":\"" + PARENT_POINT + "\"}");

        String json = tools.ragGetChunk(PARENT_POINT);

        assertThat(json).contains(PARENT_POINT);
    }

    @Test
    void ragEditChunkSurfacesExpectedTextMismatch() {
        org.mockito.Mockito.doThrow(new IllegalArgumentException(
                        "expectedText 与当前 chunk 内容不一致，请先 rag_get_chunk 确认当前内容"))
                .when(chunkEditService).editChunk(PARENT_POINT, "new", "old");

        String json = tools.ragEditChunk(PARENT_POINT, "new", "old");

        assertThat(json).contains("expectedText 与当前 chunk 内容不一致");
    }

    @Test
    void ragDeleteChunkRequiresConfirm() {
        org.mockito.Mockito.doThrow(new IllegalArgumentException("删除 chunk 需要显式 confirm=true"))
                .when(chunkEditService).deleteChunk(PARENT_POINT, false, null);

        String json = tools.ragDeleteChunk(PARENT_POINT, false, null);

        assertThat(json).contains("confirm=true");
    }
}
