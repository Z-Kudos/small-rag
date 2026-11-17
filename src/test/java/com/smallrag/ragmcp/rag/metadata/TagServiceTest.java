package com.smallrag.ragmcp.rag.metadata;

import com.smallrag.ragmcp.rag.RagService;
import com.smallrag.ragmcp.rag.archive.ArchivedDocument;
import com.smallrag.ragmcp.rag.archive.ArchivedDocumentSummary;
import com.smallrag.ragmcp.rag.archive.DocumentArchive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TagServiceTest {

    private RagService ragService;
    private DocumentArchive archive;
    private TagService service;

    @BeforeEach
    void setUp() {
        ragService = mock(RagService.class);
        archive = mock(DocumentArchive.class);
        service = new TagService(ragService, archive);
        when(ragService.update(anyString(), anyString(), any()))
                .thenReturn(new RagService.IngestResult("doc-1", 3, 1));
    }

    @Test
    void setTagsMergesExistingMetadataAndReindexes() {
        when(archive.find("doc-1")).thenReturn(Optional.of(new ArchivedDocument(
                "doc-1", "原文", Map.of("source", "manual", "tags", "旧标签"), null, null)));

        service.setTags("doc-1", "架构,部署", "运维");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(ragService).update(anyString(), anyString(), metadata.capture());
        assertThat(metadata.getValue())
                .containsEntry("source", "manual")
                .containsEntry("tags", "架构,部署")
                .containsEntry("category", "运维");
    }

    @Test
    void emptyTagsAndCategoryClearThem() {
        when(archive.find("doc-1")).thenReturn(Optional.of(new ArchivedDocument(
                "doc-1", "原文", Map.of("tags", "旧标签", "category", "旧分类"), null, null)));

        service.setTags("doc-1", "", "");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(ragService).update(anyString(), anyString(), metadata.capture());
        assertThat(metadata.getValue().get("tags")).isEmpty();
        assertThat(metadata.getValue()).doesNotContainKey("category");
    }

    @Test
    void listTagsCountsTagsAndCategoriesFromSummaries() {
        when(archive.listSummaries()).thenReturn(List.of(
                new ArchivedDocumentSummary("a", Map.of("tags", "rag,检索", "category", "技术"), null, null, 1),
                new ArchivedDocumentSummary("b", Map.of("frontmatter_tags", "rag", "category", "技术"), null, null, 1),
                new ArchivedDocumentSummary("c", Map.of(), null, null, 1)));

        Map<String, Object> result = service.listTags();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tags = (List<Map<String, Object>>) result.get("tags");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> categories = (List<Map<String, Object>>) result.get("categories");
        assertThat(tags).anySatisfy(item -> {
            assertThat(item.get("name")).isEqualTo("rag");
            assertThat(item.get("count")).isEqualTo(2);
        });
        assertThat(categories).containsExactly(Map.of("name", "技术", "count", 2));
    }
}
