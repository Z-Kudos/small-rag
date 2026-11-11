package com.smallrag.ragmcp.rag.vectorstore;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite 支持工具：连接（WAL + busy_timeout）、schema 创建与向量编解码。
 *
 * @author zjk
 */
public final class SqliteSupport {

    static final String CHUNKS_TABLE = "small_rag_chunks";
    static final String STATE_TABLE = "reindex_state";
    static final String META_TABLE = "backend_meta";

    static final String META_KEY_BACKEND = "backend_type";
    static final String META_KEY_DIMENSION = "dimension";

    private SqliteSupport() {
    }

    /**
     * 打开一个 SQLite 连接并开启 WAL / busy_timeout。
     *
     * @param path 数据库文件路径（{@code :memory:} 可用，但每连接私有库，仅适合探活）
     * @return 已配置的连接
     * @throws IllegalStateException 无法打开时
     */
    public static Connection open(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalStateException("SQLite 路径为空");
        }
        try {
            Path parent = Paths.get(path).toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
            try (Statement pragma = connection.createStatement()) {
                pragma.execute("PRAGMA journal_mode=WAL");
                pragma.execute("PRAGMA busy_timeout=5000");
                pragma.execute("PRAGMA foreign_keys=ON");
            }
            return connection;
        } catch (Exception e) {
            throw new IllegalStateException("无法打开 SQLite 数据库: " + path + "，原因=" + e.getMessage(), e);
        }
    }

    /**
     * 创建 schema（幂等）。
     *
     * @param connection 已打开的连接
     * @throws SQLException DDL 失败时
     */
    public static void ensureSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS small_rag_chunks (
                      id          TEXT PRIMARY KEY,
                      content     TEXT NOT NULL,
                      metadata    TEXT NOT NULL,
                      embedding   BLOB NOT NULL,
                      dimension   INTEGER NOT NULL,
                      doc_id      TEXT,
                      chunk_type  TEXT,
                      parent_id   TEXT,
                      created_at  TEXT NOT NULL
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_chunks_doc_id ON small_rag_chunks(doc_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_chunks_chunk_type ON small_rag_chunks(chunk_type)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_chunks_parent_id ON small_rag_chunks(parent_id)");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS reindex_state (
                      task_id    TEXT PRIMARY KEY,
                      payload    TEXT NOT NULL,
                      updated_at TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS backend_meta (
                      key TEXT PRIMARY KEY,
                      value TEXT NOT NULL
                    )
                    """);
        }
    }

    public static byte[] encodeVector(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : vector) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }

    public static float[] decodeVector(byte[] blob) {
        if (blob == null || blob.length % Float.BYTES != 0) {
            return new float[0];
        }
        ByteBuffer buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
        float[] vector = new float[blob.length / Float.BYTES];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = buffer.getFloat();
        }
        return vector;
    }

    public static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return Double.NEGATIVE_INFINITY;
        }
        double dot = 0d;
        double normA = 0d;
        double normB = 0d;
        for (int i = 0; i < a.length; i++) {
            double x = a[i];
            double y = b[i];
            dot += x * y;
            normA += x * x;
            normB += y * y;
        }
        if (normA == 0d || normB == 0d) {
            return Double.NEGATIVE_INFINITY;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
