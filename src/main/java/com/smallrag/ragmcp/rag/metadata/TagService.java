package com.smallrag.ragmcp.rag.metadata;

import com.smallrag.ragmcp.rag.RagService;
import com.smallrag.ragmcp.rag.archive.ArchivedDocument;
import com.smallrag.ragmcp.rag.archive.DocumentArchive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 文档分类 / 标签管理。
 *
 * <p>标签写入归档元数据后按当前切分签名整篇重切，因此新标签会立即生效于
 * {@code rag_search} / {@code rag_query} 的 {@code tags} 与 {@code category} 过滤。
 *
 * @author zjk
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TagService {

    private final RagService ragService;
    private final DocumentArchive documentArchive;

    /**
     * 设置（或清空）一篇文档的标签与分类。
     *
     * @param documentId 文档 id（UUID）
     * @param tags       逗号分隔的标签；传空字符串表示清空标签
     * @param category   分类；传空字符串表示清空分类
     * @return 重切后的片段统计
     */
    public RagService.IngestResult setTags(String documentId, String tags, String category) {
        ArchivedDocument archived = documentArchive.find(documentId)
                .orElseThrow(() -> new IllegalArgumentException("归档文档不存在: " + documentId));

        Map<String, String> metadata = new LinkedHashMap<>(archived.metadata());
        if (tags != null) {
            metadata.put(DocumentTags.FIELD_TAGS, String.join(",", DocumentTags.parseTags(tags)));
        }
        if (category != null) {
            String value = category.trim();
            if (value.isEmpty()) {
                metadata.remove(DocumentTags.FIELD_CATEGORY);
            } else {
                metadata.put(DocumentTags.FIELD_CATEGORY, value);
            }
        }
        log.info("更新文档标签: docId={}, tags={}, category={}", documentId, tags, category);
        return ragService.update(documentId, archived.text(), metadata);
    }

    /**
     * 统计知识库中全部标签与分类的使用量（只读取归档摘要，不加载原文）。
     *
     * @return 包含 tags / categories 列表的统计
     */
    public Map<String, Object> listTags() {
        Map<String, Integer> tagCounts = new TreeMap<>();
        Map<String, Integer> categoryCounts = new TreeMap<>();
        for (var summary : documentArchive.listSummaries()) {
            Map<String, String> metadata = summary.metadata();
            if (metadata == null) {
                continue;
            }
            Set<String> tags = new LinkedHashSet<>(DocumentTags.parseTags(metadata.get(DocumentTags.FIELD_TAGS)));
            tags.addAll(DocumentTags.parseTags(metadata.get(DocumentTags.FIELD_FRONTMATTER_TAGS)));
            for (String tag : tags) {
                tagCounts.merge(tag, 1, Integer::sum);
            }
            String category = metadata.get(DocumentTags.FIELD_CATEGORY);
            if (category != null && !category.isBlank()) {
                categoryCounts.merge(category.trim(), 1, Integer::sum);
            }
        }

        List<Map<String, Object>> tagItems = new ArrayList<>();
        tagCounts.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed()
                        .thenComparing(Map.Entry::getKey))
                .forEach(entry -> tagItems.add(Map.of("name", entry.getKey(), "count", entry.getValue())));
        List<Map<String, Object>> categoryItems = new ArrayList<>();
        categoryCounts.forEach((name, count) -> categoryItems.add(Map.of("name", name, "count", count)));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tags", tagItems);
        result.put("categories", categoryItems);
        return result;
    }
}
