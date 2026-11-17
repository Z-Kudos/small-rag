package com.smallrag.ragmcp.rag.metadata;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 文档分类 / 标签元数据的规范化工具。
 *
 * <p>标签来源有三处，统一收敛到 {@code tags}（列表）与 {@code category}（单值）：
 * <ul>
 *   <li>{@code rag_ingest} / {@code rag_update} 传入的业务元数据 {@code tags}、{@code category}；</li>
 *   <li>Markdown frontmatter 的 {@code frontmatter_tags}（逗号拼接字符串）；</li>
 *   <li>{@code rag.sources.metadata} 目录导入时附加的元数据。</li>
 * </ul>
 *
 * <p>为什么要规范化：Qdrant 的 payload 过滤对数组字段使用 {@code Match.Any}，
 * SQLite 后端在 Java 层评估过滤器。统一成 {@code List<String>} 后两端都能用
 * {@code tags in [...]} 表达“命中任一标签”，而不会再依赖逗号字符串做等值比较。
 *
 * @author zjk
 */
public final class DocumentTags {

    /** 标签列表字段（payload 中为字符串数组）。 */
    public static final String FIELD_TAGS = "tags";

    /** 分类字段（单值）。 */
    public static final String FIELD_CATEGORY = "category";

    /** Markdown frontmatter 中提取出的原始标签字段。 */
    public static final String FIELD_FRONTMATTER_TAGS = "frontmatter_tags";

    private DocumentTags() {
    }

    /**
     * 把业务标签字符串（逗号 / 中文逗号分隔）解析为规范化列表。
     */
    public static List<String> parseTags(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        Set<String> tags = new LinkedHashSet<>();
        for (String part : raw.split("[,，]")) {
            String value = part.trim();
            if (!value.isEmpty()) {
                tags.add(value);
            }
        }
        return List.copyOf(tags);
    }

    /**
     * 就地规范化元数据：合并标签来源、去重并 trimming，移除空的 category。
     */
    public static void normalize(Map<String, Object> metadata) {
        if (metadata == null) {
            return;
        }
        Set<String> tags = new LinkedHashSet<>();
        collect(tags, metadata.get(FIELD_TAGS));
        collect(tags, metadata.get(FIELD_FRONTMATTER_TAGS));
        if (tags.isEmpty()) {
            metadata.remove(FIELD_TAGS);
        } else {
            metadata.put(FIELD_TAGS, List.copyOf(tags));
        }

        Object category = metadata.get(FIELD_CATEGORY);
        String normalized = single(category);
        if (normalized.isEmpty()) {
            metadata.remove(FIELD_CATEGORY);
        } else {
            metadata.put(FIELD_CATEGORY, normalized);
        }
    }

    /**
     * 在已落库的 Document 元数据上做本地匹配（供不支持服务端过滤的回退路径使用）。
     */
    public static boolean matches(Map<String, Object> metadata, List<String> tags, String category) {
        if (metadata == null) {
            return false;
        }
        if (category != null && !category.isBlank()
                && !category.trim().equalsIgnoreCase(single(metadata.get(FIELD_CATEGORY)))) {
            return false;
        }
        if (tags == null || tags.isEmpty()) {
            return true;
        }
        Set<String> actual = new LinkedHashSet<>();
        collect(actual, metadata.get(FIELD_TAGS));
        collect(actual, metadata.get(FIELD_FRONTMATTER_TAGS));
        for (String expected : tags) {
            if (actual.contains(expected)) {
                return true;
            }
        }
        return false;
    }

    private static void collect(Set<String> out, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                add(out, item);
            }
        } else if (value instanceof String text) {
            // 业务 metadata 里的 tags / frontmatter_tags 都是逗号拼接字符串，需拆分
            out.addAll(parseTags(text));
        } else {
            add(out, value);
        }
    }

    private static void add(Set<String> out, Object value) {
        if (value == null) {
            return;
        }
        String text = String.valueOf(value).trim();
        if (!text.isEmpty()) {
            out.add(text);
        }
    }

    private static String single(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Collection<?> collection) {
            return collection.isEmpty() ? "" : String.valueOf(collection.iterator().next()).trim();
        }
        return String.valueOf(value).trim().toLowerCase(Locale.ROOT);
    }

    /** 给外部构造过滤条件时用的不可变副本。 */
    public static List<String> copyOf(Collection<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>(tags.size());
        for (String tag : tags) {
            String value = tag == null ? "" : tag.trim();
            if (!value.isEmpty() && !result.contains(value)) {
                result.add(value);
            }
        }
        return List.copyOf(result);
    }
}
