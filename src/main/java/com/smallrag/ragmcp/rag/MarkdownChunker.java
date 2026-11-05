package com.smallrag.ragmcp.rag;

import java.util.ArrayList;
import java.util.Arrays;
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

    static final String FIELD_HEADING = "heading";
    static final String FIELD_HEADING_LEVEL = "heading_level";
    static final String FIELD_HEADING_PATH = "heading_path";

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

            int blockLength = block.text().length();
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

            if (!current.isEmpty() && textLength(current) + separator(current.get(current.size() - 1), block).length()
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
                int allowed = maxChars - blockLength - separator(syntheticTail, block).length();
                if (allowed <= 0) {
                    tail = "";
                } else if (tail.length() > allowed) {
                    tail = tail.substring(tail.length() - allowed);
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
        if (block.text().length() <= maxChars) {
            return List.of(block.text());
        }
        return switch (block.type()) {
            case CODE, MATH, TABLE -> splitLines(block.text(), maxChars);
            default -> RecursiveTextChunker.chunk(block.text(), maxChars, overlap, TEXT_SEPARATORS);
        };
    }

    /**
     * 代码、表格等按行切分；单行仍超长时再按字符硬切。
     */
    private static List<String> splitLines(String text, int maxChars) {
        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (line.length() > maxChars) {
                if (!current.isEmpty()) {
                    pieces.add(current.toString());
                    current = new StringBuilder();
                }
                pieces.addAll(TextChunker.chunk(line, maxChars, 0));
                continue;
            }
            if (!current.isEmpty() && current.length() + 1 + line.length() > maxChars) {
                pieces.add(current.toString());
                current = new StringBuilder();
            }
            if (!current.isEmpty()) {
                current.append('\n');
            }
            current.append(line);
        }
        if (!current.isEmpty()) {
            pieces.add(current.toString());
        }
        return pieces;
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

    private static int textLength(List<Block> blocks) {
        int length = 0;
        for (int i = 0; i < blocks.size(); i++) {
            if (i > 0) {
                length += separator(blocks.get(i - 1), blocks.get(i)).length();
            }
            length += blocks.get(i).text().length();
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
