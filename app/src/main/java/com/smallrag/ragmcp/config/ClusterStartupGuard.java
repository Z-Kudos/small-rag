package com.smallrag.ragmcp.config;

import com.smallrag.ragmcp.rag.archive.DocumentArchive;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 启动期校验归档卷是否按 distributed 要求共享。
 *
 * <p>多副本共享归档目录是自动重切、任务续接与目录导入一致性的前提。这里只告警，
 * 不阻止启动，避免把“单副本临时验证”场景直接卡死。
 *
 * @author zjk
 */
@Component
@Slf4j
public class ClusterStartupGuard {

    private final DistributedProperties distributed;
    private final ArchiveProperties archive;
    private final DocumentArchive documentArchive;

    public ClusterStartupGuard(DistributedProperties distributed, ArchiveProperties archive,
            DocumentArchive documentArchive) {
        this.distributed = distributed;
        this.archive = archive;
        this.documentArchive = documentArchive;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void checkArchiveSharing() {
        if (!distributed.isEnabled() || !documentArchive.isEnabled() || archive.isShared()) {
            return;
        }
        log.warn("distributed 模式未声明共享归档卷：请为所有副本挂载同一 RWX 目录并设置 "
                + "SMALL_RAG_ARCHIVE_SHARED=true，否则自动重切与任务续接可能不一致");
    }
}
