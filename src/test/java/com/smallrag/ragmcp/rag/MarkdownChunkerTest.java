package com.smallrag.ragmcp.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author zjk
 */
class MarkdownChunkerTest {

    @Test
    void splitsByHeadingsAndTracksHeadingPath() {
        String markdown = """
                # 指南

                第一段内容。

                ## 安装

                安装说明。

                ### Linux

                使用 apt 安装。
                """;

        List<TextChunk> chunks = MarkdownChunker.chunk(markdown, 500, 0, " > ", Map.of());

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(2).text()).contains("使用 apt 安装");
        assertThat(chunks.get(2).metadata())
                .containsEntry("heading", "Linux")
                .containsEntry("heading_level", 3)
                .containsEntry("heading_path", "指南 > 安装 > Linux")
                .containsEntry("content_format", "markdown");
    }

    @Test
    void keepsFencedCodeBlockIntact() {
        String markdown = """
                # 示例

                ```java
                public class Demo {
                    void hello() {
                        System.out.println("hi");
                    }
                }
                ```

                代码之后。
                """;

        List<TextChunk> chunks = MarkdownChunker.chunk(markdown, 80, 0, " > ", Map.of());

        assertThat(chunks).anySatisfy(chunk -> {
            if (chunk.text().contains("public class Demo")) {
                assertThat(chunk.text()).contains("```java").contains("```");
            }
        });
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.text().length()).isLessThanOrEqualTo(80));
    }

    @Test
    void keepsTableIntact() {
        String markdown = """
                # 数据

                | 名称 | 值 |
                | --- | --- |
                | alpha | 1 |
                | beta | 2 |
                """;

        List<TextChunk> chunks = MarkdownChunker.chunk(markdown, 500, 0, " > ", Map.of());

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text())
                .contains("| 名称 | 值 |")
                .contains("| alpha | 1 |")
                .contains("| beta | 2 |");
    }

    @Test
    void extractsFrontMatterMetadataAndExcludesItFromText() {
        String markdown = """
                ---
                title: 测试笔记
                tags: [rag, markdown]
                aliases:
                  - 知识库
                ---
                # 标题

                正文内容。
                """;

        List<TextChunk> chunks = MarkdownChunker.chunk(markdown, 500, 0, " > ", Map.of());

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.metadata())
                    .containsEntry("frontmatter_title", "测试笔记")
                    .containsEntry("frontmatter_tags", "rag, markdown")
                    .containsEntry("frontmatter_aliases", "知识库");
            assertThat(chunk.text()).doesNotContain("title: 测试笔记");
        });
    }

    @Test
    void recognizesObsidianCalloutAndWikilinks() {
        String markdown = """
                # 笔记

                > [!note] 提示
                > 参见 [[另一篇笔记|别名]]。
                """;

        List<TextChunk> chunks = MarkdownChunker.chunk(markdown, 500, 0, " > ", Map.of());

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).metadata())
                .containsEntry("markdown_block_types", "heading,callout")
                .containsEntry("wikilinks", "另一篇笔记");
        assertThat(chunks.get(0).text()).contains("[[另一篇笔记|别名]]");
    }

    @Test
    void splitsOversizedChineseParagraphWithoutExceedingMax() {
        String markdown = "# 长文\n\n" + "这是一段中文内容，用于测试切分。".repeat(80);

        List<TextChunk> chunks = MarkdownChunker.chunk(markdown, 120, 20, " > ", Map.of());

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.text()).isNotBlank();
            assertThat(chunk.text().length()).isLessThanOrEqualTo(120);
        });
    }

    @Test
    void blankInputReturnsEmptyList() {
        assertThat(MarkdownChunker.chunk("  \n ", 100, 0, " > ", Map.of())).isEmpty();
        assertThat(MarkdownChunker.chunk(null, 100, 0, " > ", Map.of())).isEmpty();
    }
}
