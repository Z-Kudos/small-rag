package com.smallrag.ragmcp.rag;

import com.smallrag.ragmcp.config.QdrantProperties;
import com.smallrag.ragmcp.rag.archive.ArchivedDocument;
import com.smallrag.ragmcp.rag.archive.ArchivedDocumentSummary;
import com.smallrag.ragmcp.rag.archive.DocumentArchive;
import com.smallrag.ragmcp.rag.chunking.ChunkingSignature;
import com.smallrag.ragmcp.rag.chunking.ChunkingStrategy;
import com.smallrag.ragmcp.config.RagProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * @author zjk
 */
class ChunkingReindexRunnerTest {

    @Test
    void reindexesOnlyDocumentsWithOutdatedSignature() {
        DocumentArchive archive = mock(DocumentArchive.class);
        RagService ragService = mock(RagService.class);
        ReindexTaskService taskService = mock(ReindexTaskService.class);
        RagProperties properties = new RagProperties();
        QdrantProperties qdrantProperties = new QdrantProperties();
        when(archive.isEnabled()).thenReturn(true);

        String currentSignature = ChunkingSignature.of(properties, qdrantProperties.getCollectionName());
        ArchivedDocument outdated = new ArchivedDocument(
                UUID.randomUUID().toString(), "旧策略原文", Map.of("source", "handbook"), null, "t1");
        ArchivedDocument upToDate = new ArchivedDocument(
                UUID.randomUUID().toString(), "新策略原文", Map.of("source", "faq"), currentSignature, "t2");
        when(archive.listSummaries()).thenReturn(List.of(
                ArchivedDocumentSummary.of(outdated),
                ArchivedDocumentSummary.of(upToDate)));
        when(archive.find(outdated.documentId())).thenReturn(Optional.of(outdated));

        new ChunkingReindexRunner(archive, ragService, properties, new QdrantProperties(), taskService).run(mock(ApplicationArguments.class));

        verify(ragService).reindex(outdated.documentId(), "旧策略原文", Map.of("source", "handbook"));
        verify(archive).markIndexed(outdated.documentId(), currentSignature);
        verify(ragService, never()).reindex(eq(upToDate.documentId()), anyString(), anyMap());
    }

    @Test
    void skipsWhenAutomaticRechunkIsDisabled() {
        DocumentArchive archive = mock(DocumentArchive.class);
        RagService ragService = mock(RagService.class);
        ReindexTaskService taskService = mock(ReindexTaskService.class);
        RagProperties properties = new RagProperties();
        properties.getChunking().setAutoRechunk(false);

        new ChunkingReindexRunner(archive, ragService, properties, new QdrantProperties(), taskService).run(mock(ApplicationArguments.class));

        verifyNoInteractions(archive, ragService);
    }

    @Test
    void skipsWhenRecoveredReindexTaskIsPending() {
        DocumentArchive archive = mock(DocumentArchive.class);
        RagService ragService = mock(RagService.class);
        ReindexTaskService taskService = mock(ReindexTaskService.class);
        RagProperties properties = new RagProperties();
        when(taskService.hasRecoveredTask()).thenReturn(true);

        new ChunkingReindexRunner(archive, ragService, properties, new QdrantProperties(), taskService)
                .run(mock(ApplicationArguments.class));

        verifyNoInteractions(archive, ragService);
    }

    @Test
    void skipsWhenArchiveIsUnavailable() {
        DocumentArchive archive = mock(DocumentArchive.class);
        RagService ragService = mock(RagService.class);
        ReindexTaskService taskService = mock(ReindexTaskService.class);
        RagProperties properties = new RagProperties();
        when(archive.isEnabled()).thenReturn(false);

        new ChunkingReindexRunner(archive, ragService, properties, new QdrantProperties(), taskService).run(mock(ApplicationArguments.class));

        verifyNoInteractions(ragService);
    }
}
