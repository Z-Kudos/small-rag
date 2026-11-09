package com.smallrag.ragmcp.rag.sources;

import com.smallrag.ragmcp.config.DistributedProperties;
import com.smallrag.ragmcp.config.RagProperties;
import com.smallrag.ragmcp.distributed.LocalDistributedLock;
import com.smallrag.ragmcp.rag.IndexActivityGuard;
import com.smallrag.ragmcp.rag.RagService;
import com.smallrag.ragmcp.rag.archive.DocumentArchive;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author zjk
 */
class SourceSyncServiceTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    private RagProperties properties;
    private RagService ragService;
    private IndexActivityGuard guard;
    private SourcesLeaderElection leaderElection;
    private SourceSyncService service;
    private Path notes;
    private Path wiki;

    @BeforeEach
    void setUp() throws IOException {
        notes = Files.createDirectories(tempDir.resolve("notes"));
        wiki = Files.createDirectories(tempDir.resolve("wiki"));
        properties = new RagProperties();
        properties.setArchiveDir(tempDir.resolve("archive").toString());
        properties.getSources().setEnabled(true);
        properties.getSources().setDirectories(notes + "," + wiki);
        properties.getSources().setScanOnStartup(false);
        properties.getSources().setScanIntervalSeconds(0);

        ragService = mock(RagService.class);
        when(ragService.upsert(anyString(), anyString(), anyMap()))
                .thenAnswer(invocation -> new RagService.IngestResult(
                        invocation.getArgument(0), 1, 0));
        guard = new IndexActivityGuard();
        leaderElection = new SourcesLeaderElection(new LocalDistributedLock(), new DistributedProperties());
        leaderElection.tick();
        service = new SourceSyncService(ragService,
                new DocumentArchive(objectMapper, properties), properties,
                new SourceStateStore(objectMapper, properties), guard, leaderElection);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
        leaderElection.shutdown();
    }

    @Test
    void importsMarkdownFromMultipleDirectories() throws Exception {
        write(notes, "a.md", "AAA");
        write(notes, "b.txt", "not markdown");
        write(notes, "node_modules/c.md", "excluded");
        write(notes, ".obsidian/d.md", "hidden");
        write(notes, ".hidden.md", "hidden file");
        write(notes, "sub/e.md", "EEE");
        write(wiki, "f.markdown", "FFF");
        write(wiki, "g.MD", "GGG");

        SourceSyncService.Summary summary = service.sync(false);

        assertThat(summary.discovered()).isEqualTo(4);
        assertThat(summary.indexed()).isEqualTo(4);
        assertThat(summary.failed()).isZero();
        assertThat(summary.truncated()).isFalse();
        verify(ragService, times(4)).upsert(anyString(), anyString(), anyMap());

        ArgumentCaptor<Map<String, String>> metadata = mapCaptor();
        verify(ragService).upsert(eq(SourceSyncService.documentIdFor("notes", "a.md")),
                eq("AAA"), metadata.capture());
        assertThat(metadata.getValue())
                .containsEntry(SourceSyncService.META_SOURCE_FILE, "a.md")
                .containsEntry(SourceSyncService.META_SOURCE_ROOT, "notes")
                .containsEntry(SourceSyncService.META_SOURCE_ROOT_PATH, notes.toString())
                .containsEntry("source", "notes/a.md")
                .containsKey(SourceSyncService.META_SOURCE_SHA256);
    }

    @Test
    void incrementalRunSkipsUnchangedAndReindexesChangedFiles() throws Exception {
        Path file = write(notes, "a.md", "AAA");
        assertThat(service.sync(false).indexed()).isEqualTo(1);
        assertThat(service.sync(false).skipped()).isEqualTo(1);
        verify(ragService, times(1)).upsert(anyString(), anyString(), anyMap());

        Files.writeString(file, "changed", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file, FileTime.fromMillis(
                Files.getLastModifiedTime(file).toMillis() + 2_000));
        SourceSyncService.Summary updated = service.sync(false);
        assertThat(updated.updated()).isEqualTo(1);
        verify(ragService, times(2)).upsert(anyString(), anyString(), anyMap());

        // 只改 mtime、内容不变：SHA-256 命中后跳过，避免无谓 Embedding
        Files.setLastModifiedTime(file, FileTime.fromMillis(
                Files.getLastModifiedTime(file).toMillis() + 2_000));
        assertThat(service.sync(false).skipped()).isEqualTo(1);
        verify(ragService, times(2)).upsert(anyString(), anyString(), anyMap());

        assertThat(service.sync(true).updated()).isEqualTo(1);
        verify(ragService, times(3)).upsert(anyString(), anyString(), anyMap());
    }

    @Test
    void deleteMissingOnlyRemovesFilesUnderScannedRoots() throws Exception {
        write(notes, "a.md", "AAA");
        write(notes, "b.md", "BBB");
        assertThat(service.sync(false).indexed()).isEqualTo(2);

        Files.delete(notes.resolve("a.md"));
        assertThat(service.sync(false).deleted()).isZero();
        verify(ragService, never()).delete(anyString());

        properties.getSources().setDeleteMissing(true);
        SourceSyncService.Summary summary = service.sync(false);
        assertThat(summary.deleted()).isEqualTo(1);
        verify(ragService).delete(SourceSyncService.documentIdFor("notes", "a.md"));
        verify(ragService, never()).delete(SourceSyncService.documentIdFor("notes", "b.md"));
    }

    @Test
    void skipsFilesLargerThanConfiguredLimit() throws Exception {
        properties.getSources().setMaxFileBytes(4);
        write(notes, "a.md", "12345");

        SourceSyncService.Summary summary = service.sync(false);

        assertThat(summary.indexed()).isZero();
        assertThat(summary.skipped()).isEqualTo(1);
        verify(ragService, never()).upsert(anyString(), anyString(), anyMap());
    }

    @Test
    void maxFilesPerScanLimitsChangedDocumentsPerRun() throws Exception {
        properties.getSources().setMaxFilesPerScan(1);
        write(notes, "a.md", "AAA");
        write(notes, "b.md", "BBB");
        write(notes, "c.md", "CCC");

        SourceSyncService.Summary first = service.sync(false);
        assertThat(first.indexed()).isEqualTo(1);
        assertThat(first.truncated()).isTrue();

        SourceSyncService.Summary second = service.sync(false);
        assertThat(second.indexed()).isEqualTo(1);
        assertThat(second.skipped()).isEqualTo(1);

        SourceSyncService.Summary third = service.sync(false);
        assertThat(third.indexed()).isEqualTo(1);
        assertThat(third.truncated()).isFalse();
        verify(ragService, times(3)).upsert(anyString(), anyString(), anyMap());
    }

    @Test
    void rejectsWhenReindexActivityIsRunning() {
        guard.tryAcquire(IndexActivityGuard.reindex("task-1"));
        try {
            assertThatThrownBy(() -> service.sync(false))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("其他索引任务");
        } finally {
            guard.release(IndexActivityGuard.reindex("task-1"));
        }
    }

    @Test
    void rebuildsIncrementalStateFromArchiveWhenStateFileIsMissing() throws Exception {
        Path file = write(notes, "a.md", "AAA");
        long size = Files.size(file);
        long modified = Files.getLastModifiedTime(file).toMillis();
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest("AAA".getBytes(StandardCharsets.UTF_8)));
        String root = "notes";
        String rootPath = notes.toString();
        String key = root + "::a.md";
        String documentId = SourceSyncService.documentIdFor(root, "a.md");
        Map<String, String> metadata = Map.of(
                SourceSyncService.META_SOURCE_KEY, key,
                SourceSyncService.META_SOURCE_ROOT, root,
                SourceSyncService.META_SOURCE_ROOT_PATH, rootPath,
                SourceSyncService.META_SOURCE_FILE, "a.md",
                SourceSyncService.META_SOURCE_SIZE, Long.toString(size),
                SourceSyncService.META_SOURCE_MTIME, Long.toString(modified),
                SourceSyncService.META_SOURCE_SHA256, sha256);
        new DocumentArchive(objectMapper, properties).save(documentId, "AAA", metadata, null);

        SourceSyncService.Summary summary = service.sync(false);

        assertThat(summary.skipped()).isEqualTo(1);
        assertThat(summary.indexed()).isZero();
        verify(ragService, never()).upsert(anyString(), anyString(), anyMap());
    }

    @Test
    void usesExplicitRootLabelForDocumentId() throws Exception {
        properties.getSources().setDirectories("knowledge=" + notes);
        write(notes, "a.md", "AAA");

        service.sync(false);

        verify(ragService).upsert(
                eq(SourceSyncService.documentIdFor("knowledge", "a.md")),
                eq("AAA"), anyMap());
    }

    @Test
    void deleteOrphanRootsRemovesDocumentsFromRemovedLabels() throws Exception {
        write(notes, "a.md", "AAA");
        service.sync(false);
        String documentId = SourceSyncService.documentIdFor("notes", "a.md");

        properties.getSources().setDirectories("wiki=" + wiki);
        properties.getSources().setDeleteOrphanRoots(true);
        SourceSyncService.Summary summary = service.sync(false);

        assertThat(summary.deleted()).isEqualTo(1);
        verify(ragService).delete(documentId);
    }

    @Test
    void manualDeleteIsNotReimportedUntilContentChanges() throws Exception {
        Path file = write(notes, "a.md", "AAA");
        service.sync(false);
        String documentId = SourceSyncService.documentIdFor("notes", "a.md");
        verify(ragService).upsert(eq(documentId), eq("AAA"), anyMap());

        service.removeFromState(documentId);
        SourceSyncService.Summary afterDelete = service.sync(false);
        assertThat(afterDelete.indexed()).isZero();
        assertThat(afterDelete.updated()).isZero();
        verify(ragService, times(1)).upsert(eq(documentId), anyString(), anyMap());

        Files.writeString(file, "changed", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file, FileTime.fromMillis(
                Files.getLastModifiedTime(file).toMillis() + 2_000));
        SourceSyncService.Summary afterChange = service.sync(false);
        assertThat(afterChange.indexed()).isEqualTo(1);
        verify(ragService, times(2)).upsert(eq(documentId), anyString(), anyMap());
    }

    @Test
    void syncAsyncRunsInBackground() throws Exception {
        write(notes, "a.md", "AAA");

        SourceSyncService.Accepted accepted = service.syncAsync(false);

        assertThat(accepted.accepted()).isTrue();
        SourceSyncService.Status status = awaitLastRun(5);
        assertThat(status.lastRun().indexed()).isEqualTo(1);
        assertThat(status.lastTrigger()).isEqualTo("手动");
    }

    @Test
    void watcherTriggersScanOnFileChanges() throws Exception {
        properties.getSources().setScanOnStartup(false);
        properties.getSources().setScanIntervalSeconds(0);
        properties.getSources().setWatchEnabled(true);
        properties.getSources().setWatchDebounceSeconds(1);
        service.onApplicationReady();

        write(notes, "a.md", "AAA");

        SourceSyncService.Status status = awaitLastRun(10);
        assertThat(status.lastRun().indexed()).isEqualTo(1);
        assertThat(status.lastTrigger()).isEqualTo("文件变化");
    }

    private SourceSyncService.Status awaitLastRun(int seconds) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            SourceSyncService.Status status = service.status();
            if (status.lastRun() != null) {
                return status;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("目录导入未在 " + seconds + " 秒内完成");
    }

    private Path write(Path root, String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<Map<String, String>> mapCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
    }
}
