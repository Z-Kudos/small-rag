package com.smallrag.ragmcp.rag.archive;

import com.smallrag.ragmcp.config.RagProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @author zjk
 */
class DocumentArchiveTest {

    @TempDir
    Path tempDir;

    @Test
    void savesListsMarksAndDeletesDocuments() {
        DocumentArchive archive = archive();
        String firstId = "11111111-1111-1111-1111-111111111111";
        String secondId = "22222222-2222-2222-2222-222222222222";

        archive.save(firstId, "第一段原文", Map.of("source", "handbook"), "signature-1");
        archive.save(secondId, "第二段原文", Map.of("source", "faq"), "signature-1");

        assertThat(archive.listAll()).hasSize(2);
        ArchivedDocument first = archive.listAll().stream()
                .filter(document -> document.documentId().equals(firstId))
                .findFirst()
                .orElseThrow();
        assertThat(first.text()).isEqualTo("第一段原文");
        assertThat(first.metadata()).containsEntry("source", "handbook");
        assertThat(first.chunkSignature()).isEqualTo("signature-1");

        archive.markIndexed(firstId, "signature-2");
        ArchivedDocument updated = archive.listAll().stream()
                .filter(document -> document.documentId().equals(firstId))
                .findFirst()
                .orElseThrow();
        assertThat(updated.chunkSignature()).isEqualTo("signature-2");

        archive.delete(firstId);
        assertThat(archive.listAll())
                .singleElement()
                .extracting(ArchivedDocument::documentId)
                .isEqualTo(secondId);
    }

    @Test
    void keepsNullSignatureForPendingDocuments() {
        DocumentArchive archive = archive();
        String documentId = "33333333-3333-3333-3333-333333333333";

        archive.save(documentId, "待建索引的原文", Map.of(), null);

        assertThat(archive.listAll())
                .singleElement()
                .extracting(ArchivedDocument::chunkSignature)
                .isNull();
    }

    @Test
    void findsArchivedDocumentById() {
        DocumentArchive archive = archive();
        String documentId = "44444444-4444-4444-4444-444444444444";
        archive.save(documentId, "可被查找的原文", Map.of("source", "faq"), "signature");

        assertThat(archive.find(documentId))
                .isPresent()
                .get()
                .extracting(ArchivedDocument::text)
                .isEqualTo("可被查找的原文");
        assertThat(archive.find("55555555-5555-5555-5555-555555555555")).isEmpty();
    }

    @Test
    void listSummariesUsesIndexAndSurvivesIndexLoss() throws Exception {
        Path documents = tempDir.resolve("documents");
        DocumentArchive archive = archive();
        String firstId = "66666666-6666-6666-6666-666666666666";
        String secondId = "77777777-7777-7777-7777-777777777777";
        archive.save(firstId, "第一段原文", Map.of("source", "handbook"), "signature-1");
        archive.save(secondId, "第二段原文", Map.of("source", "faq"), "signature-1");

        assertThat(archive.listSummaries()).hasSize(2);
        assertThat(archive.listSummaries()).allSatisfy(summary ->
                assertThat(summary.textLength()).isPositive());

        // 重新打开应直接读取摘要索引（而不是重建）
        DocumentArchive reopenedWithIndex = archive();
        assertThat(reopenedWithIndex.listSummaries()).hasSize(2);

        archive.markIndexed(firstId, "signature-2");
        assertThat(archive.listSummaries()).anySatisfy(summary -> {
            if (summary.documentId().equals(firstId)) {
                assertThat(summary.chunkSignature()).isEqualTo("signature-2");
            }
        });

        // 删掉摘要索引后应能从原文文件重建
        java.nio.file.Files.deleteIfExists(documents.resolve(".archive").resolve("index.json"));
        DocumentArchive reopened = archive();
        assertThat(reopened.listSummaries())
                .extracting(ArchivedDocumentSummary::documentId)
                .containsExactlyInAnyOrder(firstId, secondId);
    }

    @Test
    void rejectsNonUuidDocumentId() {
        assertThatThrownBy(() -> archive().save("../escape", "text", Map.of(), "signature"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非法文档 id");
    }

    private DocumentArchive archive() {
        RagProperties properties = new RagProperties();
        properties.setArchiveDir(tempDir.resolve("documents").toString());
        return new DocumentArchive(new ObjectMapper(), properties);
    }
}
