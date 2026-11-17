package com.smallrag.ragmcp.mcp;

import com.smallrag.ragmcp.rag.RagService;
import com.smallrag.ragmcp.rag.metadata.TagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 文档分类 / 标签管理 MCP 工具。
 *
 * <p>与 {@link RagMcpTools} 一样由 {@code McpServerConfig} 显式注册；
 * 所有异常收敛为 {@code {"error": "..."}}，避免客户端收到协议层异常。
 *
 * @author zjk
 */
@RequiredArgsConstructor
@Slf4j
public class TagMcpTools {

    private final TagService tagService;
    private final ObjectMapper objectMapper;

    /**
     * 设置或清空一篇文档的标签与分类。标签会写入归档元数据并按当前切分签名整篇重切。
     *
     * @param documentId 文档 id（UUID），来自 rag_search / rag_list
     * @param tags       逗号分隔的标签，如 "架构,部署"；传空字符串表示清空标签
     * @param category   分类（单值），如 "运维"；传空字符串表示清空分类
     * @return 包含 documentId、chunkCount、parentCount 的 JSON
     */
    @McpTool(name = "rag_set_tags", description = "设置文档的分类与标签（逗号分隔），写入归档后整篇重切，使 rag_search/rag_query 可按 tags/category 过滤")
    public String ragSetTags(
            @McpToolParam(description = "文档 id（UUID），来自 rag_search / rag_list", required = true) String documentId,
            @McpToolParam(description = "逗号分隔的标签，如 '架构,部署'；传空串清空", required = true) String tags,
            @McpToolParam(description = "分类（单值），如 '运维'；传空串清空；可为 null 表示不改动", required = false) String category) {
        try {
            RagService.IngestResult result = tagService.setTags(documentId, tags, category);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("documentId", result.documentId());
            data.put("chunkCount", result.chunkCount());
            data.put("parentCount", result.parentCount());
            return objectMapper.writeValueAsString(data);
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        } catch (Exception e) {
            log.warn("MCP rag_set_tags 失败: documentId={}, 原因={}", documentId, e.toString());
            return error("设置标签失败: " + rootMessage(e));
        }
    }

    /**
     * 列出知识库中的全部标签与分类及各自文档数（只读取归档摘要，不加载原文）。
     *
     * @return 包含 tags 与 categories 两个计数列表的 JSON
     */
    @McpTool(name = "rag_list_tags", description = "列出知识库全部分类与标签及文档数，供检索过滤前查看可用标签")
    public String ragListTags() {
        try {
            return objectMapper.writeValueAsString(tagService.listTags());
        } catch (Exception e) {
            log.warn("MCP rag_list_tags 失败: 原因={}", e.toString());
            return error("查询标签失败: " + rootMessage(e));
        }
    }

    private String error(String message) {
        try {
            return objectMapper.writeValueAsString(Map.of("error", message == null ? "未知错误" : message));
        } catch (Exception e) {
            return "{\"error\":\"未知错误\"}";
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return (message == null || message.isBlank()) ? cause.getClass().getSimpleName() : message;
    }
}
