package com.smallrag.ragmcp.mcp;

import com.smallrag.ragmcp.rag.RagService;
import com.smallrag.ragmcp.rag.ReindexTaskService;
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
 * <p>只依赖向量（Embedding）模型，不调用 Chat / 生成模型；{@code rag_query} 返回
 * 检索增强上下文，由调用方 Agent 自行生成回答。
 *
 * <p>方法上的 Javadoc 与 {@code @McpTool} 描述会作为工具说明提供给 Agent。
 * 所有运行期故障都收敛为 {@code {"error": "..."}} JSON，避免客户端收到协议层异常。
 *
 * @author zjk
 */
@RequiredArgsConstructor
@Slf4j
public class RagMcpTools {

    private final RagService ragService;
    private final ReindexTaskService reindexTaskService;
    private final ObjectMapper objectMapper;

    /**
     * 检索知识库并返回增强上下文（不调用生成模型）。
     *
     * @param query 自然语言查询
     * @param topK  返回的参考片段数量，缺省使用服务端默认值
     * @return 带编号、来源与相似度的上下文；知识库为空时返回“未检索到相关内容”
     */
    @McpTool(name = "rag_query", description = "检索知识库并返回带来源的增强上下文；不调用生成模型，空库返回未检索到相关内容")
    public String ragQuery(
            @McpToolParam(description = "自然语言查询", required = true) String query,
            @McpToolParam(description = "返回的参考片段数量，缺省为 4", required = false) Integer topK) {
        log.debug("MCP rag_query 调用，query={}, topK={}", query, topK);
        return ragService.query(query, topK);
    }

    /**
     * 仅做向量检索，返回命中分块及其相似度，供需要自行控制 prompt 的 Agent 使用。
     *
     * @param query  自然语言查询
     * @param topK   返回片段数量，缺省使用服务端默认值
     * @param expand Parent-Child 策略下是否展开为去重后的父块结果
     * @return 包含 query、hitCount、hits 的 JSON
     */
    @McpTool(name = "rag_search", description = "对知识库做语义检索，返回命中分块（含相似度、来源元数据）；Parent-Child 策略下默认按父块去重返回")
    public String ragSearch(
            @McpToolParam(description = "自然语言查询", required = true) String query,
            @McpToolParam(description = "返回片段数量，缺省为 4", required = false) Integer topK,
            @McpToolParam(description = "Parent-Child 策略下是否展开为去重后的父块，缺省 true；传 false 返回原始子块命中", required = false) Boolean expand) {
        log.debug("MCP rag_search 调用，query={}, topK={}, expand={}", query, topK, expand);
        try {
            List<Document> documents = ragService.search(query, topK, !Boolean.FALSE.equals(expand));
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
     * 将一段纯文本按配置的切分策略切块后写入知识库。
     *
     * @param text     要入库的文本
     * @param metadata 文档元数据，例如 source、title
     * @return 包含 documentId、chunkCount 与 parentCount 的 JSON
     */
    @McpTool(name = "rag_ingest", description = "新增文档：按配置的切分策略写入知识库并归档原文，可附带 source/title 等元数据")
    public String ragIngest(
            @McpToolParam(description = "要入库的文本", required = true) String text,
            @McpToolParam(description = "文档元数据键值对，可为空", required = false) Map<String, String> metadata) {
        log.debug("MCP rag_ingest 调用，textLength={}", text == null ? 0 : text.length());
        try {
            return ingestResult(ragService.ingest(text, metadata));
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        } catch (Exception e) {
            log.warn("MCP rag_ingest 失败: 原因={}", e.toString());
            return error("入库失败: " + rootMessage(e));
        }
    }

    /**
     * 更新已归档文档的文本与元数据，并立即按当前切分策略重建向量。
     *
     * @param documentId 文档 id（UUID）
     * @param text       新的完整文本
     * @param metadata   新元数据；不传时保留原元数据
     * @return 更新后的 documentId、chunkCount、parentCount
     */
    @McpTool(name = "rag_update", description = "更新已归档文档的文本或元数据，并立即按当前策略重建该文档的向量")
    public String ragUpdate(
            @McpToolParam(description = "文档 id（UUID）", required = true) String documentId,
            @McpToolParam(description = "新的完整文本", required = true) String text,
            @McpToolParam(description = "新元数据；不传则保留原元数据", required = false) Map<String, String> metadata) {
        log.debug("MCP rag_update 调用，documentId={}, textLength={}", documentId, text == null ? 0 : text.length());
        try {
            return ingestResult(ragService.update(documentId, text, metadata));
        } catch (Exception e) {
            log.warn("MCP rag_update 失败: documentId={}, 原因={}", documentId, e.toString());
            return error("更新失败: " + rootMessage(e));
        }
    }

    /**
     * 删除文档的全部向量和原文归档。
     *
     * @param documentId 文档 id（UUID）
     * @return 删除结果
     */
    @McpTool(name = "rag_delete", description = "按 documentId 删除文档的全部向量和原文归档")
    public String ragDelete(
            @McpToolParam(description = "文档 id（UUID）", required = true) String documentId) {
        log.debug("MCP rag_delete 调用，documentId={}", documentId);
        try {
            ragService.delete(documentId);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("documentId", documentId);
            data.put("deleted", true);
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            log.warn("MCP rag_delete 失败: documentId={}, 原因={}", documentId, e.toString());
            return error("删除失败: " + rootMessage(e));
        }
    }

    /**
     * 分页列出已归档文档。
     *
     * @param limit  每页数量，默认 20，最大 100
     * @param offset 偏移量，默认 0
     * @return 文档摘要分页 JSON
     */
    @McpTool(name = "rag_list", description = "分页列出已归档文档的 id、元数据、切分签名和文本长度")
    public String ragList(
            @McpToolParam(description = "每页数量，默认 20，最大 100", required = false) Integer limit,
            @McpToolParam(description = "偏移量，默认 0", required = false) Integer offset) {
        log.debug("MCP rag_list 调用，limit={}, offset={}", limit, offset);
        try {
            return objectMapper.writeValueAsString(ragService.listDocuments(limit, offset));
        } catch (Exception e) {
            log.warn("MCP rag_list 失败: 原因={}", e.toString());
            return error("查询失败: " + rootMessage(e));
        }
    }

    /**
     * 手动重建向量：指定 documentId 时同步重建单篇；不传时提交异步任务重建全部归档文档。
     *
     * @param documentId 文档 id；为空表示全部
     * @return 单篇返回片段数；全部返回任务快照（含 taskId），用 rag_task_status 查询进度
     */
    @McpTool(name = "rag_reindex", description = "手动重建向量：传 documentId 同步重建单篇；不传则提交异步任务重建全部已归档文档，返回 taskId，用 rag_task_status 查询进度")
    public String ragReindex(
            @McpToolParam(description = "文档 id（UUID）；为空表示异步重建全部", required = false) String documentId) {
        log.debug("MCP rag_reindex 调用，documentId={}", documentId);
        try {
            if (documentId == null || documentId.isBlank()) {
                return objectMapper.writeValueAsString(reindexTaskService.submit("reindex", false));
            }
            return ingestResult(ragService.reindexDocument(documentId));
        } catch (Exception e) {
            log.warn("MCP rag_reindex 失败: documentId={}, 原因={}", documentId, e.toString());
            return error("重建失败: " + rootMessage(e));
        }
    }

    /**
     * 全量重建：清空当前集合中由本服务写入的片段，再按归档异步重建全部文档。
     *
     * @param confirm 必须为 true，防止误清空知识库
     * @return 任务快照（含 taskId），用 rag_task_status 查询进度
     */
    @McpTool(name = "rag_rebuild", description = "全量重建：清空当前集合中由 small-rag 写入的片段后按归档异步重建；必须 confirm=true，返回 taskId，用 rag_task_status 查询进度")
    public String ragRebuild(
            @McpToolParam(description = "必须显式传 true，防止误清空知识库", required = true) Boolean confirm) {
        log.debug("MCP rag_rebuild 调用，confirm={}", confirm);
        if (!Boolean.TRUE.equals(confirm)) {
            return error("必须传 confirm=true 才能执行全量重建");
        }
        try {
            return objectMapper.writeValueAsString(reindexTaskService.submit("rebuild", true));
        } catch (Exception e) {
            log.warn("MCP rag_rebuild 失败: 原因={}", e.toString());
            return error("全量重建失败: " + rootMessage(e));
        }
    }

    /**
     * 查询重建任务进度：传 taskId 查指定任务，不传返回最近一次任务。
     *
     * @param taskId 任务 id；为空表示最近一次任务
     * @return 任务快照（status、total、processed、percent、succeeded、failed、currentDocumentId、
     *         lastProgressAt、stalled、cancelRequested、revoked、lockReleaseAt 等）；
     *         从未执行过重建时返回 {@code status=IDLE}
     */
    @McpTool(name = "rag_task_status", description = "查询重建任务进度：传 taskId 查指定任务，不传返回最近一次任务；返回 status/total/processed/percent/stalled/cancelRequested/revoked/lockReleaseAt，无任务时 status=IDLE")
    public String ragTaskStatus(
            @McpToolParam(description = "任务 id；为空表示最近一次重建任务", required = false) String taskId) {
        log.debug("MCP rag_task_status 调用，taskId={}", taskId);
        try {
            return reindexTaskService.status(taskId)
                    .map(objectMapper::writeValueAsString)
                    .orElseGet(() -> objectMapper.writeValueAsString(Map.of("status", "IDLE")));
        } catch (Exception e) {
            log.warn("MCP rag_task_status 失败: taskId={}, 原因={}", taskId, e.toString());
            return error("查询任务失败: " + rootMessage(e));
        }
    }

    /**
     * 请求取消正在执行的重建任务；协作式，当前文档处理完后在文档边界停止。
     *
     * @param taskId 任务 id；为空表示最近一次任务
     * @return 收到取消请求后的任务快照（cancelRequested=true）
     */
    @McpTool(name = "rag_task_cancel", description = "取消重建任务：运行中为协作式（当前文档处理完后停止）；interrupted 任务立即置为 cancelled 并释放任务锁")
    public String ragTaskCancel(
            @McpToolParam(description = "任务 id；为空表示最近一次重建任务", required = false) String taskId) {
        log.debug("MCP rag_task_cancel 调用，taskId={}", taskId);
        try {
            return objectMapper.writeValueAsString(reindexTaskService.cancel(taskId));
        } catch (Exception e) {
            log.warn("MCP rag_task_cancel 失败: taskId={}, 原因={}", taskId, e.toString());
            return error("取消任务失败: " + rootMessage(e));
        }
    }

    /**
     * 续接重启前未完成的重建任务（status=interrupted），从上次成功的文档之后继续，不重新清空。
     *
     * @param taskId 任务 id；为空表示最近一次任务
     * @return 续接后的任务快照
     */
    @McpTool(name = "rag_task_resume", description = "续接重启前未完成的重建任务（status=interrupted）：跳过已成功文档继续重建，不重新清空；传 taskId 指定任务，不传表示最近一次")
    public String ragTaskResume(
            @McpToolParam(description = "任务 id；为空表示最近一次重建任务", required = false) String taskId) {
        log.debug("MCP rag_task_resume 调用，taskId={}", taskId);
        try {
            return objectMapper.writeValueAsString(reindexTaskService.resume(taskId));
        } catch (Exception e) {
            log.warn("MCP rag_task_resume 失败: taskId={}, 原因={}", taskId, e.toString());
            return error("续接任务失败: " + rootMessage(e));
        }
    }

    private String ingestResult(RagService.IngestResult result) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("documentId", result.documentId());
        data.put("chunkCount", result.chunkCount());
        data.put("parentCount", result.parentCount());
        return objectMapper.writeValueAsString(data);
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
