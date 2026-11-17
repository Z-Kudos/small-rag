package com.smallrag.ragmcp.rag.retention;

import com.smallrag.ragmcp.config.RagProperties;
import com.smallrag.ragmcp.rag.IndexActivityGuard;
import com.smallrag.ragmcp.rag.archive.ArchivedDocumentSummary;
import com.smallrag.ragmcp.rag.archive.DocumentArchive;
import com.smallrag.ragmcp.rag.sources.SourceSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 向量数据有效期（TTL）清理。
 *
 * <p>按归档摘要的 {@code updatedAt}（与写入向量的 {@code ingested_at} 同步刷新）
 * 判定是否过期，到期后删除向量片段与（可选）原文归档，并清理目录导入的墓碑状态。
 * 清理与重建 / 目录导入共用 {@link IndexActivityGuard} 串行化，避免并发写冲突。
 *
 * <p>定时清理默认关闭；MCP 手动入口 {@code rag_cleanup_expired} 始终可用。
 *
 * @author zjk
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RetentionCleanupService {

    private final RagProperties properties;
    private final VectorStore vectorStore;
    private final DocumentArchive documentArchive;
    private final SourceSyncService sourceSyncService;
    private final IndexActivityGuard activity;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "rag-retention");
        thread.setDaemon(true);
        return thread;
    });

    private final AtomicReference<CleanupReport> lastReport = new AtomicReference<>();

    /**
     * 清理结果。
     *
     * @param cutoff   使用的过期阈值（now - ttl）
     * @param scanned  扫描的归档文档数
     * @param expired  命中的过期文档数
     * @param deleted  实际删除的文档数（dryRun 时为 0）
     * @param failed   删除失败的文档数
     * @param dryRun   是否为预演
     * @param skipped  是否因活动锁被占用而跳过
     * @param message  附加说明
     */
    public record CleanupReport(Instant cutoff, int scanned, int expired, int deleted, int failed,
            boolean dryRun, boolean skipped, String message) {
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(110)
    public void onApplicationReady() {
        RagProperties.Retention retention = properties.getRetention();
        if (!retention.isEnabled() || retention.getTtlSeconds() <= 0) {
            return;
        }
        long delay = Math.max(0, retention.getStartupDelaySeconds());
        long interval = retention.getScanIntervalSeconds();
        log.info("向量数据有效期清理已启用: ttl={}s, 首次延迟={}s, 间隔={}s, deleteArchive={}",
                retention.getTtlSeconds(), delay, interval, retention.isDeleteArchive());
        scheduler.schedule(() -> safeRun(null), delay, TimeUnit.SECONDS);
        if (interval > 0) {
            scheduler.scheduleWithFixedDelay(() -> safeRun(null), delay + interval, interval, TimeUnit.SECONDS);
        }
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }

    private void safeRun(Long ttlOverride) {
        try {
            CleanupReport report = cleanup(false, ttlOverride);
            if (report.expired() > 0 || report.failed() > 0 || report.skipped()) {
                log.info("定时清理完成: {}", report);
            }
        } catch (Exception e) {
            log.warn("定时清理异常: {}", e.toString());
        }
    }

    /**
     * 执行一次 TTL 清理（定时与手动入口共用）。
     *
     * @param dryRun       true 时只统计不删除
     * @param ttlOverride  非空且为正数时覆盖配置的 TTL（秒）
     * @return 清理统计
     */
    public CleanupReport cleanup(boolean dryRun, Long ttlOverride) {
        RagProperties.Retention retention = properties.getRetention();
        long ttlSeconds = ttlOverride != null && ttlOverride > 0 ? ttlOverride : retention.getTtlSeconds();
        if (ttlSeconds <= 0) {
            CleanupReport report = new CleanupReport(null, 0, 0, 0, 0, dryRun, false, "TTL 未启用或 <= 0，未执行清理");
            lastReport.set(report);
            return report;
        }

        Instant cutoff = Instant.now().minusSeconds(ttlSeconds);
        if (!activity.tryAcquire(IndexActivityGuard.RETENTION)) {
            CleanupReport report = new CleanupReport(cutoff, 0, 0, 0, 0, dryRun, true,
                    "索引活动锁被占用（重建或目录导入进行中），跳过本轮清理");
            lastReport.set(report);
            return report;
        }

        try {
            List<ArchivedDocumentSummary> summaries = documentArchive.listSummaries();
            int scanned = summaries.size();
            int expired = 0;
            int deleted = 0;
            int failed = 0;
            int limit = retention.getMaxDocumentsPerRun();

            for (ArchivedDocumentSummary summary : summaries) {
                if (expired >= limit && limit > 0) {
                    break;
                }
                if (!isExpired(summary, cutoff)) {
                    continue;
                }
                expired++;
                if (dryRun) {
                    continue;
                }
                try {
                    vectorStore.delete(new FilterExpressionBuilder()
                            .eq("doc_id", summary.documentId())
                            .build());
                    if (retention.isDeleteArchive()) {
                        documentArchive.delete(summary.documentId());
                        sourceSyncService.removeFromState(summary.documentId());
                    }
                    deleted++;
                } catch (Exception e) {
                    failed++;
                    log.warn("清理过期文档失败: docId={}, 原因={}", summary.documentId(), e.toString());
                }
            }

            CleanupReport report = new CleanupReport(cutoff, scanned, expired, deleted, failed, dryRun, false,
                    dryRun ? "预演完成，未实际删除" : "清理完成");
            lastReport.set(report);
            return report;
        } finally {
            activity.release(IndexActivityGuard.RETENTION);
        }
    }

    /**
     * 返回当前 TTL 配置与最近一次清理结果（供 MCP 查询）。
     */
    public Map<String, Object> status() {
        RagProperties.Retention retention = properties.getRetention();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", retention.isEnabled());
        status.put("ttlSeconds", retention.getTtlSeconds());
        status.put("scanIntervalSeconds", retention.getScanIntervalSeconds());
        status.put("deleteArchive", retention.isDeleteArchive());
        status.put("maxDocumentsPerRun", retention.getMaxDocumentsPerRun());
        status.put("lastRun", lastReport.get());
        return status;
    }

    private static boolean isExpired(ArchivedDocumentSummary summary, Instant cutoff) {
        if (summary.updatedAt() == null || summary.updatedAt().isBlank()) {
            return false;
        }
        try {
            return Instant.parse(summary.updatedAt()).isBefore(cutoff);
        } catch (Exception e) {
            log.debug("归档 updatedAt 无法解析，跳过 TTL 判定: docId={}, updatedAt={}",
                    summary.documentId(), summary.updatedAt());
            return false;
        }
    }
}
