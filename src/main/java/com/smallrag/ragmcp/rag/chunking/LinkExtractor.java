package com.smallrag.ragmcp.rag.chunking;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Locale;

/**
 * 从 Markdown 文本中提取链接目标（保留原文，仅作为 metadata 输出）。
 *
 * <p>只提取指向 .md/.markdown 文件的链接（排除绝对 URL、mail/ftp 等），
 * 剥离锚点与查询参数、URL decode、去前导 {@code ./}，按出现顺序去重并
 * 截断到 {@value #MAX_LINKS} 个。提取结果用于图查询（`links_to` 边），
 * 不参与任何切分决策。
 *
 * @author zjk
 */
public final class LinkExtractor {

    /** 单片段最多保留的链接数。 */
    private static final int MAX_LINKS = 32;

    /** inline link：`[text](target "title")` 或 `[text](target)`。 */
    private static final Pattern INLINE_LINK = Pattern.compile(
            "\\[(?:[^\\]\\[]+)\\]\\(([^)\\s]{1,512})(?:\\s+\"[^\"]{0,512}\"\\s*)?\\)");

    /** wikilink：`[[target]]`、`[[target#anchor]]`、`[[target|alias]]`。 */
    private static final Pattern WIKILINK = Pattern.compile(
            "\\[\\[([^\\]|#]{1,512})(?:[#|][^\\]]*)?]]");

    private LinkExtractor() {
    }

    /**
     * @param text 片段正文
     * @return 归一化后的链接目标列表（保留顺序，按首次出现去重）；无链接时返回空列表
     */
    public static List<String> extract(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        for (Matcher matcher = INLINE_LINK.matcher(text); matcher.find();) {
            normalize(decode(matcher.group(1)), true).ifPresent(seen::add);
            if (seen.size() >= MAX_LINKS) {
                return List.copyOf(seen);
            }
        }
        for (Matcher matcher = WIKILINK.matcher(text); matcher.find();) {
            normalize(matcher.group(1), false).ifPresent(seen::add);
            if (seen.size() >= MAX_LINKS) {
                return List.copyOf(seen);
            }
        }
        return List.copyOf(seen);
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    private static Optional<String> normalize(String target, boolean requireMarkdown) {
        if (target == null) {
            return Optional.empty();
        }
        String trimmed = target.strip();
        int fragment = trimmed.indexOf('#');
        if (fragment >= 0) {
            trimmed = trimmed.substring(0, fragment);
        }
        int query = trimmed.indexOf('?');
        if (query >= 0) {
            trimmed = trimmed.substring(0, query);
        }
        trimmed = trimmed.strip();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("ftp://")
                || lower.startsWith("mailto:") || lower.startsWith("tel:")) {
            return Optional.empty();
        }
        if (requireMarkdown && !(lower.endsWith(".md") || lower.endsWith(".markdown"))) {
            return Optional.empty();
        }
        if (trimmed.startsWith("./")) {
            trimmed = trimmed.substring(2);
        }
        return Optional.of(trimmed);
    }
}
