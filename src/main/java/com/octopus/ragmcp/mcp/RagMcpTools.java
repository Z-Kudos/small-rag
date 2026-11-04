package com.octopus.ragmcp.mcp;

import com.octopus.ragmcp.rag.RagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;

import java.util.Map;

/**
 * 向 MCP 客户端暴露的 RAG 工具。
 *
 * <p>方法上的 Javadoc 与 {@code @McpTool} 描述会作为工具说明提供给 Agent。
 */
@RequiredArgsConstructor
@Slf4j
public class RagMcpTools {

    private final RagService ragService;

    /**
     * 基于知识库检索并生成回答。
     *
     * @param query 自然语言查询
     * @param topK  返回的参考片段数量，缺省使用服务端默认值
     * @return 基于知识库的增强回答；知识库为空时返回“未检索到相关内容”
     */
    @McpTool(name = "rag_query", description = "基于知识库检索并生成回答；知识库为空时返回未检索到相关内容")
    public String ragQuery(
            @McpToolParam(description = "自然语言查询", required = true) String query,
            @McpToolParam(description = "返回的参考片段数量，缺省为 4", required = false) Integer topK) {
        log.debug("MCP rag_query 调用，query={}, topK={}", query, topK);
        return ragService.query(query, topK);
    }

    /**
     * 将一段文本写入知识库。
     *
     * @param text     要入库的文本
     * @param metadata 文档元数据，例如 source、title
     * @return 入库结果说明（包含文档 id）
     */
    @McpTool(name = "rag_ingest", description = "将文本写入知识库，可附带 source/title 等元数据")
    public String ragIngest(
            @McpToolParam(description = "要入库的文本", required = true) String text,
            @McpToolParam(description = "文档元数据键值对，可为空", required = false) Map<String, String> metadata) {
        log.debug("MCP rag_ingest 调用，textLength={}", text == null ? 0 : text.length());
        return ragService.ingest(text, metadata);
    }
}
