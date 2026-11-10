package com.smallrag.ragmcp.rag.chunking;

import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
        assertThat(chunks).allSatisfy(chunk -> assertThat(TextWeights.weight(chunk.text())).isLessThanOrEqualTo(80));
    }

    @Test
    void hashInsideFencedCodeIsNotTreatedAsHeading() {
        String markdown = """
                # 指南

                ```python
                # 这是注释，不是标题
                def main():
                    pass
                ```

                结尾段落。
                """;

        List<TextChunk> chunks = MarkdownChunker.chunk(markdown, 500, 0, " > ", Map.of());

        // 若代码块内的 # 行被误判为标题，heading_path 会变成
        // "指南 > 这是注释，不是标题"；正确行为是全程保持"指南"，
        // 注释行留在正文里，不产生独立 section
        String joined = chunks.stream().map(TextChunk::text).collect(Collectors.joining("\n\n"));
        assertThat(joined).contains("# 这是注释，不是标题").contains("结尾段落。");
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(chunk.metadata()).containsEntry("heading_path", "指南"));
    }

    @Test
    void hashIncludeAndHashTagOutsideHeadingsAreNotHeadings() {
        String markdown = """
                # 指南

                #include <stdio.h>

                正文提到标签 #todo 标记。

                ####### 七个井号不是标题

                仍是正文。
                """;

        List<TextChunk> chunks = MarkdownChunker.chunk(markdown, 500, 0, " > ", Map.of());

        // #include（# 后无空格）、行中 #tag、#######（7 个 #）都不构成标题，
        // 全部落在"指南"一个 section 内
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).contains("#include").contains("#todo").contains("#######");
        assertThat(chunks.get(0).metadata()).containsEntry("heading_path", "指南");
    }

    @Test
    void nestedFenceWithLongerOuterFenceKeepsInnerFenceIntact() {
        // 外层四反引号围栏包裹含三反引号代码的 Markdown 教程片段
        String markdown = """
                # 示例

                ````markdown
                ```python
                # 内层注释
                print("hi")
                ```
                ````

                结尾段落。
                """;

        List<TextChunk> chunks = MarkdownChunker.chunk(markdown, 500, 0, " > ", Map.of());

        // 内层 ``` 不会提前闭合外层 ````：# 内层注释 留在代码块内，
        // heading_path 全程保持"示例"
        String joined = chunks.stream().map(TextChunk::text).collect(Collectors.joining("\n\n"));
        assertThat(joined).contains("```python").contains("# 内层注释").contains("````");
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(chunk.metadata()).containsEntry("heading_path", "示例"));
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
    void semanticChunkingMergesSimilarBlocksAndSplitsOnShift() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            List<float[]> vectors = new ArrayList<>(texts.size());
            for (String text : texts) {
                vectors.add(text.contains("乙") ? new float[] { 0f, 1f } : new float[] { 1f, 0f });
            }
            return vectors;
        });

        List<TextChunk> chunks = MarkdownChunker.chunkSemantic("""
                # 主题

                甲段落一。

                甲段落二。

                乙段落。
                """, 500, 0, " > ", Map.of(), model, 0.9, 0d, 64);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).text()).contains("甲段落一").contains("甲段落二");
        assertThat(chunks.get(1).text()).isEqualTo("乙段落。");
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(chunk.metadata()).containsEntry("heading_path", "主题"));
    }

    @Test
    void semanticChunkingKeepsProtectedBlocksStandalone() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            List<float[]> vectors = new ArrayList<>(texts.size());
            for (String text : texts) {
                vectors.add(new float[] { 1f, 0f });
            }
            return vectors;
        });

        List<TextChunk> chunks = MarkdownChunker.chunkSemantic("""
                # 指南

                甲段落。

                ```java
                int x = 1;
                ```

                乙段落。
                """, 500, 0, " > ", Map.of(), model, 0.9, 0d, 64);

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).text()).isEqualTo("# 指南\n甲段落。");
        assertThat(chunks.get(1).text()).isEqualTo("```java\nint x = 1;\n```");
        assertThat(chunks.get(1).metadata())
                .containsEntry("markdown_block_types", "code")
                .doesNotContainKey("oversized_block");
        assertThat(chunks.get(2).text()).isEqualTo("乙段落。");
    }

    @Test
    void semanticChunkingRefinesOversizedTextBlockBySentences() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(anyList())).thenAnswer(invocation -> {
            List<String> sentences = invocation.getArgument(0);
            List<float[]> vectors = new ArrayList<>(sentences.size());
            for (String sentence : sentences) {
                vectors.add(sentence.contains("乙") ? new float[] { 0f, 1f } : new float[] { 1f, 0f });
            }
            return vectors;
        });

        // 单段超长（12 > 10）：回落到句子级语义切分
        List<TextChunk> chunks = MarkdownChunker.chunkSemantic(
                "甲句一。甲句二。乙句一。", 10, 0, " > ", Map.of(), model, 0.9, 0d, 64);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).text()).isEqualTo("甲句一。甲句二。");
        assertThat(chunks.get(1).text()).isEqualTo("乙句一。");
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(chunk.metadata()).containsEntry("oversized_block", true));
    }

    @Test
    void semanticChunkingDoesNotSplitAfterEnglishAbbreviations() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(anyList())).thenAnswer(invocation -> {
            List<String> sentences = invocation.getArgument(0);
            List<float[]> vectors = new ArrayList<>(sentences.size());
            for (String sentence : sentences) {
                vectors.add(sentence.contains("Smith") ? new float[] { 1f, 0f } : new float[] { 0f, 1f });
            }
            return vectors;
        });

        // 整段超重（27 字符 * 0.25 = 6.75 > 5），回落句子级切分；
        // "Dr." 后的空白不应视为句界
        List<TextChunk> chunks = MarkdownChunker.chunkSemantic(
                "Dr. Smith arrived. He left.", 5, 0, " > ", Map.of(), model, 0.9, 0d, 64);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).text()).isEqualTo("Dr. Smith arrived.");
        assertThat(chunks.get(1).text()).isEqualTo("He left.");
    }

    @Test
    void oversizedTableSlicesRepeatHeaderRow() {
        String markdown = "# 数据\n\n| 名称 | 值 |\n| --- | --- |\n"
                + "| alpha | 1 |\n| beta | 2 |\n| gamma | 3 |";

        List<TextChunk> chunks = MarkdownChunker.chunk(markdown, 12, 0, " > ", Map.of());

        // 表格超长被切分时，每个切片都带表头与分隔行
        assertThat(chunks.size()).isGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> {
            if (chunk.text().contains("|")) {
                assertThat(chunk.text()).startsWith("| 名称 | 值 |\n| --- | --- |");
            }
            assertThat(TextWeights.weight(chunk.text())).isLessThanOrEqualTo(12);
        });
    }

    @Test
    void oversizedFencedCodeSlicesRepeatFence() {
        String markdown = "# 代码\n\n```java\n"
                + "lineOne();\nlineTwo();\nlineThree();\nlineFour();\n"
                + "```\n\n代码之后。";

        List<TextChunk> chunks = MarkdownChunker.chunk(markdown, 10, 0, " > ", Map.of());

        assertThat(chunks.size()).isGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> {
            if (chunk.text().contains("lineOne") || chunk.text().contains("lineThree")) {
                assertThat(chunk.text()).startsWith("```java").endsWith("```");
            }
            assertThat(TextWeights.weight(chunk.text())).isLessThanOrEqualTo(10);
        });
    }

    @Test
    void blankInputReturnsEmptyList() {
        assertThat(MarkdownChunker.chunk("  \n ", 100, 0, " > ", Map.of())).isEmpty();
        assertThat(MarkdownChunker.chunk(null, 100, 0, " > ", Map.of())).isEmpty();
    }
}
