package com.smallrag.ragmcp.rag.graph;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 跨文档链接解析：把片段中的相对/绝对目标解析为已归档文档的 doc_id。
 *
 * <p>解析规则（Obsidian 语义）：
 * <ol>
 *   <li>目标本身就是绝对路径且命中某个文档的 {@code source_path} → 命中；</li>
 *   <li>否则以<b>当前文档所在目录</b>为基准解析相对路径 → 命中；</li>
 *   <li>否则按后缀匹配（{@code /<target>} 或等于 target）查找：命中 1 个 → 唯一命中；命中 ≥2 → <em>ambiguous</em>（附候选 doc_id）；</li>
 *   <li>零命中 → 不产生边。</li>
 * </ol>
 *
 * @author zjk
 */
public final class LinkResolver {

    /** 解析结果：目标命中的文档 id；ambiguous 表示有多个候选。 */
    public record Result(List<String> docIds, boolean ambiguous) {

        static Result single(String docId) {
            return new Result(List.of(docId), false);
        }

        /** @return 是否有任意命中 */
        public boolean found() {
            return docIds != null && !docIds.isEmpty();
        }

        /** @return 唯一命中或为空时返回第一个；ambiguous 返回 null */
        public String unique() {
            if (docIds == null || docIds.size() != 1) {
                return null;
            }
            return docIds.get(0);
        }
    }

    private LinkResolver() {
    }

    /**
     * @param target            片段中的链接目标（已去除锚点/查询参数、URL decode）
     * @param currentSourcePath 当前文档的归档路径（可能为 null：rag_ingest 文档无路径）
     * @param pathsByDocId      归档文档的 source_path（POSIX 规范化后）→ doc_id
     */
    public static Result resolve(String target, String currentSourcePath,
            Map<String, String> pathsByDocId) {
        String normalizedTarget = normalize(toPosix(target));
        if (normalizedTarget.isBlank()) {
            return new Result(List.of(), false);
        }
        // 1) 绝对路径直接命中
        String exact = lookupByPath(pathsByDocId, normalizedTarget);
        if (exact != null) {
            return Result.single(exact);
        }
        // 2) 以当前文档所在目录为基准解析相对路径
        if (currentSourcePath != null && !currentSourcePath.isBlank()) {
            String parent = Path.of(currentSourcePath).getParent() == null
                    ? "" : Path.of(currentSourcePath).getParent().toString();
            String resolved = normalize(join(parent, normalizedTarget));
            String fromCurrentDir = lookupByPath(pathsByDocId, resolved);
            if (fromCurrentDir != null) {
                return Result.single(fromCurrentDir);
            }
            // 相对当前目录也可能带 ./
            String resolvedFromDot = normalize(join(parent, "./" + normalizedTarget));
            String dotted = lookupByPath(pathsByDocId, resolvedFromDot);
            if (dotted != null) {
                return Result.single(dotted);
            }
        }
        // 3) 后缀匹配（按 "/" + target 结尾）
        String suffix = "/" + normalizedTarget;
        List<String> suffixMatches = pathsByDocId.keySet().stream()
                .filter(path -> path.endsWith(suffix) || path.equals(normalizedTarget))
                .map(pathsByDocId::get)
                .distinct()
                .toList();
        if (suffixMatches.size() == 1) {
            return Result.single(suffixMatches.get(0));
        }
        if (suffixMatches.size() > 1) {
            return new Result(suffixMatches, true);
        }
        return new Result(List.of(), false);
    }

    private static String lookupByPath(Map<String, String> pathsByDocId, String absolutePath) {
        String normalized = normalize(toPosix(absolutePath));
        return pathsByDocId.get(normalized);
    }

    /** POSIX 化并折叠多余分隔符。 */
    public static String toPosix(String path) {
        if (path == null) {
            return "";
        }
        String posix = path.replace('\\', '/');
        while (posix.contains("//")) {
            posix = posix.replace("//", "/");
        }
        return posix;
    }

    /** 归一化：拆分、去除 . 与 ..、去前导 ./ */
    public static String normalize(String path) {
        String posix = toPosix(path);
        if (posix.isBlank()) {
            return posix;
        }
        String[] segments = posix.split("/");
        java.util.Deque<String> stack = new java.util.ArrayDeque<>();
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                stack.pollLast();
                continue;
            }
            stack.addLast(segment);
        }
        String joined = String.join("/", stack);
        String root = posix.startsWith("/") ? "/" : "";
        return root + joined;
    }

    private static String join(String parent, String child) {
        if (parent == null || parent.isBlank()) {
            return child;
        }
        if (parent.endsWith("/")) {
            return parent + child;
        }
        return parent + "/" + child;
    }
}
