package com.smallrag.ragmcp.mcp;

import com.smallrag.ragmcp.rag.RagService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * @author zjk
 */
class RagMcpToolsTest {

    private final RagService ragService = mock(RagService.class);
    private final RagMcpTools tools = new RagMcpTools(ragService, new ObjectMapper());

    @Test
    void rebuildRequiresExplicitConfirmation() {
        assertThat(tools.ragRebuild(false)).contains("confirm=true");
        verifyNoInteractions(ragService);
    }

    @Test
    void rebuildDelegatesAndReturnsStatistics() {
        when(ragService.rebuild()).thenReturn(new RagService.RebuildResult(2, 2, 0, false));

        assertThat(tools.ragRebuild(true))
                .contains("\"total\":2")
                .contains("\"succeeded\":2")
                .contains("\"aborted\":false");
    }

    @Test
    void reindexWithoutDocumentIdRebuildsAll() {
        when(ragService.reindexAll()).thenReturn(new RagService.RebuildResult(3, 3, 0, false));

        assertThat(tools.ragReindex(null)).contains("\"total\":3");
        verify(ragService).reindexAll();
    }

    @Test
    void reindexWithDocumentIdRebuildsSingleDocument() {
        when(ragService.reindexDocument("doc-1")).thenReturn(new RagService.IngestResult("doc-1", 4, 1));

        assertThat(tools.ragReindex("doc-1"))
                .contains("\"documentId\":\"doc-1\"")
                .contains("\"chunkCount\":4");
    }

    @Test
    void updateReturnsIngestResult() {
        when(ragService.update("doc-1", "new text", Map.of("source", "faq")))
                .thenReturn(new RagService.IngestResult("doc-1", 2, 1));

        assertThat(tools.ragUpdate("doc-1", "new text", Map.of("source", "faq")))
                .contains("\"chunkCount\":2")
                .contains("\"parentCount\":1");
    }

    @Test
    void deleteReturnsDeletedFlag() {
        assertThat(tools.ragDelete("doc-1"))
                .contains("\"documentId\":\"doc-1\"")
                .contains("\"deleted\":true");
        verify(ragService).delete("doc-1");
    }

    @Test
    void listReturnsDocumentPage() {
        RagService.DocumentPage page = new RagService.DocumentPage(
                1, 0, 20,
                List.of(new RagService.DocumentSummary("doc-1", Map.of("source", "faq"), "sig", "t", 12)));
        when(ragService.listDocuments(20, 0)).thenReturn(page);

        assertThat(tools.ragList(20, 0))
                .contains("\"total\":1")
                .contains("\"documentId\":\"doc-1\"")
                .contains("\"textLength\":12");
    }
}
