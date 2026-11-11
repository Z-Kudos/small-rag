package com.smallrag.ragmcp.config;

import com.smallrag.ragmcp.rag.KnowledgeBaseProbe;
import com.smallrag.ragmcp.rag.reindex.ReindexStateBackend;
import com.smallrag.ragmcp.rag.reindex.SqliteReindexStateBackend;
import com.smallrag.ragmcp.rag.vectorstore.DocumentPointsPort;
import com.smallrag.ragmcp.rag.vectorstore.SqliteDocumentPointsPort;
import com.smallrag.ragmcp.rag.vectorstore.SqliteVectorStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * SQLite 向量库装配（{@code rag.vector-store.type=sqlite}）。
 *
 * <p>切换为 Qdrant 后端时本类不生效（见 {@link QdrantConfig}）。
 * SQLite 是本地单文件，初始失败属于配置或权限错误，直接 fail-fast；
 * 维度不一致同样会抛异常阻断启动。
 *
 * @author zjk
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "rag.vector-store.type", havingValue = "sqlite")
@Slf4j
public class SqliteVectorStoreConfig {

    @Bean
    @ConditionalOnMissingBean(VectorStore.class)
    public SqliteVectorStore sqliteVectorStore(RagProperties properties, EmbeddingModel embeddingModel) {
        log.info("向量库后端: sqlite {}", properties.getVectorStore().getSqlite().getPath());
        return new SqliteVectorStore(properties, embeddingModel);
    }

    /**
     * 文档 point 底层操作端口：SQL 枚举/删除/父块取回/孤儿清理。
     */
    @Bean
    public DocumentPointsPort sqliteDocumentPointsPort(RagProperties properties) {
        return new SqliteDocumentPointsPort(properties.getVectorStore().getSqlite().getPath());
    }

    /**
     * 重建任务状态后端：任务状态写入同一数据库文件的 {@code reindex_state} 表。
     */
    @Bean
    public ReindexStateBackend sqliteReindexStateBackend(ObjectMapper objectMapper,
            RagProperties properties) {
        return new SqliteReindexStateBackend(objectMapper, properties.getVectorStore().getSqlite().getPath());
    }

    /**
     * 知识库为空时短路，避免为一次注定无结果的检索调用 Embedding 模型。
     */
    @Bean
    public KnowledgeBaseProbe knowledgeBaseProbe(SqliteVectorStore sqliteVectorStore) {
        return () -> {
            try {
                return sqliteVectorStore.countChunks() == 0L;
            } catch (Exception e) {
                // SQLite 不可用：不短路，交给真实检索去暴露错误
                log.debug("SQLite 探活失败，继续走检索: {}", e.toString());
                return false;
            }
        };
    }

    /**
     * 启动时创建 schema 并校验维度；失败直接阻断启动。
     */
    @Bean
    public SmartInitializingSingleton sqliteInitializer(SqliteVectorStore sqliteVectorStore) {
        return sqliteVectorStore::ensureReady;
    }
}
