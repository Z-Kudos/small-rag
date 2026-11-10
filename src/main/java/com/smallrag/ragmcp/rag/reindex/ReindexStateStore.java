package com.smallrag.ragmcp.rag.reindex;

import com.smallrag.ragmcp.config.QdrantProperties;
import io.qdrant.client.QdrantClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 重建任务状态的持久化门面。
 *
 * <p>状态存放在 Qdrant 的独立集合 {@code <rag.qdrant.collection-name>_reindex_state} 中，
 * 跟随 Qdrant 的持久化，不依赖应用容器的文件系统；容器重建、换机器或本地开发都不会丢失
 * 进度快照。具体实现见 {@link QdrantReindexStateBackend}。
 *
 * <p>单实例下只持久化任务快照与检查点，不保存锁镜像；互斥由
 * {@link com.smallrag.ragmcp.rag.IndexActivityGuard} 在进程内完成。
 *
 * <p>读失败只告警并返回空（应用继续启动）；写失败由调用方决定是否降级。
 *
 * @author zjk
 */
@Component
@Slf4j
public class ReindexStateStore {

    private final ReindexStateBackend backend;

    @Autowired
    public ReindexStateStore(ObjectMapper objectMapper, QdrantProperties qdrantProperties,
            QdrantClient qdrantClient) {
        this(new QdrantReindexStateBackend(objectMapper, qdrantProperties, qdrantClient));
    }

    /**
     * 测试入口：注入任意后端。
     */
    ReindexStateStore(ReindexStateBackend backend) {
        this.backend = backend;
        log.info("重建任务状态存储: {}", backend.location());
    }

    /**
     * 持久化的任务状态：公开快照 + 续接所需的检查点。
     *
     * @param view                  任务快照
     * @param cleared               本次任务是否已完成清空（{@code rebuild}）
     * @param completedDocumentIds  已成功重建的文档 id，续接时跳过
     */
    public record PersistedTask(
            ReindexTaskService.ReindexTaskView view,
            boolean cleared,
            Set<String> completedDocumentIds) {
    }

    /**
     * @return 存储位置（用于诊断 / 测试）
     */
    public String location() {
        return backend.location();
    }

    /**
     * 保存或覆盖任务状态；失败抛运行时异常，由调用方决定是否降级。
     *
     * @param task 任务状态
     */
    public synchronized void save(PersistedTask task) {
        if (task == null || task.view() == null || task.view().taskId() == null) {
            throw new IllegalArgumentException("重建任务状态缺少 taskId");
        }
        backend.save(task);
    }

    /**
     * 读取全部任务状态；失败只告警并返回空列表。
     *
     * @return 按 {@code startedAt} 升序排列的任务状态
     */
    public synchronized List<PersistedTask> loadAll() {
        try {
            return backend.loadAll();
        } catch (Exception e) {
            log.warn("读取重建任务状态失败，按无历史任务处理: {}", e.toString());
            return List.of();
        }
    }

    /**
     * 删除任务状态（保留窗口之外的旧任务）。
     *
     * @param taskId 任务 id
     */
    public synchronized void delete(String taskId) {
        try {
            backend.delete(taskId);
        } catch (Exception e) {
            log.warn("删除重建任务状态失败: taskId={}, 原因={}", taskId, e.toString());
        }
    }
}
