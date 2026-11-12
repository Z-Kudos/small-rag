package com.smallrag.ragmcp.rag.edit;

import com.smallrag.ragmcp.config.QdrantProperties;
import com.smallrag.ragmcp.config.RagProperties;
import com.smallrag.ragmcp.rag.RagService;
import com.smallrag.ragmcp.rag.archive.ArchivedDocument;
import com.smallrag.ragmcp.rag.archive.DocumentArchive;
import com.smallrag.ragmcp.rag.chunking.ChunkingService;
import com.smallrag.ragmcp.rag.chunking.ChunkingStrategy;
import com.smallrag.ragmcp.rag.vectorstore.ChunkCatalog;
import com.smallrag.ragmcp.rag.vectorstore.ChunkSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.embedding.EmbeddingModel;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChunkEditService} 的单元测试：pointId 读取、matchText 校验、编辑与删除回写。
 *
 * @author zjk
 */
class ChunkEditServiceTest {

    @TempDir
    Path tempDir;

    private RagProperties properties;
    private ChunkingService chunkingService;
    private DocumentArchive archive;
    private RagService ragService;
    private ChunkEditService service;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        properties.setArchiveDir(tempDir.resolve("archive").toString());
        properties.getChunking().setStrategy(ChunkingStrategy.FIXED);
        properties.setChunkSize(5);
        properties.setChunkOverlap(0);
        chunkingService = new ChunkingService(properties, mock(EmbeddingModel.class));
        archive = mock(DocumentArchive.class);
        ragService = mock(RagService.class);
        ChunkCatalog catalog = docId -> List.of(
                new ChunkSummary("p-1", "0123456789ABCDEFGHIJ", Map.of(
                        "doc_id", "doc-1",
                        "chunk_type", "chunk",
                        "chunk_index", 0,
                        "chunk_count", 2)),
                new ChunkSummary("p-2", "KLMNOP", Map.of(
                        "doc_id", "doc-1",
                        "chunk_type", "chunk",
                        "chunk_index", 1,
                        "chunk_count", 2)));
        service = new ChunkEditService(catalog, archive, ragService, chunkingService,
                properties, new QdrantProperties(), new ObjectMapper());
    }

    @Test
    void getChunkReturnsJson() {
        String json = service.getChunk("p-2");

        assertThat(json).contains("\"pointId\":\"p-2\"")
                .contains("\"docId\":\"doc-1\"")
                .contains("KLMNOP");
    }

    @Test
    void editChunkRejectsMismatchedExpectedText() {
        assertThatThrownBy(() -> service.editChunk("p-2", "NEW", "WRONG"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expectedText");
    }

    @Test
    void editChunkReplacesSecondChunkAndReindexes() {
        archivedWith("0123456789ABCDEFGHIJKLMNOP");

        String json = service.editChunk("p-2", "XYZ", null);

        assertThat(json).contains("\"documentId\":\"doc-1\"")
                .contains("\"chunkCount\":2");
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(archive).save(anyString(), text.capture(), anyMap(), isNull());
        assertThat(text.getValue()).isEqualTo("0123456789ABCDEFGHIJXYZ");
        verify(ragService).reindex("doc-1", "0123456789ABCDEFGHIJXYZ", Map.of());
    }

    @Test
    void deleteChunkRemovesSecondChunk() {
        archivedWith("0123456789ABCDEFGHIJKLMNOP");

        String json = service.deleteChunk("p-2", true, "KLMNOP");

        assertThat(json).contains("\"documentId\":\"doc-1\"")
                .contains("deleted");
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(archive).save(anyString(), text.capture(), anyMap(), isNull());
        assertThat(text.getValue()).isEqualTo("0123456789ABCDEFGHIJ");
    }

    @Test
    void deleteChunkRequiresConfirmTrue() {
        assertThatThrownBy(() -> service.deleteChunk("p-2", false, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confirm=true");
    }

    @Test
    void unknownPointIdIsRejected() {
        assertThatThrownBy(() -> service.getChunk("no-such-point"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chunk 不存在");
    }

    private void archivedWith(String text) {
        when(archive.find("doc-1")).thenReturn(Optional.of(
                new ArchivedDocument("doc-1", text, Map.of(), "v0.0.5", null)));
        when(ragService.reindex(anyString(), anyString(), any()))
                .thenReturn(new RagService.IngestResult("doc-1", 2, 1));
    }
}
