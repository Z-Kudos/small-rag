package com.smallrag.ragmcp.rag.reindex;

import com.smallrag.ragmcp.rag.vectorstore.SqliteSupport;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 把重建任务状态写入 SQLite 的 {@code reindex_state} 表（与向量数据同一数据库文件）。
 *
 * <p>任务快照以 JSON 字符串整体存放；单实例下不保存任何锁状态。
 * 读写失败直接抛出运行时异常，由 {@link ReindexStateStore} 决定是否降级。
 *
 * @author zjk
 */
@Slf4j
public final class SqliteReindexStateBackend implements ReindexStateBackend {

    private final ObjectMapper objectMapper;
    private final String path;

    public SqliteReindexStateBackend(ObjectMapper objectMapper, String path) {
        this.objectMapper = objectMapper;
        this.path = path;
    }

    @Override
    public String location() {
        return "sqlite:" + path + "#reindex_state";
    }

    @Override
    public List<ReindexStateStore.PersistedTask> loadAll() {
        List<ReindexStateStore.PersistedTask> tasks = new ArrayList<>();
        try (Connection connection = SqliteSupport.open(path)) {
            SqliteSupport.ensureSchema(connection);
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT payload FROM reindex_state");
                 ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    ReindexStateStore.PersistedTask task = readTask(rows.getString("payload"));
                    if (task != null) {
                        tasks.add(task);
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取重建任务状态失败: " + location(), e);
        }
        tasks.sort(Comparator.comparing(task -> String.valueOf(task.view().startedAt())));
        return tasks;
    }

    @Override
    public void save(ReindexStateStore.PersistedTask task) {
        String json;
        try {
            json = objectMapper.writeValueAsString(task);
        } catch (Exception e) {
            throw new IllegalStateException("序列化重建任务状态失败: " + task.view().taskId(), e);
        }
        try (Connection connection = SqliteSupport.open(path)) {
            SqliteSupport.ensureSchema(connection);
            try (PreparedStatement upsert = connection.prepareStatement("""
                    INSERT OR REPLACE INTO reindex_state(task_id, payload, updated_at)
                    VALUES (?, ?, ?)
                    """)) {
                upsert.setString(1, task.view().taskId());
                upsert.setString(2, json);
                upsert.setString(3, Instant.now().toString());
                upsert.executeUpdate();
            }
        } catch (Exception e) {
            throw new IllegalStateException("写入重建任务状态失败: " + location(), e);
        }
    }

    @Override
    public void delete(String taskId) {
        try (Connection connection = SqliteSupport.open(path)) {
            SqliteSupport.ensureSchema(connection);
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM reindex_state WHERE task_id = ?")) {
                delete.setString(1, taskId);
                delete.executeUpdate();
            }
        } catch (Exception e) {
            throw new IllegalStateException("删除重建任务状态失败: " + taskId, e);
        }
    }

    private ReindexStateStore.PersistedTask readTask(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            ReindexStateStore.PersistedTask task =
                    objectMapper.readValue(payload, ReindexStateStore.PersistedTask.class);
            if (task.view() == null || task.view().taskId() == null) {
                log.warn("跳过不完整的重建任务状态: payload={}", payload);
                return null;
            }
            return task;
        } catch (Exception e) {
            log.warn("跳过损坏的重建任务状态: 原因={}", e.toString());
            return null;
        }
    }
}
