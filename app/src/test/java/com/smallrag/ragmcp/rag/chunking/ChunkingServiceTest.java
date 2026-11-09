package com.smallrag.ragmcp.rag.chunking;

import com.smallrag.ragmcp.config.RagProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author zjk
 */
class ChunkingServiceTest {

    @Test
    void recursiveStrategyRespectsChunkSize() {
        RagProperties properties = new RagProperties();
        properties.setChunkSize(40);
        properties.setChunkOverlap(5);
        properties.getChunking().setStrategy(ChunkingStrategy.RECURSIVE);

        ChunkingService service = new ChunkingService(properties, mock(EmbeddingModel.class));

        ChunkingService.ChunkingResult result = service.chunk(
                "第一段内容。第二段内容。\n\n第三段内容。第四段内容。", "doc-1");

        assertThat(result.parentCount()).isZero();
        assertThat(result.chunks()).isNotEmpty();
        assertThat(result.chunks()).allSatisfy(chunk -> {
            assertThat(chunk.text().length()).isLessThanOrEqualTo(40);
            assertThat(chunk.metadata()).containsEntry("chunk_type", "chunk");
        });
    }

    @Test
    void semanticStrategySplitsBetweenDissimilarBlocks() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            List<float[]> vectors = new ArrayList<>(texts.size());
            for (String text : texts) {
                vectors.add(text.contains("乙") ? new float[] { 0f, 1f } : new float[] { 1f, 0f });
            }
            return vectors;
        });

        RagProperties properties = new RagProperties();
        properties.getChunking().setStrategy(ChunkingStrategy.SEMANTIC);
        properties.getChunking().getSemantic().setSimilarityThreshold(0.9);
        properties.getChunking().getSemantic().setMinChunkChars(0);
        properties.getChunking().getSemantic().setMaxChunkChars(500);

        ChunkingService service = new ChunkingService(properties, embeddingModel);
        ChunkingService.ChunkingResult result = service.chunk("""
                # 主题

                甲段落内容一。

                甲段落内容二。

                乙段落内容。
                """, "doc-1");

        // 甲两段语义相近合并为一块；乙段落在质心转折处断开
        assertThat(result.chunks()).hasSize(2);
        assertThat(result.chunks().get(0).text())
                .contains("甲段落内容一")
                .contains("甲段落内容二");
        // 不以标题开头的片段正文前置 heading_path
        assertThat(result.chunks().get(1).text()).isEqualTo("主题\n乙段落内容。");
        assertThat(result.chunks()).allSatisfy(chunk -> assertThat(chunk.metadata())
                .containsEntry("chunk_type", "chunk")
                .containsEntry("heading_path", "主题")
                .containsEntry("content_format", "markdown"));
    }

    @Test
    void semanticStrategyKeepsProtectedBlocksStandalone() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            List<float[]> vectors = new ArrayList<>(texts.size());
            for (String text : texts) {
                vectors.add(new float[] { 1f, 0f });
            }
            return vectors;
        });

        RagProperties properties = new RagProperties();
        properties.getChunking().setStrategy(ChunkingStrategy.SEMANTIC);
        properties.getChunking().getSemantic().setSimilarityThreshold(0.9);
        properties.getChunking().getSemantic().setMinChunkChars(0);
        properties.getChunking().getSemantic().setMaxChunkChars(500);

        ChunkingService service = new ChunkingService(properties, embeddingModel);
        ChunkingService.ChunkingResult result = service.chunk("""
                # 指南

                甲段落。

                ```java
                int x = 1;
                ```

                乙段落。
                """, "doc-1");

        // 代码块作为受保护块独立成块，不与文本块合并
        assertThat(result.chunks()).hasSize(3);
        assertThat(result.chunks().get(0).text()).isEqualTo("# 指南\n甲段落。");
        assertThat(result.chunks().get(1).text()).isEqualTo("指南\n```java\nint x = 1;\n```");
        assertThat(result.chunks().get(1).metadata())
                .containsEntry("markdown_block_types", "code")
                .doesNotContainKey("oversized_block");
        assertThat(result.chunks().get(2).text()).isEqualTo("指南\n乙段落。");
    }

    @Test
    void semanticStrategyMergesUndersizedTailBlock() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            List<float[]> vectors = new ArrayList<>(texts.size());
            for (String text : texts) {
                vectors.add(text.startsWith("丙") ? new float[] { 0f, 1f } : new float[] { 1f, 0f });
            }
            return vectors;
        });

        RagProperties properties = new RagProperties();
        properties.getChunking().setStrategy(ChunkingStrategy.SEMANTIC);
        properties.getChunking().getSemantic().setSimilarityThreshold(0.9);
        properties.getChunking().getSemantic().setMinChunkChars(50);
        properties.getChunking().getSemantic().setMaxChunkChars(500);

        ChunkingService service = new ChunkingService(properties, embeddingModel);
        ChunkingService.ChunkingResult result = service.chunk(
                "甲".repeat(60) + "。\n\n" + "乙".repeat(60) + "。\n\n" + "丙句。", "doc-1");

        // 末块低于 minChars，回收合并到前一块，避免碎块
        assertThat(result.chunks()).hasSize(1);
        assertThat(result.chunks().get(0).text())
                .contains("丙句。")
                .contains("\n\n");
        assertThat(result.chunks().get(0).metadata()).containsEntry("chunk_type", "chunk");
    }

    @Test
    void semanticStrategySupportsPercentileBreakpoints() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            List<float[]> vectors = new ArrayList<>(texts.size());
            for (String text : texts) {
                vectors.add(vectorFor(text));
            }
            return vectors;
        });

        String markdown = """
                # 主题

                甲段落。

                乙段落。

                丙段落。
                """;

        // 绝对阈值 0.75：相邻相似度 0.55 与 0.45 均低于阈值，三块各自独立
        RagProperties absolute = new RagProperties();
        absolute.getChunking().setStrategy(ChunkingStrategy.SEMANTIC);
        absolute.getChunking().getSemantic().setSimilarityThreshold(0.75);
        absolute.getChunking().getSemantic().setBreakpointPercentile(0d);
        absolute.getChunking().getSemantic().setMinChunkChars(0);
        absolute.getChunking().getSemantic().setMaxChunkChars(500);
        ChunkingService absoluteService = new ChunkingService(absolute, embeddingModel);
        assertThat(absoluteService.chunk(markdown, "doc-1").chunks()).hasSize(3);

        // 分位数 0.5：断点阈值取中位数 0.50，甲乙（0.55）合并，丙（-0.03）断开
        RagProperties percentile = new RagProperties();
        percentile.getChunking().setStrategy(ChunkingStrategy.SEMANTIC);
        percentile.getChunking().getSemantic().setSimilarityThreshold(0.75);
        percentile.getChunking().getSemantic().setBreakpointPercentile(0.5);
        percentile.getChunking().getSemantic().setMinChunkChars(0);
        percentile.getChunking().getSemantic().setMaxChunkChars(500);
        ChunkingService percentileService = new ChunkingService(percentile, embeddingModel);
        ChunkingService.ChunkingResult result = percentileService.chunk(markdown, "doc-1");

        assertThat(result.chunks()).hasSize(2);
        assertThat(result.chunks().get(0).text()).contains("甲段落").contains("乙段落");
        assertThat(result.chunks().get(1).text()).contains("丙段落");
    }

    private static float[] unitVector(double angleDegrees) {
        double radians = Math.toRadians(angleDegrees);
        return new float[] { (float) Math.cos(radians), (float) Math.sin(radians) };
    }

    private static float[] vectorFor(String text) {
        if (text.contains("甲")) {
            return unitVector(0d);
        }
        if (text.contains("乙")) {
            return unitVector(56.63d);   // cos(甲,乙) ≈ 0.55
        }
        return unitVector(119.89d);      // cos(乙,丙) ≈ 0.45，cos(质心(甲乙),丙) ≈ -0.03
    }

    @Test
    void parentChildStrategyStoresParentAndChildSeparately() {
        RagProperties properties = new RagProperties();
        properties.getChunking().setStrategy(ChunkingStrategy.PARENT_CHILD);
        properties.getChunking().getParentChild().setParentSize(120);
        properties.getChunking().getParentChild().setParentOverlap(10);
        properties.getChunking().getParentChild().setChildSize(40);
        properties.getChunking().getParentChild().setChildOverlap(5);

        ChunkingService service = new ChunkingService(properties, mock(EmbeddingModel.class));
        ChunkingService.ChunkingResult result = service.chunk("知识库内容。".repeat(80), "doc-1");

        assertThat(result.parentCount()).isGreaterThan(0);
        assertThat(result.parents()).hasSize(result.parentCount());

        // 子块：只带 parent_id / parent_index，不再复制 parent_text
        assertThat(result.chunks()).isNotEmpty();
        assertThat(result.chunks()).allSatisfy(chunk -> {
            assertThat(chunk.text().length()).isLessThanOrEqualTo(40);
            assertThat(chunk.metadata())
                    .containsEntry("chunk_type", "child")
                    .containsKey("parent_id")
                    .containsKey("parent_index")
                    .doesNotContainKey("parent_text");
            assertThat(String.valueOf(chunk.metadata().get("parent_id"))).startsWith("doc-1#p");
        });

        // 父块：独立片段，chunk_type=parent，正文与子块互补
        assertThat(result.parents()).allSatisfy(parent -> {
            assertThat(parent.metadata())
                    .containsEntry("chunk_type", "parent")
                    .containsKey("parent_id")
                    .containsKey("parent_index");
            assertThat(String.valueOf(parent.metadata().get("parent_id"))).startsWith("doc-1#p");
        });
        // 拼接全部父块正文应覆盖全部子块内容（父块是子块的完整上超集）
        String parentText = result.parents().stream().map(TextChunk::text).collect(Collectors.joining());
        for (TextChunk child : result.chunks()) {
            assertThat(parentText).contains(child.text().substring(0, Math.min(10, child.text().length())));
        }
    }

    @Test
    void parentChildStrategyInheritsMarkdownHeadingMetadata() {
        RagProperties properties = new RagProperties();
        properties.getChunking().setStrategy(ChunkingStrategy.PARENT_CHILD);
        properties.getChunking().getParentChild().setParentSize(2000);
        properties.getChunking().getParentChild().setChildSize(6);
        properties.getChunking().getParentChild().setChildOverlap(0);

        ChunkingService service = new ChunkingService(properties, mock(EmbeddingModel.class));
        ChunkingService.ChunkingResult result = service.chunk("""
                # 指南

                ## 安装

                安装说明。
                """, "doc-1");

        assertThat(result.chunks()).isNotEmpty();
        assertThat(result.chunks()).anySatisfy(chunk -> {
            assertThat(chunk.text()).contains("安装说明");
            assertThat(chunk.metadata())
                    .containsEntry("chunk_type", "child")
                    .containsEntry("heading_path", "指南 > 安装")
                    .containsEntry("content_format", "markdown");
        });
        // 不以标题行开头的子块正文前置 heading_path，让向量模型感知章节层级
        assertThat(result.chunks()).anySatisfy(chunk ->
                assertThat(chunk.text()).isEqualTo("指南 > 安装\n安装说明。"));
        // 以标题行开头的子块不重复前置
        assertThat(result.chunks()).anySatisfy(chunk ->
                assertThat(chunk.text()).startsWith("## 安装"));
    }

}
