package com.smallrag.ragmcp.rag.vectorstore;

import com.smallrag.ragmcp.config.RagProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * SQLite 实现的内嵌向量库：chunks 表 + 纯 Java 余弦相似度检索。
 *
 * <p>不引入任何 JNI / native 扩展（CJK 依赖面为零），检索为全表扫描，
 * 复杂度 O(N × dimension)；规模建议 &lt;5 万片段，更大请使用 Qdrant 后端。
 *
 * <p>通过 WAL 模式支持一写多读（本项目为单实例），写路径用
 * {@link #writeLock} 串行化。建表与维度校验由 {@link #ensureReady()} 完成，
 * 在应用启动时（SmartInitializingSingleton）执行；维度不一致 fail-fast。
 *
 * @author zjk
 */
@Slf4j
public class SqliteVectorStore implements VectorStore {

    private final String path;
    private final int dimension;
    private final EmbeddingModel embeddingModel;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ReentrantLock writeLock = new ReentrantLock();

    public SqliteVectorStore(RagProperties properties, EmbeddingModel embeddingModel) {
        this.path = properties.getVectorStore().getSqlite().getPath();
        this.dimension = properties.getVectorStore().getSqlite().getDimension();
        this.embeddingModel = embeddingModel;
    }

    /**
     * 打开数据库、创建 schema（幂等）并校验维度一致性。
     *
     * @throws IllegalStateException 无法打开、创建失败或维度不一致时
     */
    public void ensureReady() {
        try (Connection connection = SqliteSupport.open(path)) {
            SqliteSupport.ensureSchema(connection);
            verifyDimension(connection);
        } catch (SQLException e) {
            throw new IllegalStateException("SQLite 初始化失败: " + path + "，原因=" + e.getMessage(), e);
        }
        log.info("SQLite 向量库就绪: {} (维度={})", path, dimension);
    }

    /** {@inheritDoc} */
    @Override
    public void add(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        List<String> texts = new ArrayList<>(documents.size());
        for (Document document : documents) {
            texts.add(document.getText());
        }
        List<float[]> vectors;
        try {
            vectors = embeddingModel.embed(texts);
        } catch (Exception e) {
            throw new IllegalStateException("Embedding 调用失败: " + e.getMessage(), e);
        }
        if (vectors == null || vectors.size() != documents.size()) {
            throw new IllegalStateException(
                    "Embedding 返回数量与文档数不一致: expected=" + documents.size()
                            + ", actual=" + (vectors == null ? -1 : vectors.size()));
        }
        writeLock.lock();
        try (Connection connection = SqliteSupport.open(path)) {
            SqliteSupport.ensureSchema(connection);
            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT OR REPLACE INTO small_rag_chunks"
                            + "(id, content, metadata, embedding, dimension,"
                            + " doc_id, chunk_type, parent_id, created_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?)")) {
                for (int i = 0; i < documents.size(); i++) {
                    Document document = documents.get(i);
                    float[] vector = vectors.get(i);
                    if (vector.length != dimension) {
                        throw new IllegalStateException(
                                "Embedding 维度与配置不一致: 期望=" + dimension + ", 实际=" + vector.length
                                        + "。请让 rag.vector-store.sqlite.dimension 与 Embedding 模型一致。");
                    }
                    Map<String, Object> metadata = document.getMetadata() == null
                            ? Map.of() : document.getMetadata();
                    insert.setString(1, document.getId());
                    insert.setString(2, document.getText());
                    insert.setString(3, objectMapper.writeValueAsString(metadata));
                    insert.setBytes(4, SqliteSupport.encodeVector(vector));
                    insert.setInt(5, dimension);
                    insert.setString(6, stringValue(metadata.get("doc_id")));
                    insert.setString(7, stringValue(metadata.get("chunk_type")));
                    insert.setString(8, stringValue(metadata.get("parent_id")));
                    insert.setString(9, Instant.now().toString());
                    insert.addBatch();
                }
                insert.executeBatch();
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw new IllegalStateException("SQLite 写入失败: " + path + "，原因=" + e.getMessage(), e);
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("SQLite 写入失败: " + path + "，原因=" + e.getMessage(), e);
        } finally {
            writeLock.unlock();
        }
    }

    /** {@inheritDoc} */
    @Override
    public void delete(List<String> idList) {
        if (idList == null || idList.isEmpty()) {
            return;
        }
        writeLock.lock();
        try (Connection connection = SqliteSupport.open(path)) {
            SqliteSupport.ensureSchema(connection);
            String placeholders = String.join(",", idList.stream().map(id -> "?").toList());
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM small_rag_chunks WHERE id IN (" + placeholders + ")")) {
                for (int i = 0; i < idList.size(); i++) {
                    delete.setString(i + 1, idList.get(i));
                }
                delete.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("SQLite 删除失败: " + path + "，原因=" + e.getMessage(), e);
        } finally {
            writeLock.unlock();
        }
    }

    /** {@inheritDoc} */
    @Override
    public void delete(Filter.Expression filterExpression) {
        if (filterExpression == null) {
            return;
        }
        List<SqliteFilters.Eq> pushdown = SqliteFilters.extractPushdown(filterExpression);
        writeLock.lock();
        try (Connection connection = SqliteSupport.open(path)) {
            SqliteSupport.ensureSchema(connection);
            if (pushdown != null && !pushdown.isEmpty()) {
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM small_rag_chunks WHERE " + SqliteFilters.pushdownWhere(pushdown))) {
                    bindEq(delete, pushdown);
                    delete.executeUpdate();
                }
                return;
            }
            // 表达式形态无法下推：全量扫描后用 Java 层语义计算待删 id
            List<String> doomed = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT id, metadata FROM small_rag_chunks");
                 ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    Map<String, Object> metadata = parseMetadata(rows.getString("metadata"));
                    if (SqliteFilters.matches(filterExpression, metadata)) {
                        doomed.add(rows.getString("id"));
                    }
                }
            }
            if (doomed.isEmpty()) {
                return;
            }
            String placeholders = String.join(",", doomed.stream().map(id -> "?").toList());
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM small_rag_chunks WHERE id IN (" + placeholders + ")")) {
                for (int i = 0; i < doomed.size(); i++) {
                    delete.setString(i + 1, doomed.get(i));
                }
                delete.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("SQLite 删除失败: " + path + "，原因=" + e.getMessage(), e);
        } finally {
            writeLock.unlock();
        }
    }

    /** {@inheritDoc} */
    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        String query = request.getQuery() == null ? "" : request.getQuery();
        List<float[]> embedded;
        try {
            embedded = embeddingModel.embed(List.of(query));
        } catch (Exception e) {
            throw new IllegalStateException("Embedding 调用失败: " + e.getMessage(), e);
        }
        if (embedded == null || embedded.isEmpty()) {
            throw new IllegalStateException("Embedding 返回空向量");
        }
        float[] queryVector = embedded.get(0);
        int topK = request.getTopK() > 0 ? request.getTopK() : SearchRequest.DEFAULT_TOP_K;
        double threshold = request.getSimilarityThreshold();
        Filter.Expression filter = request.hasFilterExpression() ? request.getFilterExpression() : null;
        List<SqliteFilters.Eq> pushdown = filter == null ? null : SqliteFilters.extractPushdown(filter);

        StringBuilder sql = new StringBuilder("SELECT id, content, metadata, embedding FROM small_rag_chunks");
        if (pushdown != null && !pushdown.isEmpty()) {
            sql.append(" WHERE ").append(SqliteFilters.pushdownWhere(pushdown));
        }
        List<Document> candidates = new ArrayList<>();
        try (Connection connection = SqliteSupport.open(path)) {
            SqliteSupport.ensureSchema(connection);
            try (PreparedStatement select = connection.prepareStatement(sql.toString())) {
                if (pushdown != null && !pushdown.isEmpty()) {
                    bindEq(select, pushdown);
                }
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        Map<String, Object> metadata = parseMetadata(rows.getString("metadata"));
                        if (filter != null && !SqliteFilters.matches(filter, metadata)) {
                            continue;
                        }
                        float[] vector = SqliteSupport.decodeVector(rows.getBytes("embedding"));
                        double cosine = SqliteSupport.cosineSimilarity(queryVector, vector);
                        if (threshold > 0d && cosine < threshold) {
                            continue;
                        }
                        candidates.add(Document.builder()
                                .id(rows.getString("id"))
                                .text(rows.getString("content"))
                                .metadata(metadata)
                                .score(cosine)
                                .build());
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("SQLite 检索失败: " + path + "，原因=" + e.getMessage(), e);
        }
        if (candidates.isEmpty()) {
            return List.of();
        }
        candidates.sort(Comparator.comparingDouble(candidate -> -(candidate.getScore() == null ? -1d : candidate.getScore())));
        return candidates.size() <= topK ? candidates : candidates.subList(0, topK);
    }

    /**
     * @return 存储路径（用于诊断 / 日志）
     */
    public String location() {
        return "sqlite:" + path;
    }

    private void verifyDimension(Connection connection) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT value FROM backend_meta WHERE key = ?")) {
            select.setString(1, SqliteSupport.META_KEY_DIMENSION);
            try (ResultSet rows = select.executeQuery()) {
                if (rows.next()) {
                    int actual = Integer.parseInt(rows.getString("value").trim());
                    if (actual != dimension) {
                        throw new IllegalStateException(String.format(
                                "SQLite 向量维度与配置不一致：数据库=%d, rag.vector-store.sqlite.dimension=%d。%n"
                                        + "请让 dimension 与 Embedding 模型一致，或改用新数据库文件 / 重建索引。",
                                actual, dimension));
                    }
                    return;
                }
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT OR IGNORE INTO backend_meta(key, value) VALUES (?, ?)")) {
            insert.setString(1, SqliteSupport.META_KEY_BACKEND);
            insert.setString(2, "sqlite");
            insert.executeUpdate();
            insert.setString(1, SqliteSupport.META_KEY_DIMENSION);
            insert.setString(2, String.valueOf(dimension));
            insert.executeUpdate();
        }
    }

    private void bindEq(PreparedStatement statement, List<SqliteFilters.Eq> pushdown) throws SQLException {
        for (int i = 0; i < pushdown.size(); i++) {
            statement.setString(i + 1, pushdown.get(i).value());
        }
    }

    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) {
            return new HashMap<>();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> metadata = objectMapper.readValue(json, Map.class);
            return metadata == null ? new HashMap<>() : metadata;
        } catch (Exception e) {
            log.warn("解析 SQLite 行 metadata 失败，按空对象处理: {}", e.toString());
            return new HashMap<>();
        }
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * @return 当前片段总数（供空库探活）；数据库不可用时抛异常，由调用方降级
     */
    public long countChunks() {
        try (Connection connection = SqliteSupport.open(path)) {
            SqliteSupport.ensureSchema(connection);
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT COUNT(*) FROM small_rag_chunks");
                 ResultSet rows = select.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("SQLite 统计片段数失败: " + path + "，原因=" + e.getMessage(), e);
        }
    }

    /**
     * @return 无对应子块父块的数量（管理 / 诊断用途）；统计失败抛异常
     */
    public long countOrphanParents() {
        try (Connection connection = SqliteSupport.open(path)) {
            SqliteSupport.ensureSchema(connection);
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("""
                         SELECT COUNT(*) FROM small_rag_chunks p
                         WHERE p.chunk_type = 'parent'
                           AND (p.parent_id IS NULL OR p.parent_id = ''
                             OR p.parent_id NOT IN (
                                 SELECT DISTINCT c.parent_id FROM small_rag_chunks c
                                 WHERE c.chunk_type IN ('child', 'chunk')
                                   AND c.parent_id IS NOT NULL AND c.parent_id <> ''))
                         """)) {
                return rows.next() ? rows.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("SQLite 统计孤儿父块数失败: " + path, e);
        }
    }
}
