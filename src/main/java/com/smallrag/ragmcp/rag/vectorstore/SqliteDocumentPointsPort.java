package com.smallrag.ragmcp.rag.vectorstore;

import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * SQLite 实现的 {@link DocumentPointsPort}：用 SQL 完成
 * 旧片段枚举、删除、父块取回与孤儿父块清理。
 *
 * <p>父块 id 由 {@link PointIds#parentPointUuid} 确定性生成，与 Qdrant 后端一致。
 *
 * @author zjk
 */
@Slf4j
public class SqliteDocumentPointsPort implements DocumentPointsPort {

    private final String path;

    public SqliteDocumentPointsPort(String path) {
        this.path = path;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Set<String>> findPointIds(String docId) {
        try (Connection connection = SqliteSupport.open(path)) {
            SqliteSupport.ensureSchema(connection);
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT id FROM small_rag_chunks WHERE doc_id = ?")) {
                select.setString(1, docId);
                Set<String> ids = new HashSet<>();
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        ids.add(rows.getString("id"));
                    }
                }
                return Optional.of(ids);
            }
        } catch (Exception e) {
            log.debug("SQLite 读取文档旧片段 id 失败，回退为先删后写: docId={}, 原因={}",
                    docId, e.toString());
            return Optional.empty();
        }
    }

    /** {@inheritDoc} */
    @Override
    public void deletePoints(Set<String> pointIds) throws IllegalStateException {
        if (pointIds == null || pointIds.isEmpty()) {
            return;
        }
        try (Connection connection = SqliteSupport.open(path)) {
            SqliteSupport.ensureSchema(connection);
            String placeholders = String.join(",", pointIds.stream().map(id -> "?").toList());
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM small_rag_chunks WHERE id IN (" + placeholders + ")")) {
                int index = 1;
                for (String id : pointIds) {
                    delete.setString(index++, id);
                }
                delete.executeUpdate();
            }
        } catch (Exception e) {
            throw new IllegalStateException("SQLite 删除旧片段失败: " + pointIds.size() + "，原因=" + e.getMessage(), e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public CleanupResult cleanupOrphans() throws IllegalStateException {
        try (Connection connection = SqliteSupport.open(path)) {
            SqliteSupport.ensureSchema(connection);
            try (PreparedStatement legacy = connection.prepareStatement("""
                    DELETE FROM small_rag_chunks
                    WHERE (chunk_type IS NULL OR chunk_type = '')
                      AND doc_id IS NOT NULL AND doc_id <> ''
                    """)) {
                legacy.executeUpdate();
            }
            Map<String, String> parents = new HashMap<>();
            try (PreparedStatement selectParents = connection.prepareStatement(
                    "SELECT id, parent_id FROM small_rag_chunks WHERE chunk_type = 'parent'");
                 ResultSet rows = selectParents.executeQuery()) {
                while (rows.next()) {
                    parents.put(rows.getString("id"), rows.getString("parent_id"));
                }
            }
            Set<String> liveParentIds = new HashSet<>();
            try (PreparedStatement selectLive = connection.prepareStatement("""
                    SELECT DISTINCT parent_id FROM small_rag_chunks
                    WHERE chunk_type IN ('child', 'chunk')
                      AND parent_id IS NOT NULL AND parent_id <> ''
                    """);
                 ResultSet rows = selectLive.executeQuery()) {
                while (rows.next()) {
                    liveParentIds.add(rows.getString("parent_id"));
                }
            }
            List<String> orphans = new ArrayList<>();
            for (Map.Entry<String, String> entry : parents.entrySet()) {
                String parentId = entry.getValue();
                if (parentId == null || parentId.isBlank() || !liveParentIds.contains(parentId)) {
                    orphans.add(entry.getKey());
                }
            }
            if (!orphans.isEmpty()) {
                String placeholders = String.join(",", orphans.stream().map(id -> "?").toList());
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM small_rag_chunks WHERE id IN (" + placeholders + ")")) {
                    for (int i = 0; i < orphans.size(); i++) {
                        delete.setString(i + 1, orphans.get(i));
                    }
                    delete.executeUpdate();
                }
            }
            log.info("SQLite 孤儿片段清理完成: parents={}, orphanParents={}",
                    parents.size(), orphans.size());
            return new CleanupResult(parents.size(), orphans.size());
        } catch (Exception e) {
            throw new IllegalStateException("SQLite 孤儿片段清理失败: " + path + "，原因=" + e.getMessage(), e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public Map<String, ParentPoint> fetchParents(Set<String> parentIds) {
        if (parentIds == null || parentIds.isEmpty()) {
            return Map.of();
        }
        Map<String, ParentPoint> result = new HashMap<>();
        try (Connection connection = SqliteSupport.open(path)) {
            SqliteSupport.ensureSchema(connection);
            String placeholders = String.join(",", parentIds.stream().map(id -> "?").toList());
            Map<String, String> pointIds = new HashMap<>();
            for (String parentId : parentIds) {
                pointIds.put(PointIds.parentPointUuid(parentId).toString(), parentId);
            }
            String idPlaceholders = String.join(",", pointIds.keySet().stream().map(id -> "?").toList());
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT id, content, embedding FROM small_rag_chunks WHERE id IN (" + idPlaceholders + ")")) {
                int index = 1;
                for (String pointId : pointIds.keySet()) {
                    select.setString(index++, pointId);
                }
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        String parentId = pointIds.get(rows.getString("id"));
                        if (parentId == null) {
                            continue;
                        }
                        String content = rows.getString("content");
                        if (content == null || content.isBlank()) {
                            continue;
                        }
                        result.put(parentId, new ParentPoint(parentId, rows.getString("id"),
                                content, SqliteSupport.decodeVector(rows.getBytes("embedding"))));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("SQLite 父块取回失败，回退为子块结果: {}", e.toString());
            return Map.of();
        }
        return result;
    }
}
