package com.smallrag.ragmcp.rag.metadata;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentTagsTest {

    @Test
    void parsesCommaSeparatedTagsAndDeduplicates() {
        assertThat(DocumentTags.parseTags(" 架构, 部署，架构 ,,运维 "))
                .containsExactly("架构", "部署", "运维");
        assertThat(DocumentTags.parseTags(null)).isEmpty();
    }

    @Test
    void normalizesMergesFrontmatterTagsAndCategory() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(DocumentTags.FIELD_TAGS, "rag, 检索");
        metadata.put(DocumentTags.FIELD_FRONTMATTER_TAGS, "检索, 架构");
        metadata.put(DocumentTags.FIELD_CATEGORY, " 技术 ");

        DocumentTags.normalize(metadata);

        assertThat(metadata.get(DocumentTags.FIELD_TAGS))
                .isEqualTo(List.of("rag", "检索", "架构"));
        assertThat(metadata.get(DocumentTags.FIELD_CATEGORY)).isEqualTo("技术");
    }

    @Test
    void removesEmptyTagsAndCategory() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(DocumentTags.FIELD_TAGS, "  ");
        metadata.put(DocumentTags.FIELD_CATEGORY, "");

        DocumentTags.normalize(metadata);

        assertThat(metadata).doesNotContainKeys(DocumentTags.FIELD_TAGS, DocumentTags.FIELD_CATEGORY);
    }

    @Test
    void matchesAnyTagAndCaseInsensitiveCategory() {
        Map<String, Object> metadata = Map.of(
                DocumentTags.FIELD_TAGS, List.of("rag", "架构"),
                DocumentTags.FIELD_CATEGORY, "技术");

        assertThat(DocumentTags.matches(metadata, List.of("架构"), null)).isTrue();
        assertThat(DocumentTags.matches(metadata, List.of("不存在"), null)).isFalse();
        assertThat(DocumentTags.matches(metadata, null, "技术")).isTrue();
        assertThat(DocumentTags.matches(metadata, null, "技术 ")).isTrue();
        assertThat(DocumentTags.matches(metadata, null, "其他")).isFalse();
    }
}
