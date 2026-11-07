package com.smallrag.ragmcp.mcp;

import com.smallrag.ragmcp.rag.RagService;
import com.smallrag.ragmcp.rag.ReindexTaskService;
import com.smallrag.ragmcp.rag.sources.SourceSyncService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

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
    private final ReindexTaskService reindexTaskService = mock(ReindexTaskService.class);
    private final SourceSyncService sourceSyncService = mock(SourceSyncService.class);
    private final RagMcpTools tools =
            new RagMcpTools(ragService, reindexTaskService, sourceSyncService, new ObjectMapper());

    @Test
    void rebuildRequiresExplicitConfirmation() {
        assertThat(tools.ragRebuild(false)).contains("confirm=true");
        verifyNoInteractions(ragService, reindexTaskService);
    }

    @Test
    void rebuildSubmitsAsyncTask() {
        when(reindexTaskService.submit("rebuild", true)).thenReturn(taskView("task-1", "queued", 0));

        assertThat(tools.ragRebuild(true))
                .contains("\"taskId\":\"task-1\"")
                .contains("\"mode\":\"rebuild\"")
                .contains("\"status\":\"queued\"");
    }

    @Test
    void reindexWithoutDocumentIdSubmitsAsyncTask() {
        when(reindexTaskService.submit("reindex", false)).thenReturn(taskView("task-2", "queued", 0));

        assertThat(tools.ragReindex(null)).contains("\"taskId\":\"task-2\"");
        verify(reindexTaskService).submit("reindex", false);
        verifyNoInteractions(ragService);
    }

    @Test
    void reindexWithDocumentIdRebuildsSingleDocument() {
        when(ragService.reindexDocument("doc-1")).thenReturn(new RagService.IngestResult("doc-1", 4, 1));

        assertThat(tools.ragReindex("doc-1"))
                .contains("\"documentId\":\"doc-1\"")
                .contains("\"chunkCount\":4");
    }

    @Test
    void taskStatusReturnsLatestTask() {
        when(reindexTaskService.status(null)).thenReturn(Optional.of(taskView("task-3", "reindexing", 50)));

        assertThat(tools.ragTaskStatus(null))
                .contains("\"taskId\":\"task-3\"")
                .contains("\"status\":\"reindexing\"")
                .contains("\"percent\":50");
    }

    @Test
    void taskStatusReturnsIdleWhenNoTask() {
        when(reindexTaskService.status(null)).thenReturn(Optional.empty());

        assertThat(tools.ragTaskStatus(null)).contains("\"status\":\"IDLE\"");
    }

    @Test
    void taskCancelDelegates() {
        when(reindexTaskService.cancel("task-4")).thenReturn(new ReindexTaskService.ReindexTaskView(
                "task-4", "reindex", "reindexing", 4, 2, 2, 0, 50,
                "doc-1", "2026-11-06T12:00:00Z", false, true,
                "2026-11-06T11:59:00Z", null, false, null, false, null));

        assertThat(tools.ragTaskCancel("task-4"))
                .contains("\"taskId\":\"task-4\"")
                .contains("\"cancelRequested\":true");
    }

    @Test
    void taskResumeDelegates() {
        when(reindexTaskService.resume("task-5")).thenReturn(
                taskView("task-5", "reindexing", 50));

        assertThat(tools.ragTaskResume("task-5"))
                .contains("\"taskId\":\"task-5\"")
                .contains("\"status\":\"reindexing\"");
    }

    @Test
    void syncSourcesDelegates() {
        when(sourceSyncService.syncAsync(true)).thenReturn(
                new SourceSyncService.Accepted(true, "run-1", "已提交"));

        assertThat(tools.ragSyncSources(true))
                .contains("\"accepted\":true")
                .contains("\"runId\":\"run-1\"");
    }

    @Test
    void syncStatusDelegates() {
        SourceSyncService.Summary summary = new SourceSyncService.Summary(
                "2026-11-07T10:00:00Z", "2026-11-07T10:00:01Z", 1000, 2, 5, 1, 2, 2, 0, 0, false);
        SourceSyncService.Status status = new SourceSyncService.Status(
                true, false, false, List.of("/docs"), 300, "定时", null, summary);
        when(sourceSyncService.status()).thenReturn(status);

        assertThat(tools.ragSyncStatus())
                .contains("\"enabled\":true")
                .contains("\"queued\":false")
                .contains("\"directories\":[\"/docs\"]")
                .contains("\"scanIntervalSeconds\":300")
                .contains("\"lastTrigger\":\"定时\"");
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

    private static ReindexTaskService.ReindexTaskView taskView(String taskId, String status, int percent) {
        return new ReindexTaskService.ReindexTaskView(taskId, "rebuild", status, 4, 2, 2, 0,
                percent, "doc-1", "2026-11-06T12:00:00Z", false, false,
                "2026-11-06T11:59:00Z", null, false, null, false, null);
    }
}
