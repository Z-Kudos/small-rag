package com.smallrag.ragmcp.rag.vectorstore;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SQLite 实现的 {@link ChunkCatalog}：直接查询 {@code small_rag_chunks} 表。
 *
 * @author zjk
 */
@Slf4j
public class SqliteChunkCatalog implements ChunkCatalog {

    private final String path;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SqliteChunkCatalog(String path) {
        this.path = path;
    }

    @Override
    public List<ChunkSummary> listChunks(String docId) throws IllegalStateException {
        String sql = "SELECT id, content, metadata FROM small_rag_chunks"
                + (docId == null ? "" : " WHERE doc_id = ?");
        List<ChunkSummary> result = new ArrayList<>();
        try (Connection connection = SqliteSupport.open(path)) {
            SqliteSupport.ensureSchema(connection);
            try (PreparedStatement select = connection.prepareStatement(sql)) {
                if (docId != null) {
                    select.setString(1, docId);
                }
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        String metadataJson = rows.getString("metadata");
                        Map<String, Object> metadata = parseMetadata(metadataJson);
                        result.add(new ChunkSummary(rows.getString("id"), rows.getString("content"), metadata));
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("SQLite 枚举 chunk 失败: " + path + "，原因=" + e.getMessage(), e);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> metadata = objectMapper.readValue(json, Map.class);
            return metadata == null ? Map.of() : metadata;
        } catch (Exception e) {
            log.warn("解析 SQLite chunk metadata 失败，按空对象处理: {}", e.toString());
            return Map.of();
        }
    }
}
