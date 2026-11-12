package com.smallrag.ragmcp.mcp;

import com.smallrag.ragmcp.rag.RagService;
import com.smallrag.ragmcp.rag.edit.ChunkEditService;
import com.smallrag.ragmcp.rag.graph.ChunkGraph;
import com.smallrag.ragmcp.rag.reindex.ReindexTaskService;
import com.smallrag.ragmcp.rag.sources.SourceSyncService;
import com.smallrag.ragmcp.rag.vectorstore.ChunkCatalog;
import com.smallrag.ragmcp.rag.vectorstore.ChunkSummary;
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
import java.util.Set;

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
    private final SourceSyncService sourceSyncService;
    private final ChunkEditService chunkEditService;
    private final ChunkCatalog chunkCatalog;
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
            @McpToolParam(description = "Parent-Child 策略下是否展开为去重后的父块，缺省 true；传 false 返回原始子块命中", required = false) Boolean expand,
            @McpToolParam(description = "true 时附加命中片段的结构（节点/边 JSON）：含文档、章节与命中 chunk；用于 Agent 理解上下文", required = false) Boolean expandGraph) {
        log.debug("MCP rag_search 调用，query={}, topK={}, expand={}, expandGraph={}", query, topK, expand, expandGraph);
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
            if (Boolean.TRUE.equals(expandGraph)) {
                data.put("graph", searchGraph(hits));
            }
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
            sourceSyncService.removeFromState(documentId);
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
    @McpTool(name = "rag_task_cancel", description = "取消重建任务：运行中为协作式（当前文档处理完后停止）；interrupted 任务立即置为 cancelled 并释放写锁")
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

    /**
     * 异步触发一次目录导入：扫描配置的多个 Markdown 源目录，按文件指纹增量解析、切分并建立索引。
     * 立即返回受理结果，用 {@code rag_sync_status} 查询进度与统计。
     *
     * @param force true 时忽略文件指纹，重新切分所有源文件；默认 false
     * @return 受理结果（accepted/runId/message）
     */
    @McpTool(name = "rag_sync_sources", description = "异步扫描配置的多个 Markdown 源目录：按文件指纹增量解析、切分并建立索引；force=true 忽略指纹重新切分全部文件；立即返回，用 rag_sync_status 查询进度")
    public String ragSyncSources(
            @McpToolParam(description = "true 时忽略文件指纹强制重新切分；默认 false（仅处理新增/变更文件）", required = false) Boolean force) {
        log.debug("MCP rag_sync_sources 调用，force={}", force);
        try {
            return objectMapper.writeValueAsString(sourceSyncService.syncAsync(Boolean.TRUE.equals(force)));
        } catch (Exception e) {
            log.warn("MCP rag_sync_sources 失败: 原因={}", e.toString());
            return error("目录导入失败: " + rootMessage(e));
        }
    }

    /**
     * 查询目录导入状态：是否启用/运行/排队、最近一次触发与扫描统计、错误。
     *
     * @return 状态 JSON；未启用时 {@code enabled=false}
     */
    @McpTool(name = "rag_sync_status", description = "查询目录导入状态：是否启用/运行/排队、最近一次触发、扫描统计与错误")
    public String ragSyncStatus() {
        log.debug("MCP rag_sync_status 调用");
        try {
            return objectMapper.writeValueAsString(sourceSyncService.status());
        } catch (Exception e) {
            log.warn("MCP rag_sync_status 失败: 原因={}", e.toString());
            return error("查询目录导入状态失败: " + rootMessage(e));
        }
    }

    private String ingestResult(RagService.IngestResult result) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("documentId", result.documentId());
        data.put("chunkCount", result.chunkCount());
        data.put("parentCount", result.parentCount());
        return objectMapper.writeValueAsString(data);
    }

    /**
     * 列出某文档的 chunk 树（含正文前 200 字符、父子关系、heading 路径、链接）。
     *
     * @param documentId 文档 id（UUID），来自 rag_search 命中的 doc_id
     * @param limit      最多返回的 chunk 数，默认 200，最大 500
     * @return 包含 documentId、count、chunks 的 JSON；chunks 中 pointId 可用于 rag_get_chunk/rag_edit_chunk/rag_delete_chunk
     */
    @McpTool(name = "rag_chunks", description = "列出某文档的 chunk 树（含点 id、index、类型、heading_path、links），用于 Agent 定位要编辑的 chunk")
    public String ragChunks(
            @McpToolParam(description = "文档 id（UUID），来自 rag_search/rag_list 的 doc_id", required = true) String documentId,
            @McpToolParam(description = "最多返回的 chunk 数，默认 200，最大 500", required = false) Integer limit) {
        try {
            List<ChunkSummary> chunks = chunkCatalog.listChunks(documentId);
            int effective = limit == null ? 200 : Math.max(1, Math.min(500, limit));
            List<Map<String, Object>> items = new ArrayList<>(Math.min(chunks.size(), effective));
            List<ChunkSummary> ordered = new ArrayList<>(chunks);
            ordered.sort(java.util.Comparator
                    .comparingInt((ChunkSummary c) -> intField(c.metadata(), "chunk_index"))
                    .thenComparingInt(c -> intField(c.metadata(), "parent_index"))
                    .thenComparing(ChunkSummary::pointId));
            for (ChunkSummary chunk : ordered) {
                if (items.size() >= effective) {
                    break;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("pointId", chunk.pointId());
                item.put("type", stringField(chunk.metadata(), "chunk_type"));
                item.put("parentId", stringField(chunk.metadata(), "parent_id"));
                item.put("parentIndex", chunk.metadata().get("parent_index"));
                item.put("chunkIndex", chunk.metadata().get("chunk_index"));
                item.put("headingPath", stringField(chunk.metadata(), "heading_path"));
                item.put("text", snippet(chunk.text(), 200));
                item.put("links", chunk.metadata().get("links"));
                items.add(item);
            }
            return objectMapper.writeValueAsString(Map.of(
                    "documentId", documentId == null ? "" : documentId,
                    "count", items.size(),
                    "truncated", chunks.size() > items.size(),
                    "chunks", items));
        } catch (Exception e) {
            log.warn("MCP rag_chunks 失败: documentId={}, 原因={}", documentId, e.toString());
            return error("列出 chunk 失败: " + rootMessage(e));
        }
    }

    /**
     * 子图查询：以 seed 为中心展开至多 depth 跳（depth 默认 1，最大 2）。
     *
     * @param seed  种子：{@code doc:<docId>}、{@code chunk:<pointId>}、{@code section|<docId>|<headingPath>}，
     *              或裸的文档 UUID / heading_path（包含">"时按章节匹配）
     * @param depth 扩展步数，默认 1，最大 2
     * @param limit 节点数上限，默认 500，最大 5000
     * @return 包含 nodes、edges、warnings 的 JSON；nodes 不含正文，用 rag_get_chunk 看正文
     */
    @McpTool(name = "rag_graph", description = "以 seed（doc:<id> / chunk:<pointId> / section|<...> / heading_path）为中心展开子图，返回 nodes/edges JSON")
    public String ragGraph(
            @McpToolParam(description = "种子节点：doc:<uuid>、chunk:<pointId>、section|<docId>|<headingPath>，或裸文档 UUID/heading_path", required = true) String seed,
            @McpToolParam(description = "扩展步数，默认 1，最大 2", required = false) Integer depth,
            @McpToolParam(description = "节点数上限，默认 500，最大 5000", required = false) Integer limit) {
        try {
            ChunkGraph.BuildResult full = ChunkGraph.build(chunkCatalog.listChunks(null));
            if (full.nodes().isEmpty()) {
                return "{\"nodes\":[],\"edges\":[],\"warnings\":[],\"note\":\"知识库为空\"}";
            }
            int effectiveLimit = limit == null ? 500 : Math.max(1, Math.min(5000, limit));
            return ChunkGraph.expand(full, seed, depth == null ? 1 : depth, effectiveLimit)
                    .map(this::graphJson)
                    .orElseGet(() -> "{\"error\":\"seed 不匹配任何节点: \" + seed + \"\"}");
        } catch (Exception e) {
            log.warn("MCP rag_graph 失败: seed={}, 原因={}", seed, e.toString());
            return error("图查询失败: " + rootMessage(e));
        }
    }

    /**
     * 导出全库图 JSON（nodes/edges）：documents 层、sections 层、chunks 层。
     *
     * @param limit 节点数上限，默认 1000，最大 5000；超限截断并标 truncated=true
     * @return 包含 nodes、edges、warnings、truncated 的 JSON
     */
    @McpTool(name = "rag_graph_export", description = "导出全库切片图 JSON（documents/sections/chunks 三层 + contains/section/parent_of/links_to 边），供外部可视化")
    public String ragGraphExport(
            @McpToolParam(description = "节点数上限，默认 1000，最大 5000", required = false) Integer limit) {
        try {
            ChunkGraph.BuildResult full = ChunkGraph.build(chunkCatalog.listChunks(null));
            int effectiveLimit = limit == null ? 1000 : Math.max(1, Math.min(5000, limit));
            boolean truncated = full.nodes().size() > effectiveLimit;
            List<ChunkGraph.GraphNode> nodes = truncated
                    ? full.nodes().subList(0, effectiveLimit) : full.nodes();
            Set<String> nodeIds = new java.util.HashSet<>(nodes.size());
            for (ChunkGraph.GraphNode node : nodes) {
                nodeIds.add(node.id());
            }
            List<ChunkGraph.GraphEdge> edges = full.edges().stream()
                    .filter(edge -> nodeIds.contains(edge.from()) && nodeIds.contains(edge.to()))
                    .toList();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("nodes", nodes);
            out.put("edges", edges);
            out.put("warnings", full.warnings());
            out.put("truncated", truncated);
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            log.warn("MCP rag_graph_export 失败: 原因={}", e.toString());
            return error("图导出失败: " + rootMessage(e));
        }
    }

    /**
     * 读取单个 chunk 的完整内容与元数据（含 links、heading_path、parent_id）。
     *
     * @param pointId chunk 的点 id（来自 rag_chunks 输出的 pointId，或 rag_search 命中的 id）
     * @return 包含 pointId、text、docId、chunkType、parentId、links 的 JSON
     */
    @McpTool(name = "rag_get_chunk", description = "读取单个 chunk 的正文与元数据；编辑前必读，用于生成 expectedText")
    public String ragGetChunk(
            @McpToolParam(description = "chunk 点 id（pointId），来自 rag_chunks / rag_search 的 id", required = true) String pointId) {
        try {
            return chunkEditService.getChunk(pointId);
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        } catch (Exception e) {
            log.warn("MCP rag_get_chunk 失败: pointId={}, 原因={}", pointId, e.toString());
            return error("读取 chunk 失败: " + rootMessage(e));
        }
    }

    /**
     * 编辑某个 chunk 的正文（回写归档原文，按当前切分签名整篇重切）。
     *
     * <p>{@code expectedText} 非空时必须与当前 chunk 内容一致，否则拒绝；用于防止基于过期快照覆盖更新的内容。
     *
     * @param pointId     目标 chunk 的点 id
     * @param newText     要替换的新正文（可为空串，代表删除该片段内容）
     * @param expectedText 非空时必须与当前 chunk 内容完全一致；不一致返回错误并附当前内容
     * @return 包含 documentId、chunkCount、parentCount、chunkingSignature 的 JSON 回执
     */
    @McpTool(name = "rag_edit_chunk", description = "编辑 chunk 正文：expectedText 与当前内容必须一致；回写归档后按当前切分签名整篇重切")
    public String ragEditChunk(
            @McpToolParam(description = "目标 chunk 的 pointId（来自 rag_chunks / rag_get_chunk）", required = true) String pointId,
            @McpToolParam(description = "要替换的新正文（可为空串代表清空该片段）", required = true) String newText,
            @McpToolParam(description = "非空时必须与当前 chunk 内容完全一致；不一致则拒绝并返回当前内容", required = false) String expectedText) {
        try {
            return chunkEditService.editChunk(pointId, newText, expectedText);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return error(e.getMessage());
        } catch (Exception e) {
            log.warn("MCP rag_edit_chunk 失败: pointId={}, 原因={}", pointId, e.toString());
            return error("编辑 chunk 失败: " + rootMessage(e));
        }
    }

    /**
     * 从归档原文删除某个 chunk 对应的文本段（后整篇重切）。
     *
     * <p>必须显式 {@code confirm=true}，与 rag_rebuild 一致；不可撤销（归档有原文副本，可重新入库恢复）。
     *
     * @param pointId     目标 chunk 的点 id
     * @param confirm     必须为 true 才执行
     * @param expectedText 非空时必须与当前 chunk 内容一致
     * @return 包含 documentId、chunkCount、parentCount 的 JSON 回执
     */
    @McpTool(name = "rag_delete_chunk", description = "删除某个 chunk（从归档原文删除该段并重切）；必须 confirm=true；不可撤销")
    public String ragDeleteChunk(
            @McpToolParam(description = "目标 chunk 的 pointId", required = true) String pointId,
            @McpToolParam(description = "必须为 true 才执行删除；否则返回错误说明", required = true) Boolean confirm,
            @McpToolParam(description = "非空时必须与当前 chunk 内容完全一致；不一致则拒绝并返回当前内容", required = false) String expectedText) {
        try {
            return chunkEditService.deleteChunk(pointId, Boolean.TRUE.equals(confirm), expectedText);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return error(e.getMessage());
        } catch (Exception e) {
            log.warn("MCP rag_delete_chunk 失败: pointId={}, 原因={}", pointId, e.toString());
            return error("删除 chunk 失败: " + rootMessage(e));
        }
    }

    private String graphJson(ChunkGraph.BuildResult result) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("nodes", result.nodes());
        data.put("edges", result.edges());
        data.put("warnings", result.warnings());
        try {
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            throw new IllegalStateException("序列化图结果失败: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> searchGraph(List<Map<String, Object>> hits) {
        Map<String, Object> graph = new LinkedHashMap<>();
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (Map<String, Object> hit : hits) {
            Map<String, Object> metadata = hitMetadata(hit);
            if (metadata == null) {
                continue;
            }
            String docId = String.valueOf(metadata.getOrDefault("doc_id", ""));
            String id = String.valueOf(hit.get("id"));
            putNode(nodes, seen, Map.of(
                    "id", id, "type", "chunk",
                    "doc_id", docId,
                    "heading_path", metadata.get("heading_path"),
                    "parent_id", metadata.get("parent_id"),
                    "label", snippet(String.valueOf(hit.get("text")), 120)));
            if (!docId.isBlank()) {
                String docNodeId = "document:" + docId;
                putNode(nodes, seen, Map.of("id", docNodeId, "type", "document", "doc_id", docId));
                edges.add(Map.of("from", docNodeId, "to", id, "type", "contains"));
                String heading = stringField(metadata, "heading_path");
                if (heading != null) {
                    String sectionId = "section|" + docId + "|" + heading;
                    putNode(nodes, seen, Map.of("id", sectionId, "type", "section",
                            "doc_id", docId, "heading_path", heading));
                    edges.add(Map.of("from", sectionId, "to", id, "type", "section"));
                }
            }
        }
        graph.put("nodes", nodes);
        graph.put("edges", edges);
        return graph;
    }

    private static void putNode(List<Map<String, Object>> nodes, List<String> seen, Map<String, Object> node) {
        String id = String.valueOf(node.get("id"));
        if (!seen.contains(id)) {
            seen.add(id);
            nodes.add(node);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> hitMetadata(Map<String, Object> hit) {
        Object metadata = hit.get("metadata");
        return metadata instanceof Map ? (Map<String, Object>) metadata : null;
    }

    private static int intField(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String string) {
            try {
                return Integer.parseInt(string.trim());
            } catch (NumberFormatException e) {
                return Integer.MAX_VALUE;
            }
        }
        return Integer.MAX_VALUE;
    }

    private static String stringField(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value instanceof String string && !string.isBlank() ? string : null;
    }

    private static String snippet(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String stripped = text.strip().replaceAll("\s+", " ");
        return stripped.length() <= maxChars ? stripped : stripped.substring(0, maxChars) + "…";
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
