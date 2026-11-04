package com.octopus.ragmcp.mcp;

import com.octopus.ragmcp.rag.RagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 向 MCP 客户端暴露的纯文本 RAG 工具。
 *
 * <p>方法上的 Javadoc 与 {@code @McpTool} 描述会作为工具说明提供给 Agent。
 * 所有运行期故障都收敛为 {@code {"error": "..."}} JSON，避免客户端收到协议层异常。
 */
@RequiredArgsConstructor
@Slf4j
public class RagMcpTools {

    private final RagService ragService;
    private final ObjectMapper objectMapper;

    /**
     * 基于知识库检索并生成回答。
     *
     * @param query 自然语言查询
     * @param topK  参与生成的参考片段数量，缺省使用服务端默认值
     * @return 基于知识库的增强回答；知识库为空时返回“未检索到相关内容”
     */
    @McpTool(name = "rag_query", description = "基于知识库检索并生成回答；知识库为空时返回未检索到相关内容")
    public String ragQuery(
            @McpToolParam(description = "自然语言查询", required = true) String query,
            @McpToolParam(description = "参与生成的参考片段数量，缺省为 4", required = false) Integer topK) {
        log.debug("MCP rag_query 调用，query={}, topK={}", query, topK);
        return ragService.query(query, topK);
    }

    /**
     * 仅做向量检索，返回命中分块及其相似度，供需要自行控制 prompt 的 Agent 使用。
     *
     * @param query 自然语言查询
     * @param topK  返回片段数量，缺省使用服务端默认值
     * @return 包含 query、hitCount、hits 的 JSON
     */
    @McpTool(name = "rag_search", description = "对知识库做语义检索，返回命中分块（含相似度、来源元数据）")
    public String ragSearch(
            @McpToolParam(description = "自然语言查询", required = true) String query,
            @McpToolParam(description = "返回片段数量，缺省为 4", required = false) Integer topK) {
        log.debug("MCP rag_search 调用，query={}, topK={}", query, topK);
        try {
            List<Document> documents = ragService.search(query, topK);
            List<Map<String, Object>> hits = new ArrayList<>(documents.size());
            for (Document document : documents) {
                Map<String, Object> hit = new LinkedHashMap<>();
                hit.put("id", document.getId());
                hit.put("score", document.getScore());
                hit.put("text", document.getText());
                hit.put("metadata", document.getMetadata());
                hits.add(hit);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("query", query);
            data.put("hitCount", hits.size());
            data.put("hits", hits);
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            log.warn("MCP rag_search 失败: query={}, 原因={}", query, e.toString());
            return error("检索失败: " + rootMessage(e));
        }
    }

    /**
     * 将一段纯文本切块后写入知识库。
     *
     * @param text     要入库的文本
     * @param metadata 文档元数据，例如 source、title
     * @return 包含 documentId 与 chunkCount 的 JSON
     */
    @McpTool(name = "rag_ingest", description = "将纯文本按分块写入知识库，可附带 source/title 等元数据")
    public String ragIngest(
            @McpToolParam(description = "要入库的文本", required = true) String text,
            @McpToolParam(description = "文档元数据键值对，可为空", required = false) Map<String, String> metadata) {
        log.debug("MCP rag_ingest 调用，textLength={}", text == null ? 0 : text.length());
        try {
            RagService.IngestResult result = ragService.ingest(text, metadata);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("documentId", result.documentId());
            data.put("chunkCount", result.chunkCount());
            return objectMapper.writeValueAsString(data);
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        } catch (Exception e) {
            log.warn("MCP rag_ingest 失败: 原因={}", e.toString());
            return error("入库失败: " + rootMessage(e));
        }
    }

    private String error(String message) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("error", message == null ? "未知错误" : message);
            return objectMapper.writeValueAsString(data);
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
