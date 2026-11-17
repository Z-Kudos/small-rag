package com.smallrag.ragmcp.mcp;

import com.smallrag.ragmcp.rag.retention.RetentionCleanupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * 向量数据有效期（TTL）管理 MCP 工具。
 *
 * <p>由 {@code McpServerConfig} 显式注册；与定时清理共用
 * {@link RetentionCleanupService}，并通过索引活动锁避免与重建 / 目录导入并发。
 *
 * @author zjk
 */
@RequiredArgsConstructor
@Slf4j
public class RetentionMcpTools {

    private final RetentionCleanupService cleanupService;
    private final ObjectMapper objectMapper;

    /**
     * 手动清理过期向量数据。默认预演（dryRun=true）；要实际删除必须同时传 confirm=true。
     *
     * @param dryRun      true 时只统计不删除，默认 true
     * @param ttlSeconds  覆盖配置中的 TTL（秒）；null 或非正数时使用配置值
     * @param confirm     dryRun=false 时必须显式传 true
     * @return 清理统计 JSON（scanned/expired/deleted/failed 等）
     */
    @McpTool(name = "rag_cleanup_expired", description = "清理过期向量数据：默认预演不删除；实际删除需 dryRun=false 且 confirm=true；ttlSeconds 可临时覆盖配置")
    public String ragCleanupExpired(
            @McpToolParam(description = "true=只统计不删除（默认）；false=实际删除", required = false) Boolean dryRun,
            @McpToolParam(description = "临时覆盖 TTL（秒），<=0 时使用配置值", required = false) Integer ttlSeconds,
            @McpToolParam(description = "dryRun=false 时必须显式传 true，防止误删", required = false) Boolean confirm) {
        boolean preview = !Boolean.FALSE.equals(dryRun);
        if (!preview && !Boolean.TRUE.equals(confirm)) {
            return error("实际删除必须显式传 confirm=true（或保持 dryRun=true 先预演）");
        }
        try {
            Long override = ttlSeconds != null && ttlSeconds > 0 ? ttlSeconds.longValue() : null;
            return objectMapper.writeValueAsString(cleanupService.cleanup(preview, override));
        } catch (Exception e) {
            log.warn("MCP rag_cleanup_expired 失败: 原因={}", e.toString());
            return error("清理失败: " + rootMessage(e));
        }
    }

    /**
     * 查询 TTL 配置与最近一次清理结果。
     *
     * @return 配置与 lastRun 的 JSON
     */
    @McpTool(name = "rag_retention_status", description = "查询向量数据有效期配置（启用状态、TTL、间隔、是否删归档）与最近一次清理统计")
    public String ragRetentionStatus() {
        try {
            return objectMapper.writeValueAsString(cleanupService.status());
        } catch (Exception e) {
            log.warn("MCP rag_retention_status 失败: 原因={}", e.toString());
            return error("查询有效期状态失败: " + rootMessage(e));
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
