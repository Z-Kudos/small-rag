package com.smallrag.ragmcp.rag;

import com.smallrag.ragmcp.config.QdrantProperties;
import com.smallrag.ragmcp.rag.archive.ArchivedDocument;
import com.smallrag.ragmcp.rag.archive.DocumentArchive;
import com.smallrag.ragmcp.rag.chunking.ChunkingSignature;
import com.smallrag.ragmcp.config.RagProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动时检测切分配置变化，并自动重切已归档的原始文档。
 *
 * <p>Qdrant 里只有分块，没有原文，无法从旧分块反推出可靠原文；因此自动重切
 * 依赖 {@link DocumentArchive}。归档不可用或单篇重切失败时只告警，
 * 保留旧向量并让下次启动继续重试，不阻断应用启动。
 *
 * @author zjk
 */
@Component
@RequiredArgsConstructor
@Slf4j
@Order(Ordered.LOWEST_PRECEDENCE)
public class ChunkingReindexRunner implements ApplicationRunner {

    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    private final DocumentArchive documentArchive;
    private final RagService ragService;
    private final RagProperties properties;
    private final QdrantProperties qdrantProperties;
    private final ReindexTaskService reindexTaskService;

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.getChunking().isAutoRechunk()) {
            log.info("自动重切已关闭（rag.chunking.auto-rechunk=false）");
            return;
        }
        if (reindexTaskService.hasRecoveredTask()) {
            log.warn("存在重启时中断、待续接的重建任务，跳过启动自动重切以避免重复写入");
            return;
        }
        if (!documentArchive.isEnabled()) {
            log.warn("原始文档归档不可用，跳过自动重切");
            return;
        }

        String signature = ChunkingSignature.of(properties, qdrantProperties.getCollectionName());
        List<ArchivedDocument> documents = documentArchive.listAll();
        List<ArchivedDocument> pending = documents.stream()
                .filter(document -> !signature.equals(document.chunkSignature()))
                .toList();

        if (pending.isEmpty()) {
            log.debug("切分配置未变化，无需自动重切（已归档 {} 篇）", documents.size());
            return;
        }

        log.info("检测到切分配置变化，开始自动重切: {} 篇（当前策略 {}）",
                pending.size(), properties.getChunking().getStrategy());
        int succeeded = 0;
        int failed = 0;
        int consecutiveFailures = 0;
        for (ArchivedDocument document : pending) {
            try {
                ragService.reindex(document.documentId(), document.text(), document.metadata());
                documentArchive.markIndexed(document.documentId(), signature);
                succeeded++;
                consecutiveFailures = 0;
            } catch (Exception e) {
                failed++;
                consecutiveFailures++;
                log.warn("自动重切失败，将在下次启动重试: docId={}, 原因={}",
                        document.documentId(), e.toString());
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    log.error("连续 {} 篇重切失败，停止本次自动重切（可能是 Qdrant 或 Embedding 服务不可用）",
                            consecutiveFailures);
                    break;
                }
            }
        }
        log.info("自动重切完成: 成功={}, 失败={}", succeeded, failed);
    }
}
