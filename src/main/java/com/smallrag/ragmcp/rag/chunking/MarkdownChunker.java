package com.smallrag.ragmcp.rag.chunking;

import org.springframework.ai.embedding.EmbeddingModel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Markdown / Obsidian 感知切分器。
 *
 * <p>目标输入是标准 Markdown 或 Obsidian 兼容 Markdown，支持中英文。实现重点：
 * <ul>
 * <li>按 ATX / Setext 标题分节，并把标题路径写入 metadata；</li>
 * <li>fenced code、缩进代码、表格、引用/标注块作为受保护块，不按字符切断；</li>
 * <li>支持 YAML frontmatter，并提取 title / tags / aliases / description 元数据；</li>
 * <li>识别 Obsidian callout、wikilink，并保留原文；</li>
 * <li>超长块才回落到递归字符切分或按行切分。</li>
 * </ul>
 *
 * @author zjk
 */
public final class MarkdownChunker {

    public static final String FIELD_HEADING = "heading";
    public static final String FIELD_HEADING_LEVEL = "heading_level";
    public static final String FIELD_HEADING_PATH = "heading_path";

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern SETEXT_UNDERLINE = Pattern.compile("^\\s*(=+|-+)\\s*$");
    private static final Pattern FENCE_START = Pattern.compile("^\\s*(`{3,}|~{3,})(.*)$");
    private static final Pattern LIST_ITEM = Pattern.compile("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$");
    private static final Pattern HORIZONTAL_RULE = Pattern.compile(
            "^\\s*(?:(?:\\*\\s*){3,}|(?:-\\s*){3,}|(?:_\\s*){3,})$");
    private static final Pattern TABLE_SEPARATOR = Pattern.compile(
            "^\\s*\\|?\\s*:?-{3,}:?\\s*(?:\\|\\s*:?-{3,}:?\\s*)+\\|?\\s*$");
    private static final Pattern WIKILINK = Pattern.compile("\\[\\[([^\\]|#]+)(?:[#|][^\\]]*)?]]");
    private static final Pattern CALLOUT = Pattern.compile("^\\s*>\\s*\\[!.*");

    /**
     * 超长文本块回落到递归切分时使用的分隔符。
     */
    private static final List<String> TEXT_SEPARATORS = List.of(
            "\n\n", "\n", "。", "！", "？", "；", "…",
            ". ", "! ", "? ", "; ", "，", ", ", " ");

    private MarkdownChunker() {
    }

    /**
     * @param markdown           Markdown 原文
     * @param maxChars           单块最大字符数
     * @param overlap            相邻块重叠字符数（仅文本类块生效）
     * @param headingSeparator   标题路径分隔符，例如 " > "
     * @param inheritedMetadata  从父块继承的元数据，可为空
     * @return 按原文顺序排列的片段
     */
    public static List<TextChunk> chunk(String markdown,
            int maxChars,
            int overlap,
            String headingSeparator,
            Map<String, Object> inheritedMetadata) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }

        int effectiveMax = Math.max(1, maxChars);
        int effectiveOverlap = Math.max(0, Math.min(overlap, effectiveMax / 2));
        String separator = (headingSeparator == null || headingSeparator.isBlank())
                ? " > "
                : headingSeparator;
        Map<String, Object> inherited = inheritedMetadata == null ? Map.of() : inheritedMetadata;

        String normalized = markdown.replace("\r\n", "\n").replace('\r', '\n').strip();
        Parsed parsed = parseFrontMatter(normalized);
        List<Block> blocks = parseBlocks(parsed.body());
        List<Section> sections = buildSections(blocks, separator, inherited, parsed.metadata());

        List<TextChunk> chunks = new ArrayList<>();
        for (Section section : sections) {
            chunks.addAll(chunkSection(section, effectiveMax, effectiveOverlap));
        }
        return chunks;
    }


    /**
     * 语义感知的 Markdown 切分：标题分节 + 文本块级质心比较。
     *
     * <p>在结构化解析之上把切分决策从“按大小贪心装填”升级为“按语义合并”：
     * 同一节内的段落/列表/引用等文本块先批量 Embedding，与当前块的累积质心
     * 比较，相似度低于阈值（语义转折）或合并超长时断开。代码、表格、数学块
     * 作为受保护块独立成块，不参与语义合并，也不消耗 Embedding；超长文本块
     * 回落到句子级语义切分（{@link SemanticTextChunker}）。
     *
     * @param markdown          Markdown 原文
     * @param maxChars          单块最大字符数
     * @param minChars          单块目标最小字符数，未达到时优先继续合并
     * @param headingSeparator  标题路径分隔符，例如 " > "
     * @param inheritedMetadata 从上层继承的元数据，可为空
     * @param embeddingModel    用于计算文本块向量的模型
     * @param threshold         合并阈值：文本块向量与当前块质心的余弦相似度低于该值时断开
     * @param breakpointPercentile 断点分位数（0~1）：&gt; 0 时忽略 threshold，改用节内相邻
     *                           文本块相似度的该分位数作为断点阈值，对语言与模型分布差异更鲁棒
     * @param batchSize         单次 Embedding 请求的文本块数
     * @return 按原文顺序排列的片段
     */
    public static List<TextChunk> chunkSemantic(String markdown,
            int maxChars,
            int minChars,
            String headingSeparator,
            Map<String, Object> inheritedMetadata,
            EmbeddingModel embeddingModel,
            double threshold,
            double breakpointPercentile,
            int batchSize) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }

        int effectiveMax = Math.max(1, maxChars);
        int effectiveMin = Math.max(0, Math.min(minChars, effectiveMax));
        String separator = (headingSeparator == null || headingSeparator.isBlank())
                ? " > "
                : headingSeparator;
        Map<String, Object> inherited = inheritedMetadata == null ? Map.of() : inheritedMetadata;

        String normalized = markdown.replace("\r\n", "\n").replace('\r', '\n').strip();
        Parsed parsed = parseFrontMatter(normalized);
        List<Block> blocks = parseBlocks(parsed.body());
        List<Section> sections = buildSections(blocks, separator, inherited, parsed.metadata());
        Map<String, float[]> vectorByText = embedTextBlocks(sections, embeddingModel, batchSize, effectiveMax);
        double effectiveThreshold = breakpointPercentile > 0d
                ? breakpointThreshold(sections, vectorByText, threshold, breakpointPercentile)
                : threshold;

        List<TextChunk> chunks = new ArrayList<>();
        for (Section section : sections) {
            chunkSectionSemantic(section, effectiveMax, effectiveMin, effectiveThreshold,
                    vectorByText, embeddingModel, batchSize, chunks);
        }
        return chunks;
    }

    /**
     * 批量计算普通文本块（非超长、非受保护）的向量，按文本内容去重。
     */
    private static Map<String, float[]> embedTextBlocks(List<Section> sections,
            EmbeddingModel embeddingModel,
            int batchSize,
            int maxChars) {
        Set<String> texts = new LinkedHashSet<>();
        for (Section section : sections) {
            for (Block block : section.blocks()) {
                if (block.text().isBlank() || !isTextLike(block) || TextWeights.weight(block.text()) > maxChars) {
                    continue;
                }
                texts.add(block.text());
            }
        }
        if (texts.isEmpty()) {
            return Map.of();
        }
        List<String> ordered = new ArrayList<>(texts);
        List<float[]> vectors = SemanticTextChunker.embedAll(embeddingModel, ordered, Math.max(1, batchSize));
        Map<String, float[]> vectorByText = new HashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            vectorByText.put(ordered.get(i), vectors.get(i));
        }
        return vectorByText;
    }

    /**
     * 分位数断点阈值：取节内相邻文本块相似度的指定分位数。
     * 相似样本不足（无法形成相邻对）时回落到绝对阈值。
     */
    private static double breakpointThreshold(List<Section> sections,
            Map<String, float[]> vectorByText,
            double fallbackThreshold,
            double percentile) {
        List<Double> similarities = new ArrayList<>();
        for (Section section : sections) {
            float[] previous = null;
            for (Block block : section.blocks()) {
                float[] vector = vectorByText.get(block.text());
                if (vector == null) {
                    continue;
                }
                if (previous != null) {
                    similarities.add(SemanticTextChunker.cosine(previous, vector));
                }
                previous = vector;
            }
        }
        if (similarities.isEmpty()) {
            return fallbackThreshold;
        }
        double threshold = SemanticTextChunker.percentile(similarities, percentile);
        return Double.isNaN(threshold) ? fallbackThreshold : threshold;
    }

    /**
     * 受保护块：不参与语义合并，独立成块（超长时按行切分）。
     */
    private static boolean isProtected(Block block) {
        return switch (block.type()) {
            case CODE, MATH, TABLE -> true;
            default -> false;
        };
    }

    /**
     * 在一个 section 内按语义合并文本块；受保护块独立成块，超长块回落到句子级。
     */
    private static void chunkSectionSemantic(Section section,
            int maxChars,
            int minChars,
            double threshold,
            Map<String, float[]> vectorByText,
            EmbeddingModel embeddingModel,
            int batchSize,
            List<TextChunk> chunks) {

        SemanticMerge merge = new SemanticMerge(section, maxChars, chunks);
        for (Block block : section.blocks()) {
            if (block.text().isBlank()) {
                continue;
            }

            if (TextWeights.weight(block.text()) > maxChars) {
                merge.flush();
                List<String> pieces = isTextLike(block)
                        ? SemanticTextChunker.chunk(block.text(), embeddingModel,
                                maxChars, minChars, threshold, batchSize)
                        : splitOversized(block, maxChars, 0);
                for (String piece : pieces) {
                    chunks.add(toChunk(piece, section, List.of(block), true));
                }
                merge.invalidateTail();
                continue;
            }

            if (isProtected(block)) {
                merge.flush();
                chunks.add(toChunk(block.text(), section, List.of(block), false));
                merge.invalidateTail();
                continue;
            }

            if (block.type() == BlockType.HEADING || block.type() == BlockType.HR) {
                // 标题与分隔线直接并入当前块，不参与语义比较
                merge.add(block, null);
                continue;
            }

            float[] vector = vectorByText.get(block.text());
            if (merge.shouldBreak(vector, minChars, threshold)) {
                merge.flush();
            }
            merge.add(block, vector);
        }
        merge.finish(minChars);
    }

    /**
     * 语义合并累积器：维护当前块缓冲与累积质心，并支持尾块回收。
     */
    private static final class SemanticMerge {

        private final Section section;
        private final int maxChars;
        private final List<TextChunk> chunks;
        private final List<Block> buffer = new ArrayList<>();
        private float[] centroidSum;
        private String lastText;
        private List<Block> lastBlocks;

        SemanticMerge(Section section, int maxChars, List<TextChunk> chunks) {
            this.section = section;
            this.maxChars = maxChars;
            this.chunks = chunks;
        }

        void flush() {
            if (buffer.isEmpty()) {
                return;
            }
            String text = joinBlocks(buffer);
            chunks.add(toChunk(text, section, buffer, false));
            lastText = text;
            lastBlocks = new ArrayList<>(buffer);
            buffer.clear();
            centroidSum = null;
        }

        void invalidateTail() {
            lastText = null;
            lastBlocks = null;
        }

        boolean shouldBreak(float[] vector, int minChars, double threshold) {
            return vector != null
                    && centroidSum != null
                    && textLength(buffer) >= minChars
                    && SemanticTextChunker.cosine(centroidSum, vector) < threshold;
        }

        void add(Block block, float[] vector) {
            if (!buffer.isEmpty()
                    && textLength(buffer) + TextWeights.weight(separator(buffer.get(buffer.size() - 1), block))
                            + TextWeights.weight(block.text()) > maxChars) {
                flush();
            }
            buffer.add(block);
            if (vector != null) {
                if (centroidSum == null) {
                    centroidSum = vector.clone();
                } else {
                    for (int i = 0; i < centroidSum.length; i++) {
                        centroidSum[i] += vector[i];
                    }
                }
            }
        }

        /**
         * 收尾：最后一块低于 minChars 时回收到上一条普通文本块中，避免碎块。
         */
        void finish(int minChars) {
            if (buffer.isEmpty()) {
                return;
            }
            String text = joinBlocks(buffer);
            if (TextWeights.weight(text) < minChars && lastText != null
                    && TextWeights.weight(lastText) + 0.5 + TextWeights.weight(text) <= maxChars) {
                List<Block> combined = new ArrayList<>(lastBlocks);
                combined.addAll(buffer);
                chunks.set(chunks.size() - 1, toChunk(lastText + "\n\n" + text, section, combined, false));
            } else {
                chunks.add(toChunk(text, section, buffer, false));
            }
            buffer.clear();
        }
    }

    /**
     * 解析 YAML frontmatter，返回正文和可用的元数据。
     */
    private static Parsed parseFrontMatter(String markdown) {
        String[] lines = markdown.split("\n", -1);
        if (lines.length < 3 || !"---".equals(lines[0].strip())) {
            return new Parsed(markdown, Map.of());
        }

        int end = -1;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].strip();
            if ("---".equals(line) || "...".equals(line)) {
                end = i;
                break;
            }
        }
        if (end < 0) {
            return new Parsed(markdown, Map.of());
        }

        // 只有中间确实存在 YAML key 时才按 frontmatter 处理，避免把 "---\n正文\n---" 误判
        boolean hasYamlKey = false;
        for (int i = 1; i < end; i++) {
            if (lines[i].matches("^[A-Za-z0-9_-]+\\s*:.*")) {
                hasYamlKey = true;
                break;
            }
        }
        if (!hasYamlKey) {
            return new Parsed(markdown, Map.of());
        }

        Map<String, Object> metadata = parseFrontMatterMetadata(
                Arrays.asList(lines).subList(1, end));
        String body = String.join("\n", Arrays.copyOfRange(lines, end + 1, lines.length)).strip();
        return new Parsed(body, metadata);
    }

    /**
     * 只提取对检索有用的常见 frontmatter 字段，避免把任意 YAML 塞进向量 payload。
     */
    private static Map<String, Object> parseFrontMatterMetadata(List<String> lines) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        String currentKey = null;
        List<String> currentValues = new ArrayList<>();

        for (String rawLine : lines) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("- ") && currentKey != null) {
                currentValues.add(unquote(line.substring(2).strip()));
                continue;
            }

            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            putFrontMatter(metadata, currentKey, currentValues);
            currentKey = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).strip();
            currentValues = new ArrayList<>();
            if (!value.isEmpty()) {
                currentValues.add(unquote(value));
            }
        }
        putFrontMatter(metadata, currentKey, currentValues);
        return metadata;
    }

    private static void putFrontMatter(Map<String, Object> metadata, String key, List<String> values) {
        if (key == null || values.isEmpty()) {
            return;
        }
        String value = String.join(", ", values);
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1).strip();
        }
        switch (key) {
            case "title" -> metadata.put("frontmatter_title", value);
            case "tags" -> metadata.put("frontmatter_tags", value);
            case "aliases" -> metadata.put("frontmatter_aliases", value);
            case "description" -> metadata.put("frontmatter_description", value);
            default -> {
                // 其它 frontmatter 字段暂不进入 payload
            }
        }
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private static List<Block> parseBlocks(String body) {
        List<Block> blocks = new ArrayList<>();
        String[] lines = body.split("\n", -1);
        int i = 0;

        while (i < lines.length) {
            String line = lines[i];
            if (line.isBlank()) {
                i++;
                continue;
            }

            Matcher fence = FENCE_START.matcher(line);
            if (fence.matches()) {
                String marker = fence.group(1);
                char fenceChar = marker.charAt(0);
                int fenceLength = marker.length();
                List<String> content = new ArrayList<>();
                content.add(line.stripTrailing());
                i++;
                while (i < lines.length) {
                    String current = lines[i];
                    content.add(current.stripTrailing());
                    i++;
                    if (isFenceClose(current, fenceChar, fenceLength)) {
                        break;
                    }
                }
                blocks.add(new Block(BlockType.CODE, String.join("\n", content).strip(), 0, null));
                continue;
            }

            String stripped = line.strip();
            if (stripped.startsWith("$$")) {
                List<String> content = new ArrayList<>();
                content.add(line.stripTrailing());
                i++;
                if (!stripped.equals("$$")) {
                    // 单行 $$...$$，不继续吞后续内容
                    blocks.add(new Block(BlockType.MATH, content.get(0).strip(), 0, null));
                    continue;
                }
                while (i < lines.length) {
                    String current = lines[i];
                    content.add(current.stripTrailing());
                    i++;
                    if ("$$".equals(current.strip())) {
                        break;
                    }
                }
                blocks.add(new Block(BlockType.MATH, String.join("\n", content).strip(), 0, null));
                continue;
            }

            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                int level = heading.group(1).length();
                String text = heading.group(2).strip();
                blocks.add(new Block(BlockType.HEADING, line.strip(), level, text));
                i++;
                continue;
            }

            if (i + 1 < lines.length
                    && !line.isBlank()
                    && !LIST_ITEM.matcher(line).matches()
                    && SETEXT_UNDERLINE.matcher(lines[i + 1]).matches()
                    && !SETEXT_UNDERLINE.matcher(line).matches()) {
                String underline = lines[i + 1].strip();
                int level = underline.startsWith("=") ? 1 : 2;
                blocks.add(new Block(BlockType.HEADING,
                        line.strip() + "\n" + underline, level, line.strip()));
                i += 2;
                continue;
            }

            if (HORIZONTAL_RULE.matcher(line).matches()) {
                blocks.add(new Block(BlockType.HR, line.strip(), 0, null));
                i++;
                continue;
            }

            if (isTableStart(lines, i)) {
                List<String> content = new ArrayList<>();
                while (i < lines.length && !lines[i].isBlank() && lines[i].contains("|")) {
                    content.add(lines[i].stripTrailing());
                    i++;
                }
                blocks.add(new Block(BlockType.TABLE, String.join("\n", content).strip(), 0, null));
                continue;
            }

            if (line.stripLeading().startsWith(">")) {
                List<String> content = new ArrayList<>();
                boolean callout = false;
                while (i < lines.length) {
                    String current = lines[i];
                    if (current.isBlank()) {
                        if (i + 1 < lines.length && lines[i + 1].stripLeading().startsWith(">")) {
                            content.add("");
                            i++;
                            continue;
                        }
                        break;
                    }
                    if (!current.stripLeading().startsWith(">")) {
                        break;
                    }
                    if (CALLOUT.matcher(current).matches()) {
                        callout = true;
                    }
                    content.add(current.stripTrailing());
                    i++;
                }
                blocks.add(new Block(callout ? BlockType.CALLOUT : BlockType.QUOTE,
                        String.join("\n", content).strip(), 0, null));
                continue;
            }

            Matcher list = LIST_ITEM.matcher(line);
            if (list.matches()) {
                int indent = leadingSpaces(line);
                List<String> content = new ArrayList<>();
                content.add(line.stripTrailing());
                i++;
                while (i < lines.length) {
                    String current = lines[i];
                    if (current.isBlank()) {
                        int next = i + 1;
                        while (next < lines.length && lines[next].isBlank()) {
                            next++;
                        }
                        if (next < lines.length
                                && (leadingSpaces(lines[next]) > indent
                                || LIST_ITEM.matcher(lines[next]).matches())) {
                            content.add("");
                            i++;
                            continue;
                        }
                        break;
                    }
                    Matcher nextItem = LIST_ITEM.matcher(current);
                    if (nextItem.matches() && leadingSpaces(current) <= indent) {
                        break;
                    }
                    if (leadingSpaces(current) > indent) {
                        content.add(current.stripTrailing());
                        i++;
                        continue;
                    }
                    break;
                }
                blocks.add(new Block(BlockType.LIST_ITEM, String.join("\n", content).strip(), 0, null));
                continue;
            }

            if (line.startsWith("    ") || line.startsWith("\t")) {
                List<String> content = new ArrayList<>();
                while (i < lines.length
                        && (lines[i].startsWith("    ") || lines[i].startsWith("\t") || lines[i].isBlank())) {
                    content.add(lines[i]);
                    i++;
                }
                blocks.add(new Block(BlockType.CODE, String.join("\n", content).strip(), 0, null));
                continue;
            }

            List<String> content = new ArrayList<>();
            while (i < lines.length && !lines[i].isBlank() && !isBlockStart(lines, i)) {
                content.add(lines[i].stripTrailing());
                i++;
            }
            if (content.isEmpty()) {
                content.add(line.stripTrailing());
                i++;
            }
            blocks.add(new Block(BlockType.PARAGRAPH, String.join("\n", content).strip(), 0, null));
        }
        return blocks;
    }

    private static boolean isFenceClose(String line, char fenceChar, int fenceLength) {
        String stripped = line.strip();
        if (stripped.isEmpty() || stripped.charAt(0) != fenceChar) {
            return false;
        }
        int count = 0;
        while (count < stripped.length() && stripped.charAt(count) == fenceChar) {
            count++;
        }
        return count >= fenceLength && stripped.substring(count).isBlank();
    }

    private static boolean isTableStart(String[] lines, int index) {
        return lines[index].contains("|")
                && index + 1 < lines.length
                && TABLE_SEPARATOR.matcher(lines[index + 1]).matches();
    }

    private static boolean isBlockStart(String[] lines, int index) {
        String line = lines[index];
        if (line.isBlank()) {
            return true;
        }
        if (FENCE_START.matcher(line).matches() || HEADING.matcher(line).matches()) {
            return true;
        }
        if (line.strip().startsWith("$$") || HORIZONTAL_RULE.matcher(line).matches()) {
            return true;
        }
        if (isTableStart(lines, index)
                || line.stripLeading().startsWith(">")
                || LIST_ITEM.matcher(line).matches()) {
            return true;
        }
        return index + 1 < lines.length
                && !SETEXT_UNDERLINE.matcher(line).matches()
                && SETEXT_UNDERLINE.matcher(lines[index + 1]).matches();
    }

    private static int leadingSpaces(String line) {
        int count = 0;
        while (count < line.length()) {
            char value = line.charAt(count);
            if (value == ' ') {
                count++;
            } else if (value == '\t') {
                count += 4;
            } else {
                break;
            }
        }
        return count;
    }

    /**
     * 按标题构建 section；每个 section 继承 frontmatter 和标题路径元数据。
     */
    private static List<Section> buildSections(List<Block> blocks,
            String headingSeparator,
            Map<String, Object> inheritedMetadata,
            Map<String, Object> frontMatterMetadata) {
        Map<String, Object> base = new LinkedHashMap<>(inheritedMetadata);
        base.putAll(frontMatterMetadata);

        List<Section> sections = new ArrayList<>();
        List<Heading> headingPath = new ArrayList<>();
        List<Block> currentBlocks = new ArrayList<>();
        Map<String, Object> currentMetadata = new LinkedHashMap<>(base);

        for (Block block : blocks) {
            if (block.type() == BlockType.HEADING) {
                if (hasContent(currentBlocks)) {
                    sections.add(new Section(new LinkedHashMap<>(currentMetadata), currentBlocks));
                }
                headingPath.removeIf(heading -> heading.level() >= block.headingLevel());
                headingPath.add(new Heading(block.headingLevel(), block.headingText()));

                currentMetadata = new LinkedHashMap<>(base);
                currentMetadata.put(FIELD_HEADING, block.headingText());
                currentMetadata.put(FIELD_HEADING_LEVEL, block.headingLevel());
                currentMetadata.put(FIELD_HEADING_PATH, headingPath.stream()
                        .map(Heading::text)
                        .collect(Collectors.joining(headingSeparator)));
                currentBlocks = new ArrayList<>();
                currentBlocks.add(block);
            } else {
                currentBlocks.add(block);
            }
        }
        if (hasContent(currentBlocks)) {
            sections.add(new Section(currentMetadata, currentBlocks));
        }
        return sections;
    }

    private static boolean hasContent(List<Block> blocks) {
        return blocks.stream().anyMatch(block -> block.type() != BlockType.HEADING);
    }

    /**
     * 在一个 section 内按块合并；受保护块不拆，超长时才回落。
     */
    private static List<TextChunk> chunkSection(Section section, int maxChars, int overlap) {
        List<TextChunk> chunks = new ArrayList<>();
        List<Block> current = new ArrayList<>();
        Block lastFlushedBlock = null;
        String lastFlushedText = null;

        for (Block block : section.blocks()) {
            if (block.text().isBlank()) {
                continue;
            }

            double blockLength = TextWeights.weight(block.text());
            if (blockLength > maxChars) {
                if (!current.isEmpty()) {
                    String text = joinBlocks(current);
                    chunks.add(toChunk(text, section, current, false));
                    lastFlushedText = text;
                    lastFlushedBlock = current.get(current.size() - 1);
                    current = new ArrayList<>();
                }
                List<String> pieces = splitOversized(block, maxChars, overlap);
                for (String piece : pieces) {
                    chunks.add(toChunk(piece, section, List.of(block), true));
                }
                if (!pieces.isEmpty()) {
                    lastFlushedText = pieces.get(pieces.size() - 1);
                    lastFlushedBlock = block;
                }
                continue;
            }

            if (!current.isEmpty() && textLength(current) + TextWeights.weight(separator(current.get(current.size() - 1), block))
                    + blockLength > maxChars) {
                String text = joinBlocks(current);
                chunks.add(toChunk(text, section, current, false));
                lastFlushedText = text;
                lastFlushedBlock = current.get(current.size() - 1);
                current = new ArrayList<>();
            }

            if (current.isEmpty()
                    && lastFlushedText != null
                    && overlap > 0
                    && isTextLike(lastFlushedBlock)
                    && isTextLike(block)) {
                String tail = overlapTail(lastFlushedText, overlap);
                Block syntheticTail = new Block(BlockType.PARAGRAPH, tail, 0, null);
                double allowed = maxChars - blockLength - TextWeights.weight(separator(syntheticTail, block));
                if (allowed <= 0) {
                    tail = "";
                } else if (TextWeights.weight(tail) > allowed) {
                    tail = tail.substring(TextWeights.fitCharsBefore(tail, tail.length(), allowed));
                }
                if (!tail.isBlank()) {
                    current.add(new Block(BlockType.PARAGRAPH, tail, 0, null));
                }
            }

            current.add(block);
        }

        if (!current.isEmpty()) {
            chunks.add(toChunk(joinBlocks(current), section, current, false));
        }
        return chunks;
    }

    private static List<String> splitOversized(Block block, int maxChars, int overlap) {
        if (TextWeights.weight(block.text()) <= maxChars) {
            return List.of(block.text());
        }
        return switch (block.type()) {
            case CODE -> splitFenced(block.text(), maxChars);
            case MATH -> splitMath(block.text(), maxChars);
            case TABLE -> splitTable(block.text(), maxChars);
            default -> RecursiveTextChunker.chunk(block.text(), maxChars, overlap, TEXT_SEPARATORS);
        };
    }

    /**
     * 表格按行切分；每个片段都重复表头（含分隔行），避免切片脱离列语义。
     * 表头本身超长时回退到按行切分。
     */
    private static List<String> splitTable(String text, int maxChars) {
        String[] lines = text.split("\n", -1);
        if (lines.length < 3) {
            return splitLines(text, maxChars);
        }
        String header = lines[0] + "\n" + lines[1];
        double budget = maxChars - TextWeights.weight(header) - 1;
        if (budget <= 0) {
            return splitLines(text, maxChars);
        }
        List<String> groups = groupLines(Arrays.asList(lines).subList(2, lines.length), budget);
        List<String> pieces = new ArrayList<>(groups.size());
        for (String group : groups) {
            pieces.add(header + "\n" + group);
        }
        return pieces;
    }

    /**
     * fenced code 按行切分；每个片段都补回围栏标记（含语言标注），
     * 保持代码块语法完整。缩进代码或无围栏时回退到按行切分。
     */
    private static List<String> splitFenced(String text, int maxChars) {
        String[] lines = text.split("\n", -1);
        if (lines.length < 3) {
            return splitLines(text, maxChars);
        }
        Matcher open = FENCE_START.matcher(lines[0]);
        String last = lines[lines.length - 1];
        if (!open.matches()
                || !isFenceClose(last, open.group(1).charAt(0), open.group(1).length())) {
            return splitLines(text, maxChars);
        }
        String fence = open.group(1);
        String opening = fence + open.group(2);
        double budget = maxChars - TextWeights.weight(opening) - TextWeights.weight(fence) - 0.5;
        if (budget <= 0) {
            return splitLines(text, maxChars);
        }
        List<String> groups = groupLines(
                Arrays.asList(lines).subList(1, lines.length - 1), budget);
        List<String> pieces = new ArrayList<>(groups.size());
        for (String group : groups) {
            pieces.add(wrapFence(opening, fence, group));
        }
        return pieces;
    }

    private static String wrapFence(String opening, String fence, String content) {
        return opening + "\n" + content + "\n" + fence;
    }

    /**
     * 数学块按行切分；每个片段补回 $$ 围栏。单行或非围栏形态回退到按行切分。
     */
    private static List<String> splitMath(String text, int maxChars) {
        String[] lines = text.split("\n", -1);
        if (lines.length < 3
                || !"$$".equals(lines[0].strip())
                || !"$$".equals(lines[lines.length - 1].strip())) {
            return splitLines(text, maxChars);
        }
        double budget = maxChars - 1.5;
        if (budget <= 0) {
            return splitLines(text, maxChars);
        }
        List<String> groups = groupLines(
                Arrays.asList(lines).subList(1, lines.length - 1), budget);
        List<String> pieces = new ArrayList<>(groups.size());
        for (String group : groups) {
            pieces.add("$$\n" + group + "\n$$");
        }
        return pieces;
    }

    /**
     * 通用按行切分：单行仍超长时再按字符硬切。
     */
    private static List<String> splitLines(String text, int maxChars) {
        return groupLines(Arrays.asList(text.split("\n", -1)), maxChars);
    }

    /**
     * 把行列表按预算分组；单行超预算时按字符硬切，保证每片不超过预算。
     */
    private static List<String> groupLines(List<String> lines, double budget) {
        List<String> groups = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        double currentWeight = 0d;
        for (String line : lines) {
            double lineWeight = TextWeights.weight(line);
            if (lineWeight > budget) {
                if (!current.isEmpty()) {
                    groups.add(current.toString());
                    current = new StringBuilder();
                    currentWeight = 0d;
                }
                groups.addAll(TextChunker.chunk(line, (int) budget, 0));
                continue;
            }
            if (!current.isEmpty() && currentWeight + TextWeights.NARROW + lineWeight > budget) {
                groups.add(current.toString());
                current = new StringBuilder();
                currentWeight = 0d;
            }
            if (!current.isEmpty()) {
                current.append('\n');
                currentWeight += TextWeights.NARROW;
            }
            current.append(line);
            currentWeight += lineWeight;
        }
        if (!current.isEmpty()) {
            groups.add(current.toString());
        }
        return groups;
    }

    private static TextChunk toChunk(String text,
            Section section,
            List<Block> blocks,
            boolean oversized) {
        Map<String, Object> metadata = new LinkedHashMap<>(section.metadata());
        metadata.put("content_format", "markdown");
        metadata.put("markdown_block_types", blocks.stream()
                .map(block -> block.type().name().toLowerCase(Locale.ROOT))
                .distinct()
                .collect(Collectors.joining(",")));
        String wikilinks = extractWikilinks(text);
        if (!wikilinks.isEmpty()) {
            metadata.put("wikilinks", wikilinks);
        }
        if (oversized) {
            metadata.put("oversized_block", true);
        }
        return new TextChunk(text.strip(), metadata);
    }

    private static String extractWikilinks(String text) {
        Matcher matcher = WIKILINK.matcher(text);
        Set<String> links = new LinkedHashSet<>();
        while (matcher.find()) {
            String target = matcher.group(1).strip();
            if (!target.isEmpty()) {
                links.add(target);
            }
        }
        return String.join(",", links);
    }

    private static double textLength(List<Block> blocks) {
        double length = 0;
        for (int i = 0; i < blocks.size(); i++) {
            if (i > 0) {
                length += TextWeights.weight(separator(blocks.get(i - 1), blocks.get(i)));
            }
            length += TextWeights.weight(blocks.get(i).text());
        }
        return length;
    }

    private static String joinBlocks(List<Block> blocks) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < blocks.size(); i++) {
            if (i > 0) {
                result.append(separator(blocks.get(i - 1), blocks.get(i)));
            }
            result.append(blocks.get(i).text());
        }
        return result.toString();
    }

    private static String separator(Block previous, Block next) {
        if (previous.type() == BlockType.HEADING || next.type() == BlockType.HEADING) {
            return "\n";
        }
        if (previous.type() == BlockType.LIST_ITEM && next.type() == BlockType.LIST_ITEM) {
            return "\n";
        }
        if (isQuoteLike(previous) && isQuoteLike(next)) {
            return "\n";
        }
        return "\n\n";
    }

    private static boolean isQuoteLike(Block block) {
        return block.type() == BlockType.QUOTE || block.type() == BlockType.CALLOUT;
    }

    private static boolean isTextLike(Block block) {
        return block != null && switch (block.type()) {
            case PARAGRAPH, LIST_ITEM, QUOTE, CALLOUT -> true;
            default -> false;
        };
    }

    private static String overlapTail(String text, int overlap) {
        if (text == null || text.isEmpty() || overlap <= 0) {
            return "";
        }
        return text.substring(Math.max(0, text.length() - overlap));
    }

    private enum BlockType {
        HEADING,
        PARAGRAPH,
        LIST_ITEM,
        CODE,
        TABLE,
        QUOTE,
        CALLOUT,
        MATH,
        HR
    }

    private record Block(BlockType type, String text, int headingLevel, String headingText) {
    }

    private record Heading(int level, String text) {
    }

    private record Section(Map<String, Object> metadata, List<Block> blocks) {
    }

    private record Parsed(String body, Map<String, Object> metadata) {
    }
}
