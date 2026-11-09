package com.smallrag.ragmcp.config;

import com.smallrag.ragmcp.rag.IndexActivityGuard;
import com.smallrag.ragmcp.rag.QueryCircuitBreaker;
import com.smallrag.ragmcp.rag.archive.DocumentArchive;
import com.smallrag.ragmcp.rag.reindex.ReindexTaskService;
import com.smallrag.ragmcp.rag.sources.SourcesLeaderElection;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IndexLockHealthIndicatorTest {

    @Test
    void reportsInstanceLockArchiveAndCircuitState() {
        DistributedProperties properties = new DistributedProperties();
        properties.setEnabled(true);
        properties.setInstanceId("replica-1");
        ArchiveProperties archiveProperties = new ArchiveProperties();
        archiveProperties.setShared(true);
        IndexActivityGuard guard = mock(IndexActivityGuard.class);
        when(guard.owner()).thenReturn(Optional.of(IndexActivityGuard.SOURCES));
        SourcesLeaderElection leaderElection = mock(SourcesLeaderElection.class);
        when(leaderElection.isLeader()).thenReturn(true);
        DocumentArchive archive = mock(DocumentArchive.class);
        when(archive.isEnabled()).thenReturn(true);
        when(archive.isWritable()).thenReturn(true);
        when(archive.directory()).thenReturn(Path.of("/data/documents"));
        ReindexTaskService reindexTaskService = mock(ReindexTaskService.class);
        when(reindexTaskService.stateStoreLocation()).thenReturn("qdrant:small_rag_text_reindex_state");

        QueryCircuitBreaker breaker = new QueryCircuitBreaker(new RagProperties());
        IndexLockHealthIndicator indicator = new IndexLockHealthIndicator(
                properties, archiveProperties, guard, leaderElection, breaker, archive,
                reindexTaskService, new MockEnvironment());

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("instanceId", "replica-1")
                .containsEntry("distributed", true)
                .containsEntry("activeLock", IndexActivityGuard.SOURCES)
                .containsEntry("sourcesLeader", true)
                .containsEntry("queryCircuit", "CLOSED");
        @SuppressWarnings("unchecked")
        Map<String, Object> archiveDetails = (Map<String, Object>) health.getDetails().get("archive");
        assertThat(archiveDetails)
                .containsEntry("enabled", true)
                .containsEntry("writable", true)
                .containsEntry("dir", "/data/documents")
                .containsEntry("shared", true);
        @SuppressWarnings("unchecked")
        Map<String, Object> taskDetails = (Map<String, Object>) health.getDetails().get("reindexTask");
        assertThat(taskDetails)
                .containsEntry("stateStore", "qdrant:small_rag_text_reindex_state")
                .containsEntry("status", "idle");
    }

    @Test
    void reportsReadOnlyArchive() {
        DocumentArchive archive = mock(DocumentArchive.class);
        when(archive.isEnabled()).thenReturn(true);
        when(archive.isWritable()).thenReturn(false);
        when(archive.directory()).thenReturn(Path.of("/readonly"));
        ReindexTaskService reindexTaskService = mock(ReindexTaskService.class);
        when(reindexTaskService.stateStoreLocation()).thenReturn("qdrant:state");
        IndexLockHealthIndicator indicator = new IndexLockHealthIndicator(
                new DistributedProperties(), new ArchiveProperties(),
                mock(IndexActivityGuard.class), mock(SourcesLeaderElection.class),
                new QueryCircuitBreaker(new RagProperties()), archive,
                reindexTaskService, new MockEnvironment());

        @SuppressWarnings("unchecked")
        Map<String, Object> archiveDetails = (Map<String, Object>)
                indicator.health().getDetails().get("archive");

        assertThat(archiveDetails).containsEntry("writable", false);
    }
}
