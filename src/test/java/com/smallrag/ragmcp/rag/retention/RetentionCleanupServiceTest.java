package com.smallrag.ragmcp.rag.retention;

import com.smallrag.ragmcp.config.RagProperties;
import com.smallrag.ragmcp.rag.IndexActivityGuard;
import com.smallrag.ragmcp.rag.archive.ArchivedDocumentSummary;
import com.smallrag.ragmcp.rag.archive.DocumentArchive;
import com.smallrag.ragmcp.rag.sources.SourceSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetentionCleanupServiceTest {

    private RagProperties properties;
    private VectorStore vectorStore;
    private DocumentArchive archive;
    private SourceSyncService sourceSyncService;
    private IndexActivityGuard activity;
    private RetentionCleanupService service;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        properties.getRetention().setEnabled(true);
        properties.getRetention().setTtlSeconds(3600);
        properties.getRetention().setMaxDocumentsPerRun(0);
        vectorStore = mock(VectorStore.class);
        archive = mock(DocumentArchive.class);
        sourceSyncService = mock(SourceSyncService.class);
        activity = new IndexActivityGuard();
        service = new RetentionCleanupService(properties, vectorStore, archive, sourceSyncService, activity);
    }

    private ArchivedDocumentSummary summary(String id, Instant updatedAt) {
        return new ArchivedDocumentSummary(id, Map.of(), null, updatedAt.toString(), 10);
    }

    @Test
    void dryRunOnlyCountsExpiredDocuments() {
        when(archive.listSummaries()).thenReturn(List.of(
                summary("expired", Instant.now().minus(2, ChronoUnit.HOURS)),
                summary("fresh", Instant.now())));

        RetentionCleanupService.CleanupReport report = service.cleanup(true, null);

        assertThat(report.scanned()).isEqualTo(2);
        assertThat(report.expired()).isEqualTo(1);
        assertThat(report.deleted()).isZero();
        assertThat(report.dryRun()).isTrue();
        verify(vectorStore, never()).delete(any(Filter.Expression.class));
        verify(archive, never()).delete(any());
    }

    @Test
    void actualCleanupDeletesVectorsArchiveAndSourceState() {
        when(archive.listSummaries())
                .thenReturn(List.of(summary("expired", Instant.now().minus(2, ChronoUnit.HOURS))));

        RetentionCleanupService.CleanupReport report = service.cleanup(false, null);

        assertThat(report.expired()).isEqualTo(1);
        assertThat(report.deleted()).isEqualTo(1);
        assertThat(report.failed()).isZero();

        ArgumentCaptor<Filter.Expression> filter = ArgumentCaptor.forClass(Filter.Expression.class);
        verify(vectorStore).delete(filter.capture());
        verify(archive).delete("expired");
        verify(sourceSyncService).removeFromState("expired");
        assertThat(activity.activeOwner()).isEmpty();
    }

    @Test
    void ttlOverrideCanForceNewerDocumentsToExpire() {
        when(archive.listSummaries()).thenReturn(List.of(summary("recent", Instant.now().minusSeconds(10))));

        assertThat(service.cleanup(true, null).expired()).isZero();
        assertThat(service.cleanup(true, 5L).expired()).isEqualTo(1);
    }

    @Test
    void skipsWhenAnotherIndexActivityHoldsTheLock() {
        activity.tryAcquire(IndexActivityGuard.SOURCES);

        RetentionCleanupService.CleanupReport report = service.cleanup(true, null);

        assertThat(report.skipped()).isTrue();
        assertThat(report.scanned()).isZero();
        verify(archive, never()).listSummaries();
    }

    @Test
    void zeroTtlDoesNothing() {
        properties.getRetention().setTtlSeconds(0);

        RetentionCleanupService.CleanupReport report = service.cleanup(false, null);

        assertThat(report.expired()).isZero();
        assertThat(report.message()).contains("TTL 未启用");
        verify(archive, never()).listSummaries();
    }
}
